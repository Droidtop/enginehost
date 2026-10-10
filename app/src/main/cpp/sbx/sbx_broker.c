/*
 * The broker half of sbx (see sbx.h): answers a confined process's path
 * calls from a policy of path rules.
 *
 * Each request is checked twice. First the path as asked, made absolute
 * (against the directory descriptor that came with it) and normalised
 * lexically, so a refusal never says whether something exists. Then the
 * path as the kernel would resolve it: realpath() of the whole path, or of
 * its parent for calls that act on a link itself (lstat, readlink, unlink,
 * rename, mkdir, a create). An open is checked a third time after it
 * succeeds, on what the new descriptor actually names (/proc/self/fd), so
 * a symlink swapped in between the check and the open cannot lead out.
 *
 * /proc/self and /proc/thread-self are rewritten to the sender's pid, which
 * comes from SCM_CREDENTIALS (the kernel's word, not the request's).
 */
#define _GNU_SOURCE
#include "sbx.h"
#include "sbx_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef O_PATH
#define O_PATH 010000000
#endif
#ifndef __NR_renameat2
#define __NR_renameat2 316
#endif

#define TRAVERSE 0x100u   /* internal: an ancestor of a rule, stat and access only */

struct rule {
    char *path;
    size_t length;
    unsigned mode;
};

struct sbx_policy {
    struct rule *rules;
    size_t count;
};

static void default_log(const char *op, const char *asked, const char *resolved, int error) {
    fprintf(stderr, "sbx: refused %s %s (%s): %s\n", op, asked, resolved, strerror(error));
}

static sbx_log_fn log_refusal = default_log;

void sbx_set_log(sbx_log_fn log) { log_refusal = log ? log : default_log; }

struct sbx_policy *sbx_policy_new(void) { return calloc(1, sizeof(struct sbx_policy)); }

void sbx_policy_free(struct sbx_policy *policy) {
    if (!policy) return;
    for (size_t i = 0; i < policy->count; i++) free(policy->rules[i].path);
    free(policy->rules);
    free(policy);
}

/* Lexically absolute and normalised: no "", ".", ".." components, no trailing slash. Fails on overflow. */
static int normalise(const char *base, const char *path, size_t path_len, char *out) {
    char joined[SBX_PATH_MAX * 2 + 2];
    size_t used = 0;
    if (path_len == 0 || path[0] != '/') {
        size_t base_len = strlen(base);
        if (base_len + 1 >= sizeof(joined)) return -ENAMETOOLONG;
        memcpy(joined, base, base_len);
        used = base_len;
        joined[used++] = '/';
    }
    if (used + path_len >= sizeof(joined)) return -ENAMETOOLONG;
    memcpy(joined + used, path, path_len);
    used += path_len;
    joined[used] = '\0';

    size_t length = 0;
    out[0] = '\0';
    char *cursor = joined;
    while (*cursor) {
        while (*cursor == '/') cursor++;
        if (!*cursor) break;
        char *end = cursor;
        while (*end && *end != '/') end++;
        size_t part = (size_t) (end - cursor);
        if (part == 1 && cursor[0] == '.') {
            /* nothing */
        } else if (part == 2 && cursor[0] == '.' && cursor[1] == '.') {
            while (length > 0 && out[length - 1] != '/') length--;
            if (length > 0) length--;
            out[length] = '\0';
        } else {
            if (length + 1 + part >= SBX_PATH_MAX) return -ENAMETOOLONG;
            out[length++] = '/';
            memcpy(out + length, cursor, part);
            length += part;
            out[length] = '\0';
        }
        cursor = end;
    }
    if (length == 0) strcpy(out, "/");
    return 0;
}

