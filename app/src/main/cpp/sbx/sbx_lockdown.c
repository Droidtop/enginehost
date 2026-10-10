/*
 * The confined half of sbx (see sbx.h): the seccomp filter and the SIGSYS
 * handler that forwards path system calls to the broker.
 *
 * The filter, after an architecture check (a call made through another
 * architecture's entry, such as x86_64's int 0x80 or x32, is refused):
 *   - traps every call the broker can answer: open, stat, access,
 *     readlink, mkdir, unlink, rename and statfs in all their forms;
 *   - refuses with ENOSYS statx and openat2, whose callers fall back to
 *     fstatat and openat;
 *   - refuses with EACCES every other call that names a path or reaches
 *     past the process: chdir and fchdir (the handler resolves relative
 *     paths against the directory the process locked down in), truncate,
 *     link, symlink, mknod, chmod, chown, utimes on a path, inotify and
 *     fanotify marks, file handles, path xattrs, execve, socket, bind and
 *     connect (a confined process uses the sockets it already has and
 *     socketpair), ptrace, process_vm_readv/writev, pidfd_getfd,
 *     process_madvise, bpf, perf_event_open and io_uring;
 *   - allows everything else, including every call on a descriptor.
 * It stacks on whatever filter the process already has and can only
 * narrow it.
 */
#define _GNU_SOURCE
#include "sbx.h"
#include "sbx_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <sys/uio.h>
#include <unistd.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#ifndef SECCOMP_SET_MODE_FILTER
#define SECCOMP_SET_MODE_FILTER 1
#endif
#ifndef SECCOMP_FILTER_FLAG_TSYNC
#define SECCOMP_FILTER_FLAG_TSYNC 1
#endif
#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif
#ifndef O_PATH
#define O_PATH 010000000
#endif
#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif
#ifndef __NR_faccessat2
#define __NR_faccessat2 439
#endif
#ifndef __NR_openat2
#define __NR_openat2 437
#endif
#ifndef __NR_pidfd_getfd
#define __NR_pidfd_getfd 438
#endif
#ifndef __NR_process_madvise
#define __NR_process_madvise 440
#endif
#ifndef __NR_io_uring_setup
#define __NR_io_uring_setup 425
#define __NR_io_uring_enter 426
#define __NR_io_uring_register 427
#endif

#if defined(__aarch64__)
#define SBX_ARCH AUDIT_ARCH_AARCH64
#define ARG(uc, i) ((long) (uc)->uc_mcontext.regs[i])
#define SET_RESULT(uc, v) ((uc)->uc_mcontext.regs[0] = (unsigned long) (v))
#elif defined(__x86_64__)
#define SBX_ARCH AUDIT_ARCH_X86_64
static const int x86_args[6] = {REG_RDI, REG_RSI, REG_RDX, REG_R10, REG_R8, REG_R9};
#define ARG(uc, i) ((long) (uc)->uc_mcontext.gregs[x86_args[i]])
#define SET_RESULT(uc, v) ((uc)->uc_mcontext.gregs[REG_RAX] = (long) (v))
#endif

/* SECCOMP_RET_DATA of our traps, which tells them from anyone else's SIGSYS. */
#define SBX_TRAP_DATA 0x5b0

#ifdef SBX_ARCH
/* 64-bit ARM and x86 only; elsewhere sbx_lockdown() answers ENOSYS. */
static int broker = -1;
static int start_dir = -1;
static struct sigaction previous;

