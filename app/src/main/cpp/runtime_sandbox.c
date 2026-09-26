/*
 * The engine sandbox's network half: a seccomp filter both :runtime and
 * :runtime_isolated install on themselves before any engine code is
 * loaded (EnginehostApplication.onCreate; Layer 2's isolatedProcess UID
 * is additional to this, not a replacement for it -- an isolated process
 * still starts as an ordinary Linux process that can open a socket
 * unless something stops it). See docs/engine-sandbox.md for what it
 * covers and what it cannot.
 *
 * The filter stacks on the platform's own app filter and can only narrow it.
 * It refuses, with EACCES (what a missing INTERNET permission looks like):
 *   - socket() for AF_INET and AF_INET6, so no IP socket can be made;
 *   - io_uring_setup(), which could make sockets without socket();
 *   - any system call made in an architecture other than the process's own
 *     (x86_64's int 0x80 i386 entry, and x32), which would otherwise go
 *     around the checks above.
 * Everything else is allowed: Unix sockets (logd, netd, binder helpers),
 * netlink, files -- including pread/pwrite/memfd_create/fcntl, which the
 * isolated launch milestone's own fd-passing and shared-memory mechanisms
 * use on both sides of the isolation boundary and which this filter was
 * reviewed against and does not touch. It cannot be removed once
 * installed, and every thread and child process inherits it. It returns
 * EACCES, never SIGSYS, so it cannot itself be why a sandboxed process
 * dies.
 */
#include <errno.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#ifdef __ANDROID__
#include <jni.h>
#endif

#ifndef __NR_io_uring_setup
#define __NR_io_uring_setup 425
#endif
#ifndef SECCOMP_SET_MODE_FILTER
#define SECCOMP_SET_MODE_FILTER 1
#endif
#ifndef SECCOMP_FILTER_FLAG_TSYNC
#define SECCOMP_FILTER_FLAG_TSYNC 1
#endif

#if defined(__aarch64__)
#define SANDBOX_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define SANDBOX_ARCH AUDIT_ARCH_X86_64
#elif defined(__arm__)
#define SANDBOX_ARCH AUDIT_ARCH_ARM
#endif

/* The filter covers every thread of the process. */
#define SANDBOX_ALL_THREADS 0
/* The kernel refused to synchronise threads; only the calling thread and those it starts are covered. */
#define SANDBOX_CALLING_THREAD 1

/*
 * Returns SANDBOX_ALL_THREADS or SANDBOX_CALLING_THREAD, or a negative errno
 * when nothing was installed. i386 is not supported: bionic makes sockets
 * there through socketcall(), whose arguments a filter cannot read.
 */
int enginehost_deny_internet(void) {
#ifndef SANDBOX_ARCH
    return -ENOSYS;
#else
    /* Jump offsets count from the instruction after the jump. */
    struct sock_filter filter[] = {
        /* 0 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        /* 1 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SANDBOX_ARCH, 0, 8),
        /* 2 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        /* 3 */ BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 6, 0),
        /* 4 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 5, 0),
        /* 5 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 3),
        /* The domain is an int: the kernel reads the low 32 bits, and so does this (little-endian). */
        /* 6 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        /* 7 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET, 2, 0),
        /* 8 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET6, 1, 0),
        /* 9 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        /* 10 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
    };
    struct sock_fprog program = {
        .len = (unsigned short) (sizeof(filter) / sizeof(filter[0])),
        .filter = filter,
    };
    /* Zygote already set this for every app thread; it is required, and repeating it is harmless. */
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return -errno;
    long synced = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program);
    if (synced == 0) return SANDBOX_ALL_THREADS;
    /* A positive result names a thread that could not be synchronised; -1 is an error. Either way nothing was installed. */
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program, 0, 0) == 0) return SANDBOX_CALLING_THREAD;
    return -errno;
#endif
}

#ifdef __ANDROID__
JNIEXPORT jint JNICALL
Java_dev_enginehost_RuntimeSandbox_denyInternet(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return enginehost_deny_internet();
}
#endif
