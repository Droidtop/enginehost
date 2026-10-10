/*
 * sbx: a process sandbox in two halves, after Chromium's Linux broker
 * (sandbox/linux/syscall_broker) and minijail's seccomp policies.
 *
 * sbx_lockdown() is called by the process to be confined, before it loads
 * code it does not trust. It installs a seccomp filter on every thread
 * under which no system call can name a file: each one that would traps
 * (SIGSYS), and the handler sends it over a Unix socket to the broker.
 * Sockets, program execution, ptrace and the other kernel surfaces listed
 * in sbx_lockdown.c are refused outright. Calls on descriptors the process
 * already holds (read, write, mmap, ioctl, getdents) are untouched, so a
 * GPU driver keeps its device and an engine its window.
 *
 * The broker (sbx_broker.c) runs in another process. It answers each
 * request from a policy of path rules, does the call itself and sends back
 * the result, with the descriptor for an open. The handler is not a trust
 * boundary: code that replaces it only loses its own file access.
 *
 * Nothing here knows about Android or any particular host; the JNI glue
 * and the policy's contents are the host's.
 */
#ifndef SBX_H
#define SBX_H

#ifdef __cplusplus
extern "C" {
#endif

/* Path rule modes. A rule covers its path and everything under it; the longest matching rule decides. */
#define SBX_DENY 0u
#define SBX_READ 1u          /* open for reading, stat, access, readlink, list */
#define SBX_WRITE 2u         /* also open for writing, create, mkdir, unlink, rename */

/*
 * Confines the calling process. broker_fd is a connected SOCK_SEQPACKET
 * Unix socket whose other end a broker serves; the process keeps it.
 *
 * own_opens (NULL-terminated, or NULL) names devices the process must
 * open for itself, because the kernel ties the open file to the process
 * that opened it: a binder device opened by the broker cannot be mapped
 * here (libhidl: "Mmapping /dev/hwbinder failed: Invalid argument"), and
 * some GPU drivers keep per-process state the same way. Each is opened
 * now, read-write, and an open of exactly that path later is answered
 * with a duplicate, without asking the broker. One that cannot be opened
 * now is skipped.
 *
 * Returns 0 when every thread is confined, or a negative errno when
 * nothing was installed.
 */
int sbx_lockdown(int broker_fd, const char *const *own_opens);

struct sbx_policy;

struct sbx_policy *sbx_policy_new(void);
/*
 * Adds a rule for path (absolute; canonicalised when it exists). Every
 * ancestor of a rule can be stat()ed and traversed, but not listed or
 * opened. /proc/self and /proc/thread-self mean the confined process's own
 * entries, which are always read-write; other processes' are always
 * denied. Returns 0 or a negative errno.
 */
int sbx_policy_add(struct sbx_policy *policy, const char *path, unsigned mode);
void sbx_policy_free(struct sbx_policy *policy);

/* Receives each refusal: the operation, the path as asked and as resolved. Defaults to stderr. */
typedef void (*sbx_log_fn)(const char *op, const char *asked, const char *resolved, int error);
void sbx_set_log(sbx_log_fn log);

/*
 * Serves fd, the broker's end of the socket sbx_lockdown() was given,
 * until the other end closes it; then closes fd. Takes the policy.
 * sbx_broker_serve blocks; sbx_broker_start serves on a new detached
 * thread and returns 0 or a negative errno.
 */
void sbx_broker_serve(struct sbx_policy *policy, int fd);
int sbx_broker_start(struct sbx_policy *policy, int fd);

#ifdef __cplusplus
}
#endif

#endif