static long forward(uint32_t op, int dirfd, const char *path, int dirfd2, const char *path2,
                    int flags, uint32_t mode, void *out, size_t out_len) {
    if (path == NULL || (op == SBX_RENAME && path2 == NULL)) return -EFAULT;
    size_t length = strnlen(path, SBX_PATH_MAX);
    size_t length2 = op == SBX_RENAME ? strnlen(path2, SBX_PATH_MAX) : 0;
    if (length == SBX_PATH_MAX || length2 == SBX_PATH_MAX) return -ENAMETOOLONG;
    if (length == 0 || (op == SBX_RENAME && length2 == 0)) return -ENOENT;

    struct sbx_request request;
    memset(&request, 0, sizeof(request));
    request.op = op;
    request.flags = flags;
    request.mode = mode;
    request.path_len = (uint32_t) length;
    request.path2_len = (uint32_t) length2;
    request.tid = (int32_t) syscall(__NR_gettid);

    int answer[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, answer) != 0) return -errno;
    int fds[3];
    int count = 0;
    fds[count++] = answer[1];
    if (path[0] != '/') {
        fds[count++] = dirfd == AT_FDCWD ? start_dir : dirfd;
        request.dirfd_sent = 1;
    }
    if (op == SBX_RENAME && path2[0] != '/') {
        fds[count++] = dirfd2 == AT_FDCWD ? start_dir : dirfd2;
        request.dirfd2_sent = 1;
    }

    struct iovec parts[3] = {
        {&request, sizeof(request)},
        {(void *) path, length},
        {(void *) path2, length2},
    };
    union {
        char buffer[CMSG_SPACE(sizeof(int) * 3)];
        struct cmsghdr align;
    } control;
    memset(&control, 0, sizeof(control));
    struct msghdr message;
    memset(&message, 0, sizeof(message));
    message.msg_iov = parts;
    message.msg_iovlen = length2 ? 3 : 2;
    message.msg_control = control.buffer;
    message.msg_controllen = CMSG_SPACE(sizeof(int) * count);
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int) * count);
    memcpy(CMSG_DATA(header), fds, sizeof(int) * count);

    ssize_t sent;
    do sent = sendmsg(broker, &message, MSG_NOSIGNAL);
    while (sent < 0 && errno == EINTR);
    int send_error = errno;
    close(answer[1]);
    if (sent < 0) {
        close(answer[0]);
        /* EBADF: a bad directory descriptor of the caller's; anything else, the broker is gone. */
        return send_error == EBADF ? -EBADF : -EIO;
    }

    struct sbx_reply reply;
    memset(&reply, 0, sizeof(reply));
    struct iovec back[2] = {{&reply, sizeof(reply)}, {out, out_len}};
    union {
        char buffer[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } back_control;
    memset(&back_control, 0, sizeof(back_control));
    struct msghdr answer_message;
    memset(&answer_message, 0, sizeof(answer_message));
    answer_message.msg_iov = back;
    answer_message.msg_iovlen = out_len ? 2 : 1;
    answer_message.msg_control = back_control.buffer;
    answer_message.msg_controllen = sizeof(back_control.buffer);
    int receive_flags = (op == SBX_OPEN && (flags & O_CLOEXEC)) ? MSG_CMSG_CLOEXEC : 0;
    ssize_t received;
    do received = recvmsg(answer[0], &answer_message, receive_flags);
    while (received < 0 && errno == EINTR);
    close(answer[0]);
    if (received < (ssize_t) sizeof(reply)) return -EIO;

    int passed = -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&answer_message); c; c = CMSG_NXTHDR(&answer_message, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len >= CMSG_LEN(sizeof(int))) {
            memcpy(&passed, CMSG_DATA(c), sizeof(int));
        }
    }
    if (op == SBX_OPEN && reply.result >= 0) {
        if (passed < 0) return -EIO;
        return passed;
    }
    if (passed >= 0) close(passed);
    return (long) reply.result;
}