int sbx_policy_add(struct sbx_policy *policy, const char *path, unsigned mode) {
    if (!policy || !path || path[0] != '/') return -EINVAL;
    char canonical[PATH_MAX];
    char normal[SBX_PATH_MAX];
    const char *chosen = realpath(path, canonical);
    if (!chosen) {
        if (normalise("/", path, strlen(path), normal) != 0) return -ENAMETOOLONG;
        chosen = normal;
    }
    struct rule *grown = realloc(policy->rules, (policy->count + 1) * sizeof(struct rule));
    if (!grown) return -ENOMEM;
    policy->rules = grown;
    char *copy = strdup(chosen);
    if (!copy) return -ENOMEM;
    policy->rules[policy->count].path = copy;
    policy->rules[policy->count].length = strlen(copy);
    policy->rules[policy->count].mode = mode & (SBX_READ | SBX_WRITE);
    policy->count++;
    return 0;
}

/* path is under (or is) prefix, on a component boundary. */
static int within(const char *path, const char *prefix, size_t prefix_len) {
    if (prefix_len == 1 && prefix[0] == '/') return 1;
    return strncmp(path, prefix, prefix_len) == 0 && (path[prefix_len] == '\0' || path[prefix_len] == '/');
}

static int all_digits(const char *s, size_t n) {
    if (n == 0) return 0;
    for (size_t i = 0; i < n; i++) if (s[i] < '0' || s[i] > '9') return 0;
    return 1;
}

/* What the policy allows on a normalised absolute path for the process pid. */
static unsigned allowed(const struct sbx_policy *policy, const char *path, pid_t pid) {
    if (strncmp(path, "/proc/", 6) == 0) {
        const char *entry = path + 6;
        size_t n = strcspn(entry, "/");
        if (all_digits(entry, n)) {
            char own[24];
            int own_len = snprintf(own, sizeof(own), "%d", (int) pid);
            return (n == (size_t) own_len && strncmp(entry, own, n) == 0) ? (SBX_READ | SBX_WRITE) : SBX_DENY;
        }
    }
    size_t best = 0;
    int found = 0;
    unsigned mode = SBX_DENY;
    int ancestor = 0;
    size_t path_len = strlen(path);
    for (size_t i = 0; i < policy->count; i++) {
        const struct rule *r = &policy->rules[i];
        if (within(path, r->path, r->length)) {
            if (!found || r->length >= best) {
                best = r->length;
                mode = r->mode;
                found = 1;
            }
        } else if (within(r->path, path, path_len)) {
            ancestor = 1;
        }
    }
    if (found) return mode;
    return ancestor ? TRAVERSE : SBX_DENY;
}

/* Rewrites /proc/self and /proc/thread-self for the sender. */
static int rewrite_proc(char *path, pid_t pid, pid_t tid) {
    char rest[SBX_PATH_MAX];
    char head[64];
    const char *tail;
    if (strncmp(path, "/proc/self", 10) == 0 && (path[10] == '\0' || path[10] == '/')) {
        tail = path + 10;
        snprintf(head, sizeof(head), "/proc/%d", (int) pid);
    } else if (strncmp(path, "/proc/thread-self", 17) == 0 && (path[17] == '\0' || path[17] == '/')) {
        tail = path + 17;
        snprintf(head, sizeof(head), "/proc/%d/task/%d", (int) pid, (int) tid);
    } else {
        return 0;
    }
    if (strlen(head) + strlen(tail) >= SBX_PATH_MAX) return -ENAMETOOLONG;
    snprintf(rest, sizeof(rest), "%s%s", head, tail);
    strcpy(path, rest);
    return 0;
}

/*
 * The path as the kernel would resolve it: the parent through realpath(),
 * then the final name, followed only when follow_last and only when it is
 * a symlink. readlink() answers EINVAL for anything else without the stat
 * that realpath() would make, which SELinux can refuse where access() and
 * open() are allowed (libhidl's access() of /system/bin/hwservicemanager).
 */
