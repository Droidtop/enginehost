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
#include <android/log.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include "sbx.h"
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

/*
 * Logs the signals whose installed handler blocks SIGSYS while it runs,
 * read with the raw call (ART interposes sigaction()). A trapped call made
 * inside such a handler used to kill the process by signal 31 with no
 * tombstone (Droidtop/tracker#550); the library now takes SIGSYS out of
 * these masks at lockdown, and this says which handlers it had to change.
 */
static void log_handlers_masking_sigsys(void) {
    struct {
        void *handler;
        unsigned long flags;
        void *restorer;
        unsigned long long mask;
    } action;
    char names[512] = "";
    size_t used = 0;
    for (int signal_number = 1; signal_number <= 64; signal_number++) {
        if (signal_number == SIGKILL || signal_number == SIGSTOP || signal_number == SIGSYS) continue;
        if (syscall(__NR_rt_sigaction, signal_number, NULL, &action, 8) != 0) continue;
        if (action.handler == SIG_DFL || action.handler == SIG_IGN) continue;
        if (!(action.mask & (1ULL << (SIGSYS - 1)))) continue;
        int n = snprintf(names + used, sizeof(names) - used, "%s%d", used ? ", " : "", signal_number);
        if (n > 0 && (size_t) n < sizeof(names) - used) used += (size_t) n;
    }
    if (used) {
        __android_log_print(ANDROID_LOG_WARN, "EnginehostSandbox",
                            "Handlers that block SIGSYS while they run (signal numbers): %s; the lockdown opens it in each",
                            names);
    }
}

/* Process lockdown (the sandbox library's sbx.h): the :runtime side. Takes ownership of fd. */
JNIEXPORT jint JNICALL
Java_dev_enginehost_RuntimeSandbox_lockdown0(JNIEnv *env, jclass clazz, jint fd, jobjectArray own_opens, jboolean learn,
                                             jboolean audit) {
    (void) clazz;
    jsize count = (*env)->GetArrayLength(env, own_opens);
    if (count > 8) count = 8;
    char paths[8][128];
    const char *list[8];
    jsize used = 0;
    for (jsize i = 0; i < count; i++) {
        jstring path = (jstring) (*env)->GetObjectArrayElement(env, own_opens, i);
        const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
        if (chars && strlen(chars) < sizeof(paths[0])) {
            strcpy(paths[used], chars);
            list[used] = paths[used];
            used++;
        }
        if (chars) (*env)->ReleaseStringUTFChars(env, path, chars);
        (*env)->DeleteLocalRef(env, path);
    }
    /* No socket of any kind, as before the library: the process keeps the ones it already has. */
    struct sbx_lockdown_options options = {.own_opens = list, .own_open_count = (unsigned) used};
    /* Learning (debug.enginehost.learn): nothing refused, every would-be refusal recorded by the broker. */
    if (learn) options.flags |= SBX_LOCK_LEARN;
    /* debug.enginehost.audit: the kernel logs every trap (audit type 1326), the record of a trap no handler saw. */
    if (audit) options.flags |= SBX_LOCK_AUDIT;
    log_handlers_masking_sigsys();
    int result = sbx_lockdown(fd, &options);
    if (result == -EINVAL && audit) {
        /* Before Linux 4.14 there is no audit flag; a debug switch must not stop the game. */
        __android_log_print(ANDROID_LOG_WARN, "EnginehostSandbox", "This kernel cannot audit seccomp; locking down without it");
        options.flags &= ~SBX_LOCK_AUDIT;
        result = sbx_lockdown(fd, &options);
    }
    if (result != 0) close(fd);
    return result;
}