static long dispatch(long nr, ucontext_t *uc) {
    long a0 = ARG(uc, 0), a1 = ARG(uc, 1), a2 = ARG(uc, 2), a3 = ARG(uc, 3), a4 = ARG(uc, 4);
    switch (nr) {
    case __NR_openat:
        return forward(SBX_OPEN, (int) a0, (const char *) a1, 0, NULL, (int) a2, (uint32_t) a3, NULL, 0);
    case __NR_newfstatat: {
        const char *path = (const char *) a1;
        if ((a3 & AT_EMPTY_PATH) && path != NULL && path[0] == '\0') {
            return syscall(__NR_fstat, (int) a0, (void *) a2) == 0 ? 0 : -errno;
        }
        return forward(SBX_STAT, (int) a0, path, 0, NULL, (int) (a3 & AT_SYMLINK_NOFOLLOW), 0,
                       (void *) a2, sizeof(struct stat));
    }
    case __NR_faccessat:
    case __NR_faccessat2:
        return forward(SBX_ACCESS, (int) a0, (const char *) a1, 0, NULL, 0, (uint32_t) a2, NULL, 0);
    case __NR_readlinkat:
        if (a3 <= 0) return -EINVAL;
        return forward(SBX_READLINK, (int) a0, (const char *) a1, 0, NULL, 0, (uint32_t) a3, (void *) a2,
                       (size_t) a3);
    case __NR_mkdirat:
        return forward(SBX_MKDIR, (int) a0, (const char *) a1, 0, NULL, 0, (uint32_t) a2, NULL, 0);
    case __NR_unlinkat:
        return forward(SBX_UNLINK, (int) a0, (const char *) a1, 0, NULL, (int) (a2 & AT_REMOVEDIR), 0, NULL, 0);
#ifdef __NR_renameat
    case __NR_renameat:
        return forward(SBX_RENAME, (int) a0, (const char *) a1, (int) a2, (const char *) a3, 0, 0, NULL, 0);
#endif
    case __NR_renameat2:
        return forward(SBX_RENAME, (int) a0, (const char *) a1, (int) a2, (const char *) a3, (int) a4, 0, NULL, 0);
    case __NR_statfs:
        return forward(SBX_STATFS, AT_FDCWD, (const char *) a0, 0, NULL, 0, 0, (void *) a1, sizeof(struct statfs));
#ifdef __NR_open
    case __NR_open:
        return forward(SBX_OPEN, AT_FDCWD, (const char *) a0, 0, NULL, (int) a1, (uint32_t) a2, NULL, 0);
    case __NR_creat:
        return forward(SBX_OPEN, AT_FDCWD, (const char *) a0, 0, NULL, O_CREAT | O_WRONLY | O_TRUNC,
                       (uint32_t) a1, NULL, 0);
    case __NR_stat:
        return forward(SBX_STAT, AT_FDCWD, (const char *) a0, 0, NULL, 0, 0, (void *) a1, sizeof(struct stat));
    case __NR_lstat:
        return forward(SBX_STAT, AT_FDCWD, (const char *) a0, 0, NULL, AT_SYMLINK_NOFOLLOW, 0, (void *) a1,
                       sizeof(struct stat));
    case __NR_access:
        return forward(SBX_ACCESS, AT_FDCWD, (const char *) a0, 0, NULL, 0, (uint32_t) a1, NULL, 0);
    case __NR_readlink:
        if (a2 <= 0) return -EINVAL;
        return forward(SBX_READLINK, AT_FDCWD, (const char *) a0, 0, NULL, 0, (uint32_t) a2, (void *) a1,
                       (size_t) a2);
    case __NR_mkdir:
        return forward(SBX_MKDIR, AT_FDCWD, (const char *) a0, 0, NULL, 0, (uint32_t) a1, NULL, 0);
    case __NR_unlink:
        return forward(SBX_UNLINK, AT_FDCWD, (const char *) a0, 0, NULL, 0, 0, NULL, 0);
    case __NR_rmdir:
        return forward(SBX_UNLINK, AT_FDCWD, (const char *) a0, 0, NULL, AT_REMOVEDIR, 0, NULL, 0);
    case __NR_rename:
        return forward(SBX_RENAME, AT_FDCWD, (const char *) a0, AT_FDCWD, (const char *) a1, 0, 0, NULL, 0);
#endif
    default:
        return -ENOSYS;
    }
}

static void on_sigsys(int signal_number, siginfo_t *info, void *context) {
    if (info->si_code != SYS_SECCOMP || info->si_errno != SBX_TRAP_DATA) {
        /* Not ours (the platform's own filter traps too): hand it on as if we were not here. */
        if ((previous.sa_flags & SA_SIGINFO) && previous.sa_sigaction != NULL) {
            previous.sa_sigaction(signal_number, info, context);
        } else if (previous.sa_handler != SIG_DFL && previous.sa_handler != SIG_IGN) {
            previous.sa_handler(signal_number);
        } else {
            signal(SIGSYS, SIG_DFL);
            syscall(__NR_tgkill, getpid(), syscall(__NR_gettid), SIGSYS);
        }
        return;
    }
    int saved_errno = errno;
    ucontext_t *uc = (ucontext_t *) context;
    SET_RESULT(uc, dispatch(info->si_syscall, uc));
    errno = saved_errno;
}

#define FILTER_MAX 160
static struct sock_filter filter[FILTER_MAX];
static unsigned short filter_length;

static void emit(struct sock_filter instruction) {
    if (filter_length < FILTER_MAX) filter[filter_length] = instruction;
    filter_length++;
}

/* One rule: if the call is nr, return action. The accumulator holds nr throughout. */
static void rule(unsigned nr, unsigned action) {
    emit((struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1));
    emit((struct sock_filter) BPF_STMT(BPF_RET | BPF_K, action));
}

#define TRAP (SECCOMP_RET_TRAP | SBX_TRAP_DATA)
#define REFUSE (SECCOMP_RET_ERRNO | EACCES)
#define NOSYS (SECCOMP_RET_ERRNO | ENOSYS)