static int resolve(const char *path, int follow_last, char *out) {
    if (strcmp(path, "/") == 0) {
        strcpy(out, "/");
        return 0;
    }
    const char *slash = strrchr(path, '/');
    char parent[SBX_PATH_MAX];
    size_t parent_len = (size_t) (slash - path);
    if (parent_len == 0) {
        strcpy(parent, "/");
    } else {
        memcpy(parent, path, parent_len);
        parent[parent_len] = '\0';
    }
    char real_parent[PATH_MAX];
    if (!realpath(parent, real_parent)) return -errno;
    const char *name = slash + 1;
    if (strlen(real_parent) + 1 + strlen(name) >= SBX_PATH_MAX) return -ENAMETOOLONG;
    if (strcmp(real_parent, "/") == 0) snprintf(out, SBX_PATH_MAX, "/%s", name);
    else snprintf(out, SBX_PATH_MAX, "%s/%s", real_parent, name);
    if (!follow_last) return 0;
    char target[SBX_PATH_MAX];
    if (readlink(out, target, sizeof(target)) < 0) return 0;
    char linked[PATH_MAX];
    if (!realpath(out, linked)) return -errno;
    snprintf(out, SBX_PATH_MAX, "%s", linked);
    return 0;
}

static int fd_path(int fd, char *out) {
    char link[64];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, out, SBX_PATH_MAX - 1);
    if (n < 0) return -errno;
    out[n] = '\0';
    return 0;
}

static const char *op_name(uint32_t op) {
    switch (op) {
    case SBX_OPEN: return "open";
    case SBX_STAT: return "stat";
    case SBX_ACCESS: return "access";
    case SBX_READLINK: return "readlink";
    case SBX_MKDIR: return "mkdir";
    case SBX_UNLINK: return "unlink";
    case SBX_RENAME: return "rename";
    case SBX_STATFS: return "statfs";
    default: return "unknown";
    }
}

static int open_wants_write(int flags) {
    return (flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC | O_APPEND)) != 0;
}

static int permits(unsigned mode, unsigned need) {
    return need == TRAVERSE ? mode != SBX_DENY : (mode != TRAVERSE && (mode & need) == need);
}

/*
 * Checks one path and fills real; need is SBX_READ, SBX_WRITE, or TRAVERSE
 * for stat-like calls. The path as asked must be allowed, or else the path
 * it resolves to (an alias such as /sdcard for /storage/emulated/0); a path
 * allowed neither way is refused whether or not it exists. The resolved
 * path must always be allowed.
 */
static int check(const struct sbx_policy *policy, uint32_t op, const char *asked, const char *base, size_t len,
                 const char *raw, pid_t pid, pid_t tid, unsigned need, int follow_last, char *real) {
    char normal[SBX_PATH_MAX];
    int error = normalise(base, raw, len, normal);
    if (error == 0) error = rewrite_proc(normal, pid, tid);
    if (error != 0) return error;
    int as_asked = permits(allowed(policy, normal, pid), need);
    error = resolve(normal, follow_last, real);
    if (error != 0) {
        if (as_asked) return error;
        log_refusal(op_name(op), asked, normal, EACCES);
        return -EACCES;
    }
    if (!permits(allowed(policy, real, pid), need)) {
        log_refusal(op_name(op), asked, real, EACCES);
        return -EACCES;
    }
    return 0;
}

struct session {
    struct sbx_policy *policy;
    int fd;
};

static void reply(int to, int64_t result, const void *data, uint32_t data_len, int fd) {
    struct sbx_reply header = {.result = result, .data_len = data_len};
    struct iovec parts[2] = {{&header, sizeof(header)}, {(void *) data, data_len}};
    union {
        char buffer[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } control;
    memset(&control, 0, sizeof(control));
    struct msghdr message;
    memset(&message, 0, sizeof(message));
    message.msg_iov = parts;
    message.msg_iovlen = data_len ? 2 : 1;
    if (fd >= 0) {
        message.msg_control = control.buffer;
        message.msg_controllen = sizeof(control.buffer);
        struct cmsghdr *c = CMSG_FIRSTHDR(&message);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof(int));
    }
    sendmsg(to, &message, MSG_NOSIGNAL);
}