/* The trace ring (sbx_trace) of this process, in the file at path; before lockdown0. 0 or a negative errno. */
JNIEXPORT jint JNICALL
Java_dev_enginehost_RuntimeSandbox_trace0(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (!chars) return -ENOMEM;
    int fd = open(chars, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    if (fd < 0) return -errno;
    int result = sbx_trace(fd, 256);
    close(fd);
    return result;
}

/*
 * A trace ring as sentences, newest last: the process, whether its filter
 * was on, the last calls the handler took, and first the one it died
 * handling when an entry was entered and never left. NULL when the file
 * holds no ring.
 */
JNIEXPORT jstring JNICALL
Java_dev_enginehost_RuntimeSandbox_traceSummary0(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (!chars) return NULL;
    int fd = open(chars, O_RDONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    if (fd < 0) return NULL;
    struct sbx_trace_header header;
    if (pread(fd, &header, sizeof(header), 0) != (ssize_t) sizeof(header) || header.magic != SBX_TRACE_MAGIC ||
        header.version != SBX_TRACE_VERSION || header.slots == 0 || header.slots > 4096) {
        close(fd);
        return NULL;
    }
    enum { SHOWN = 12 };
    struct sbx_trace_entry shown[SHOWN];
    unsigned count = 0;
    uint32_t first = header.next > SHOWN ? header.next - SHOWN : 0;
    for (uint32_t n = first; n < header.next; n++) {
        struct sbx_trace_entry entry;
        off_t at = (off_t) (sizeof(header) + (n % header.slots) * sizeof(entry));
        if (pread(fd, &entry, sizeof(entry), at) != (ssize_t) sizeof(entry) || entry.seq != n + 1) continue;
        shown[count++] = entry;
    }
    /* Older entries still marked entered: a call whose handling never ended, even far back. */
    struct sbx_trace_entry stuck = {0};
    int found_stuck = 0;
    for (uint32_t k = 0; k < header.slots && k < header.next; k++) {
        struct sbx_trace_entry entry;
        if (pread(fd, &entry, sizeof(entry), (off_t) (sizeof(header) + k * sizeof(entry))) != (ssize_t) sizeof(entry)) break;
        if (entry.seq != 0 && (entry.state & 0xff) == SBX_TRACE_ENTERED && (!found_stuck || entry.seq > stuck.seq)) {
            stuck = entry;
            found_stuck = 1;
        }
    }
    close(fd);
    char text[2048];
    size_t used = 0;
#define ADD(...)                                                                     \
    do {                                                                             \
        int n_ = snprintf(text + used, sizeof(text) - used, __VA_ARGS__);            \
        if (n_ > 0) used = (size_t) n_ < sizeof(text) - used ? used + (size_t) n_ : sizeof(text) - 1; \
    } while (0)
    ADD("process %d, %s, %u trapped calls", header.pid, header.locked ? "locked down" : "not yet locked down",
        header.next);
    if (found_stuck) {
        const char *name = sbx_syscall_name(stuck.nr);
        ADD("; DIED HANDLING %s (%d) on thread %d, first argument %#llx, from %#llx", name ? name : "call", stuck.nr,
            stuck.tid, (unsigned long long) stuck.arg0, (unsigned long long) stuck.ip);
    }
    ADD("; last calls:");
    for (unsigned k = 0; k < count; k++) {
        const char *name = sbx_syscall_name(shown[k].nr);
        ADD(" %s(%d) thread %d %s;", name ? name : "call", shown[k].nr, shown[k].tid,
            (shown[k].state & 0xff) == SBX_TRACE_LEFT ? "done" : "NOT DONE");
    }
#undef ADD
    return (*env)->NewStringUTF(env, text);
}

/* Logs every refusal (EACCES, EPERM) under EnginehostSandbox; a call that fails on its own is no refusal. */
static void log_refusal(void *context, const struct sbx_access *access, int result) {
    (void) context;
    if (result != -EACCES && result != -EPERM) return;
    __android_log_print(ANDROID_LOG_WARN, "EnginehostSandbox", "refused %s %s (%s): %s", access->op, access->asked,
                        access->resolved[0] ? access->resolved : "unresolved", strerror(-result));
}

/*
 * A learning session's recorder (SandboxLearner.record), called from the
 * broker's thread. Its global reference lives as long as the process: a
 * learning run is a debugging run, and one small reference per run is
 * kept rather than tracking when the broker's thread is done with it.
 */
struct learner {
    JavaVM *vm;
    jobject recorder;
    jmethodID record;
};

static jbyteArray bytes_of(JNIEnv *env, const char *text) {
    jsize length = (jsize) strlen(text);
    jbyteArray array = (*env)->NewByteArray(env, length);
    if (array) (*env)->SetByteArrayRegion(env, array, 0, length, (const jbyte *) text);
    return array;
}

/* Hands one record to the recorder; paths go as bytes, since they need not be valid modified UTF-8. */
static void learn_record(struct learner *learner, const struct sbx_access *access, int verdict) {
    __android_log_print(ANDROID_LOG_WARN, "EnginehostSandbox", "LEARNED %s %s (%s): a lockdown answers %s", access->op,
                        access->asked, access->resolved[0] ? access->resolved : "unresolved", strerror(-verdict));
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*learner->vm)->GetEnv(learner->vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*learner->vm)->AttachCurrentThread(learner->vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    jbyteArray op = bytes_of(env, access->op);
    jbyteArray asked = bytes_of(env, access->asked);
    jbyteArray resolved = bytes_of(env, access->resolved);
    if (op && asked && resolved) {
        (*env)->CallVoidMethod(env, learner->recorder, learner->record, op, asked, resolved, (jint) access->need,
                               (jint) access->flags, (jint) verdict);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (op) (*env)->DeleteLocalRef(env, op);
    if (asked) (*env)->DeleteLocalRef(env, asked);
    if (resolved) (*env)->DeleteLocalRef(env, resolved);
    /* The broker's thread ends with its session; a thread that ends attached aborts the process. */
    if (attached) (*learner->vm)->DetachCurrentThread(learner->vm);
}

/* Learning: a path the rules refuse is recorded and allowed. */
static int learn_decide(void *context, const struct sbx_access *access) {
    if (access->rule_verdict < 0) learn_record(context, access, access->rule_verdict);
    return 0;
}

/* Learning: a call the filter would refuse arrives here as the access "syscall"; refusals are still logged. */
static void learn_audit(void *context, const struct sbx_access *access, int result) {
    if (strcmp(access->op, "syscall") == 0) learn_record(context, access, access->rule_verdict);
    else log_refusal(NULL, access, result);
}

/*
 * Process lockdown: the broker side, in the main process. paths[i] gets
 * modes[i] (SBX_READ, SBX_WRITE). Takes ownership of fd; serves it on its
 * own thread until the runtime process closes it or dies. With a recorder
 * (a learning run), nothing is refused and every would-be refusal goes to
 * recorder.record.
 */
JNIEXPORT jint JNICALL
Java_dev_enginehost_RuntimeSandbox_serve0(JNIEnv *env, jclass clazz, jint fd, jobjectArray paths, jintArray modes,
                                          jobject recorder) {
    (void) clazz;
    struct sbx_policy *policy = sbx_policy_new();
    if (!policy) { close(fd); return -ENOMEM; }
    if (recorder) {
        struct learner *learner = calloc(1, sizeof(*learner));
        jclass type = (*env)->GetObjectClass(env, recorder);
        if (learner) learner->record = (*env)->GetMethodID(env, type, "record", "([B[B[BIII)V");
        if (!learner || !learner->record || (*env)->GetJavaVM(env, &learner->vm) != JNI_OK) {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            free(learner);
            sbx_policy_free(policy);
            close(fd);
            return -EINVAL;
        }
        learner->recorder = (*env)->NewGlobalRef(env, recorder);
        sbx_policy_set_decider(policy, learn_decide, learner);
        sbx_policy_set_audit(policy, learn_audit, learner);
    } else {
        sbx_policy_set_audit(policy, log_refusal, NULL);
    }
    jsize count = (*env)->GetArrayLength(env, paths);
    jint *mode = (*env)->GetIntArrayElements(env, modes, NULL);
    for (jsize i = 0; i < count && mode; i++) {
        jstring path = (jstring) (*env)->GetObjectArrayElement(env, paths, i);
        const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
        if (chars) {
            sbx_policy_add(policy, chars, (unsigned) mode[i]);
            (*env)->ReleaseStringUTFChars(env, path, chars);
        }
        (*env)->DeleteLocalRef(env, path);
    }
    if (mode) (*env)->ReleaseIntArrayElements(env, modes, mode, JNI_ABORT);
    int result = sbx_broker_start(policy, fd);
    if (result != 0) {
        sbx_policy_free(policy);
        close(fd);
    }
    return result;
}
#endif
