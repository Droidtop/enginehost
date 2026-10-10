/*
 * The host half of the isolated file layer (plugin-native/enginehost_vfs.h,
 * docs/engine-sandbox.md "Files by path: one host VFS for every native
 * engine"). isolated_vfs.c is plain POSIX C with no JNI in it, so the
 * whole path logic runs in CI against a fake broker
 * (app/src/test/native/isolated_vfs_test.c); isolated_vfs_jni.c supplies
 * the real broker over Binder.
 */
#ifndef ENGINEHOST_ISOLATED_VFS_H
#define ENGINEHOST_ISOLATED_VFS_H

#include <stdint.h>

#include "enginehost_vfs.h"

#define EHVFS_KIND_FILE 1
#define EHVFS_KIND_DIRECTORY 2

struct ehvfs_entry {
    char *name;
    int kind;
    int64_t size;
    int64_t mtime_ms;
};

/*
 * One root's broker. Paths are relative to the root, '/'-separated, never
 * empty except for the root itself, never containing "." or "..". Every
 * call answers 0 (or a descriptor) on success and a negative errno on
 * failure.
 */
struct ehvfs_broker {
    void *context;
    /* A read-only descriptor for an existing file. */
    int (*open_read)(void *context, const char *relative);
    /* A descriptor to a fresh, empty file that becomes `relative` on commit_write. */
    int (*open_write)(void *context, const char *relative);
    int (*commit_write)(void *context, const char *relative);
    int (*remove)(void *context, const char *relative);
    int (*make_directory)(void *context, const char *relative);
    int (*rename)(void *context, const char *from, const char *to);
    /* The entries directly inside directory `relative`, malloc'd (names too); the caller frees them. */
    int (*list)(void *context, const char *relative, struct ehvfs_entry **entries, int *count);
};

/*
 * Serves every path at or under `root` (absolute, as the engine is given
 * it) from `broker`. `writable` is false for a folder the engine may only
 * read. At most EHVFS_MAX_MOUNTS mounts; returns 0 or a negative errno.
 */
#define EHVFS_MAX_MOUNTS 4
int ehvfs_mount(const char *root, const struct ehvfs_broker *broker, int writable);

/* The table every bound plugin library calls into. */
const struct enginehost_vfs_table *ehvfs_table(void);

/*
 * Called when engine code calls exit(); the calling thread then ends there
 * (the process goes on until the host ends it). Not on the process's main
 * thread, where exit proceeds as usual.
 */
void ehvfs_set_exit_hook(void (*hook)(int status));

#endif