static void handle(const struct sbx_policy *policy, const struct sbx_request *request, const char *path,
                   const char *path2, const char *base, const char *base2, pid_t pid, int answer) {
    char asked[SBX_PATH_MAX];
    snprintf(asked, sizeof(asked), "%.*s", (int) request->path_len, path);
    char real[SBX_PATH_MAX];
    int error;
    switch (request->op) {
    case SBX_OPEN: {
        int flags = request->flags;
        int writing = open_wants_write(flags);
        /* O_PATH reads nothing: it is a handle to look things up from, like a traversal. */
        unsigned need = writing ? SBX_WRITE : (flags & O_PATH) ? TRAVERSE : SBX_READ;
        /* O_NOFOLLOW means the link itself, so only its parent is resolved and the flag kept. */
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, need,
                      !(flags & O_NOFOLLOW), real);
        if (error != 0) break;
        /* Opened by the resolved path; no controlling terminal; never inherited by the broker's children. */
        int fd = open(real, flags | O_NOCTTY | O_CLOEXEC, (mode_t) request->mode);
        if (fd < 0) { error = -errno; break; }
        char opened[SBX_PATH_MAX];
        if (fd_path(fd, opened) == 0 && opened[0] == '/') {
            unsigned mode = allowed(policy, opened, pid);
            if (!permits(mode, need)) {
                log_refusal("open", asked, opened, EACCES);
                close(fd);
                error = -EACCES;
                break;
            }
        }
        reply(answer, 0, NULL, 0, fd);
        close(fd);
        return;
    }
    case SBX_STAT: {
        int follow = !(request->flags & AT_SYMLINK_NOFOLLOW);
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, TRAVERSE,
                      follow, real);
        if (error != 0) break;
        struct stat st;
        if ((follow ? stat(real, &st) : lstat(real, &st)) != 0) { error = -errno; break; }
        reply(answer, 0, &st, sizeof(st), -1);
        return;
    }
    case SBX_ACCESS: {
        unsigned need = (request->mode & W_OK) ? SBX_WRITE : (request->mode & R_OK) ? SBX_READ : TRAVERSE;
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, need, 1, real);
        if (error != 0) break;
        error = access(real, (int) request->mode) == 0 ? 0 : -errno;
        break;
    }
    case SBX_READLINK: {
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, TRAVERSE, 0, real);
        if (error != 0) break;
        char target[SBX_PATH_MAX];
        ssize_t n = readlink(real, target, sizeof(target));
        if (n < 0) { error = -errno; break; }
        if ((size_t) n > request->mode) n = (ssize_t) request->mode;
        reply(answer, n, target, (uint32_t) n, -1);
        return;
    }
    case SBX_MKDIR:
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, SBX_WRITE, 0, real);
        if (error == 0) error = mkdir(real, (mode_t) request->mode) == 0 ? 0 : -errno;
        break;
    case SBX_UNLINK:
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, SBX_WRITE, 0, real);
        if (error == 0) error = ((request->flags & AT_REMOVEDIR) ? rmdir(real) : unlink(real)) == 0 ? 0 : -errno;
        break;
    case SBX_RENAME: {
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, SBX_WRITE, 0, real);
        if (error != 0) break;
        char asked2[SBX_PATH_MAX];
        snprintf(asked2, sizeof(asked2), "%.*s", (int) request->path2_len, path2);
        char real2[SBX_PATH_MAX];
        error = check(policy, request->op, asked2, base2, request->path2_len, path2, pid, request->tid,
                      SBX_WRITE, 0, real2);
        if (error != 0) break;
        if (request->flags != 0) {
            error = syscall(__NR_renameat2, AT_FDCWD, real, AT_FDCWD, real2, (unsigned) request->flags) == 0
                        ? 0 : -errno;
        } else {
            error = rename(real, real2) == 0 ? 0 : -errno;
        }
        break;
    }
    case SBX_STATFS: {
        error = check(policy, request->op, asked, base, request->path_len, path, pid, request->tid, TRAVERSE, 1, real);
        if (error != 0) break;
        struct statfs st;
        if (statfs(real, &st) != 0) { error = -errno; break; }
        reply(answer, 0, &st, sizeof(st), -1);
        return;
    }
    default:
        error = -ENOSYS;
    }
    reply(answer, error, NULL, 0, -1);
}