static int build_filter(void) {
    filter_length = 0;
    emit((struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)));
    emit((struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SBX_ARCH, 1, 0));
    emit((struct sock_filter) BPF_STMT(BPF_RET | BPF_K, REFUSE));
    emit((struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)));
#ifdef __x86_64__
    /* x32 calls share the architecture but set this bit. */
    emit((struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1));
    emit((struct sock_filter) BPF_STMT(BPF_RET | BPF_K, REFUSE));
#endif
    /* Brokered. */
    rule(__NR_openat, TRAP);
    rule(__NR_newfstatat, TRAP);
    rule(__NR_faccessat, TRAP);
    rule(__NR_faccessat2, TRAP);
    rule(__NR_readlinkat, TRAP);
    rule(__NR_mkdirat, TRAP);
    rule(__NR_unlinkat, TRAP);
#ifdef __NR_renameat
    rule(__NR_renameat, TRAP);
#endif
    rule(__NR_renameat2, TRAP);
    rule(__NR_statfs, TRAP);
#ifdef __NR_open
    rule(__NR_open, TRAP);
    rule(__NR_creat, TRAP);
    rule(__NR_stat, TRAP);
    rule(__NR_lstat, TRAP);
    rule(__NR_access, TRAP);
    rule(__NR_readlink, TRAP);
    rule(__NR_mkdir, TRAP);
    rule(__NR_unlink, TRAP);
    rule(__NR_rmdir, TRAP);
    rule(__NR_rename, TRAP);
#endif
    /* Callers fall back to the brokered forms. */
    rule(__NR_statx, NOSYS);
    rule(__NR_openat2, NOSYS);
    /* utimensat(fd, NULL, ...) is futimens, on a descriptor; with a path it is refused. */
    emit((struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_utimensat, 0, 6));
    emit((struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])));
    emit((struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 3));
    emit((struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1]) + 4));
    emit((struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 1));
    emit((struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    emit((struct sock_filter) BPF_STMT(BPF_RET | BPF_K, REFUSE));
    /* Refused: other paths. */
    static const unsigned refused[] = {
        __NR_chdir, __NR_fchdir, __NR_truncate, __NR_linkat, __NR_symlinkat, __NR_mknodat, __NR_fchmodat,
        __NR_fchownat, __NR_inotify_add_watch, __NR_fanotify_mark, __NR_name_to_handle_at,
        __NR_open_by_handle_at, __NR_setxattr, __NR_lsetxattr, __NR_getxattr, __NR_lgetxattr,
        __NR_listxattr, __NR_llistxattr, __NR_removexattr, __NR_lremovexattr,
#ifdef __NR_open
        __NR_link, __NR_symlink, __NR_mknod, __NR_chmod, __NR_chown, __NR_lchown, __NR_utime, __NR_utimes,
        __NR_futimesat, __NR_uselib,
#endif
        /* Reaching past the process. */
        __NR_execve, __NR_execveat, __NR_socket, __NR_bind, __NR_connect, __NR_ptrace,
        __NR_process_vm_readv, __NR_process_vm_writev, __NR_pidfd_getfd, __NR_process_madvise, __NR_bpf,
        __NR_perf_event_open, __NR_io_uring_setup, __NR_io_uring_enter, __NR_io_uring_register,
    };
    for (size_t i = 0; i < sizeof(refused) / sizeof(refused[0]); i++) rule(refused[i], REFUSE);
    emit((struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    return filter_length <= FILTER_MAX ? 0 : -E2BIG;
}
#endif

int sbx_lockdown(int broker_fd) {
#ifndef SBX_ARCH
    (void) broker_fd;
    return -ENOSYS;
#else
    if (broker >= 0) return -EALREADY;
    int built = build_filter();
    if (built != 0) return built;
    int directory = open(".", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) return -errno;
    broker = broker_fd;
    start_dir = directory;

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = on_sigsys;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    int error = 0;
    if (sigaction(SIGSYS, &action, &previous) != 0) {
        error = -errno;
    } else if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        error = -errno;
    } else {
        struct sock_fprog program = {.len = filter_length, .filter = filter};
        long synced = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program);
        if (synced == 0) return 0;
        /* A positive result names a thread that could not be synchronised: a lockdown that misses threads is none. */
        error = synced > 0 ? -EBUSY : -errno;
    }
    sigaction(SIGSYS, &previous, NULL);
    close(directory);
    broker = -1;
    start_dir = -1;
    return error;
#endif
}
