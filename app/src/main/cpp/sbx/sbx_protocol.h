/*
 * The wire format between sbx_lockdown.c's SIGSYS handler and
 * sbx_broker.c.
 *
 * Request, one SEQPACKET message: struct sbx_request, then path_len bytes
 * of path, then path2_len bytes of the second path (rename), neither
 * terminated. Descriptors travel with it (SCM_RIGHTS): first the socket to
 * answer on (one end of a socketpair made for this request alone, so
 * threads share nothing), then the directory the path is relative to when
 * dirfd_sent, then the second path's when dirfd2_sent. A relative path
 * without a directory is relative to the directory the process was in
 * when it locked down; the handler sends that directory's descriptor.
 * The broker learns the sender's pid from SCM_CREDENTIALS, not from the
 * request.
 *
 * Reply, one message on the answer socket: struct sbx_reply, then
 * data_len bytes (a struct stat, a struct statfs, or a link target), and
 * for an open that succeeded the descriptor.
 */
#ifndef SBX_PROTOCOL_H
#define SBX_PROTOCOL_H

#include <stdint.h>

#define SBX_PATH_MAX 4096

enum sbx_op {
    SBX_OPEN = 1,      /* flags = open flags, mode = creation mode */
    SBX_STAT = 2,      /* flags = AT_SYMLINK_NOFOLLOW or 0; data = struct stat */
    SBX_ACCESS = 3,    /* mode = R_OK|W_OK|X_OK or F_OK */
    SBX_READLINK = 4,  /* mode = the caller's buffer size; data = the target */
    SBX_MKDIR = 5,     /* mode = creation mode */
    SBX_UNLINK = 6,    /* flags = AT_REMOVEDIR or 0 */
    SBX_RENAME = 7,    /* flags = renameat2 flags; path2 = the new name */
    SBX_STATFS = 8,    /* data = struct statfs */
};

struct sbx_request {
    uint32_t op;
    int32_t flags;
    uint32_t mode;
    uint32_t path_len;
    uint32_t path2_len;
    int32_t tid;        /* for /proc/thread-self; the broker checks it is the sender's */
    uint8_t dirfd_sent;
    uint8_t dirfd2_sent;
    uint8_t reserved[6];
};

struct sbx_reply {
    int64_t result;     /* >= 0 on success (a byte count for READLINK), else -errno */
    uint32_t data_len;
    uint32_t reserved;
};

#endif
