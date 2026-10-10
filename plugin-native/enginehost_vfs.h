/*
 * Enginehost's isolated file layer, as a native engine sees it
 * (docs/engine-sandbox.md "Files by path: one host VFS for every native
 * engine").
 *
 * An isolated runtime holds no storage permission, so a game's files can
 * only be reached through the host's file broker. Rather than give every
 * engine its own broker seam, an engine library is linked with
 * enginehost_vfs_forward.c and the -Wl,--wrap flags enginehost_vfs.cmake
 * adds: every libc file call the library makes (open, fopen, stat,
 * opendir, ...) goes to __wrap_<name> in the forwarder, which hands it to
 * the table below when Enginehost has bound one, and to libc unchanged
 * when it has not (an in-process launch, or any host older than this).
 *
 * The table is Enginehost's: the host's own libenginehost_sandbox.so
 * implements it (isolated_vfs.c), and the isolated runtime binds it into
 * each plugin library it loads by calling enginehost_vfs_bind. Paths under
 * the game folder and the save folder (the same absolute paths an
 * in-process launch would use) are served by the broker; every other path
 * goes to libc.
 *
 * This header is copied verbatim into each plugin repository beside the
 * forwarder; the host builds against the same copy. Fields are only ever
 * appended, and `size` says how many a host filled in.
 */
#ifndef ENGINEHOST_VFS_H
#define ENGINEHOST_VFS_H

#include <dirent.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ENGINEHOST_VFS_VERSION 1

struct enginehost_vfs_table {
    /* sizeof(struct enginehost_vfs_table) as the host built it. */
    size_t size;
    int version;

    int (*open)(const char *path, int flags, mode_t mode);
    int (*openat)(int dirfd, const char *path, int flags, mode_t mode);
    int (*close)(int fd);
    FILE *(*fopen)(const char *path, const char *mode);
    int (*fclose)(FILE *stream);
    int (*stat)(const char *path, struct stat *out);
    int (*lstat)(const char *path, struct stat *out);
    int (*fstatat)(int dirfd, const char *path, struct stat *out, int flags);
    int (*access)(const char *path, int mode);
    int (*faccessat)(int dirfd, const char *path, int mode, int flags);
    DIR *(*opendir)(const char *path);
    struct dirent *(*readdir)(DIR *dir);
    int (*closedir)(DIR *dir);
    void (*rewinddir)(DIR *dir);
    int (*dirfd)(DIR *dir);
    int (*mkdir)(const char *path, mode_t mode);
    int (*rmdir)(const char *path);
    int (*unlink)(const char *path);
    int (*unlinkat)(int dirfd, const char *path, int flags);
    int (*remove)(const char *path);
    int (*rename)(const char *from, const char *to);
    int (*chdir)(const char *path);
    char *(*getcwd)(char *buffer, size_t size);
    char *(*realpath)(const char *path, char *resolved);
    int (*scandir)(const char *path, struct dirent ***names,
                   int (*filter)(const struct dirent *),
                   int (*compare)(const struct dirent **, const struct dirent **));
    /*
     * The engine ending itself with exit(). In the isolated runtime that
     * tells the host the game is over and ends only the calling thread, so
     * the host closes the game as it does any game that ended, instead of
     * finding its runtime dead. Returns when it cannot (then exit proceeds).
     */
    void (*exit)(int status);
};

/*
 * Exported by every library linked with enginehost_vfs_forward.c. The
 * isolated runtime calls it once, before any engine code runs.
 */
void enginehost_vfs_bind(const struct enginehost_vfs_table *table);

#ifdef __cplusplus
}
#endif

#endif