static void serve_one(const struct sbx_policy *policy, const struct sbx_request *request, const char *body,
                      size_t body_len, int *fds, int fd_count, pid_t pid) {
    if (fd_count < 1) return;
    int answer = fds[0];
    if ((size_t) request->path_len + request->path2_len != body_len || request->path_len == 0 ||
        request->path_len >= SBX_PATH_MAX || request->path2_len >= SBX_PATH_MAX ||
        fd_count != 1 + request->dirfd_sent + request->dirfd2_sent) {
        reply(answer, -EINVAL, NULL, 0, -1);
        return;
    }
    /* /proc/<pid>/task/<tid> must be the sender's own thread. */
    char task[64];
    snprintf(task, sizeof(task), "/proc/%d/task/%d", (int) pid, (int) request->tid);
    struct stat ignored;
    if (request->tid <= 0 || stat(task, &ignored) != 0) {
        reply(answer, -EINVAL, NULL, 0, -1);
        return;
    }
    char base[SBX_PATH_MAX] = "/";
    char base2[SBX_PATH_MAX] = "/";
    int next = 1;
    if (request->dirfd_sent) {
        if (fd_path(fds[next++], base) != 0 || base[0] != '/') { reply(answer, -ENOTDIR, NULL, 0, -1); return; }
    }
    if (request->dirfd2_sent) {
        if (fd_path(fds[next++], base2) != 0 || base2[0] != '/') { reply(answer, -ENOTDIR, NULL, 0, -1); return; }
    }
    handle(policy, request, body, body + request->path_len, base, base2, pid, answer);
}

void sbx_broker_serve(struct sbx_policy *policy, int fd) {
    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_PASSCRED, &on, sizeof(on));
    struct {
        struct sbx_request request;
        char body[SBX_PATH_MAX * 2];
    } incoming;
    for (;;) {
        union {
            char buffer[CMSG_SPACE(sizeof(int) * 3) + CMSG_SPACE(sizeof(struct ucred))];
            struct cmsghdr align;
        } control;
        struct iovec part = {&incoming, sizeof(incoming)};
        struct msghdr message;
        memset(&message, 0, sizeof(message));
        message.msg_iov = &part;
        message.msg_iovlen = 1;
        message.msg_control = control.buffer;
        message.msg_controllen = sizeof(control.buffer);
        ssize_t n = recvmsg(fd, &message, MSG_CMSG_CLOEXEC);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        int fds[3];
        int fd_count = 0;
        pid_t pid = 0;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&message); c; c = CMSG_NXTHDR(&message, c)) {
            if (c->cmsg_level != SOL_SOCKET) continue;
            if (c->cmsg_type == SCM_RIGHTS) {
                size_t count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                for (size_t i = 0; i < count; i++) {
                    int received;
                    memcpy(&received, CMSG_DATA(c) + i * sizeof(int), sizeof(int));
                    if (fd_count < 3) fds[fd_count++] = received;
                    else close(received);
                }
            } else if (c->cmsg_type == SCM_CREDENTIALS) {
                struct ucred credentials;
                memcpy(&credentials, CMSG_DATA(c), sizeof(credentials));
                pid = credentials.pid;
            }
        }
        if (pid > 0 && !(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) && (size_t) n >= sizeof(struct sbx_request)) {
            serve_one(policy, &incoming.request, incoming.body, (size_t) n - sizeof(struct sbx_request), fds,
                      fd_count, pid);
        }
        for (int i = 0; i < fd_count; i++) close(fds[i]);
    }
    close(fd);
    sbx_policy_free(policy);
}

static void *serve_thread(void *argument) {
    struct session *session = argument;
    sbx_broker_serve(session->policy, session->fd);
    free(session);
    return NULL;
}

int sbx_broker_start(struct sbx_policy *policy, int fd) {
    /* Before the confined side can send anything, or its first request would come without a pid. */
    int on = 1;
    if (setsockopt(fd, SOL_SOCKET, SO_PASSCRED, &on, sizeof(on)) != 0) return -errno;
    struct session *session = malloc(sizeof(*session));
    if (!session) return -ENOMEM;
    session->policy = policy;
    session->fd = fd;
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    int error = pthread_create(&thread, &attributes, serve_thread, session);
    pthread_attr_destroy(&attributes);
    if (error != 0) {
        free(session);
        return -error;
    }
    return 0;
}
