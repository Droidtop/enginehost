/*
 * The plugin side of Enginehost's isolated file layer (enginehost_vfs.h).
 * Linked into an engine library together with the --wrap flags in
 * enginehost_vfs.cmake; every wrapped call goes to Enginehost's table once
 * one is bound, and to libc otherwise. Nothing here decides anything: the
 * table does. Copied verbatim from Enginehost's plugin-native/ directory;
 * change it there.
 *
 * Only 64-bit ABIs ship (arm64-v8a, x86_64), where the *64 variants of
 * these calls share their plain counterparts' structures.
 */
#include "enginehost_vfs.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <unistd.h>

static const struct enginehost_vfs_table *g_vfs;

__attribute__((visibility("default")))
void enginehost_vfs_bind(const struct enginehost_vfs_table *table) {
    if (table != NULL && table->version >= 1 && table->size >= sizeof(struct enginehost_vfs_table)) {
        g_vfs = table;
    }
}

int __real_open(const char *path, int flags, ...);
int __real_open64(const char *path, int flags, ...);
int __real_openat(int dirfd, const char *path, int flags, ...);
int __real_openat64(int dirfd, const char *path, int flags, ...);
int __real___open_2(const char *path, int flags);
int __real___openat_2(int dirfd, const char *path, int flags);
int __real_creat(const char *path, mode_t mode);
int __real_close(int fd);
FILE *__real_fopen(const char *path, const char *mode);
FILE *__real_fopen64(const char *path, const char *mode);
int __real_fclose(FILE *stream);
int __real_stat(const char *path, struct stat *out);
int __real_stat64(const char *path, struct stat *out);
int __real_lstat(const char *path, struct stat *out);
int __real_lstat64(const char *path, struct stat *out);
int __real_fstatat(int dirfd, const char *path, struct stat *out, int flags);
int __real_fstatat64(int dirfd, const char *path, struct stat *out, int flags);
int __real_access(const char *path, int mode);
int __real_faccessat(int dirfd, const char *path, int mode, int flags);
DIR *__real_opendir(const char *path);
struct dirent *__real_readdir(DIR *dir);
struct dirent *__real_readdir64(DIR *dir);
int __real_closedir(DIR *dir);
void __real_rewinddir(DIR *dir);
int __real_dirfd(DIR *dir);
int __real_mkdir(const char *path, mode_t mode);
int __real_rmdir(const char *path);
int __real_unlink(const char *path);
int __real_unlinkat(int dirfd, const char *path, int flags);
int __real_remove(const char *path);
int __real_rename(const char *from, const char *to);
int __real_chdir(const char *path);
char *__real_getcwd(char *buffer, size_t size);
char *__real_realpath(const char *path, char *resolved);
int __real_scandir(const char *path, struct dirent ***names,
                   int (*filter)(const struct dirent *),
                   int (*compare)(const struct dirent **, const struct dirent **));

static mode_t mode_argument(int flags, va_list args) {
    /* The mode is only passed (and only meaningful) when a file may be created. */
    if ((flags & O_CREAT) != 0
#ifdef O_TMPFILE
        || (flags & O_TMPFILE) == O_TMPFILE
#endif
    ) {
        return (mode_t) va_arg(args, int);
    }
    return 0;
}

int __wrap_open(const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = mode_argument(flags, args);
    va_end(args);
    return g_vfs ? g_vfs->open(path, flags, mode) : __real_open(path, flags, mode);
}

int __wrap_open64(const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = mode_argument(flags, args);
    va_end(args);
    return g_vfs ? g_vfs->open(path, flags, mode) : __real_open64(path, flags, mode);
}

int __wrap_openat(int dirfd, const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = mode_argument(flags, args);
    va_end(args);
    return g_vfs ? g_vfs->openat(dirfd, path, flags, mode) : __real_openat(dirfd, path, flags, mode);
}

int __wrap_openat64(int dirfd, const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = mode_argument(flags, args);
    va_end(args);
    return g_vfs ? g_vfs->openat(dirfd, path, flags, mode) : __real_openat64(dirfd, path, flags, mode);
}

/* FORTIFY's checked forms, which an open() with non-constant flags compiles to. */
int __wrap___open_2(const char *path, int flags) {
    return g_vfs ? g_vfs->open(path, flags, 0) : __real___open_2(path, flags);
}

int __wrap___openat_2(int dirfd, const char *path, int flags) {
    return g_vfs ? g_vfs->openat(dirfd, path, flags, 0) : __real___openat_2(dirfd, path, flags);
}

int __wrap_creat(const char *path, mode_t mode) {
    return g_vfs ? g_vfs->open(path, O_CREAT | O_WRONLY | O_TRUNC, mode) : __real_creat(path, mode);
}

int __wrap_close(int fd) {
    return g_vfs ? g_vfs->close(fd) : __real_close(fd);
}

FILE *__wrap_fopen(const char *path, const char *mode) {
    return g_vfs ? g_vfs->fopen(path, mode) : __real_fopen(path, mode);
}

FILE *__wrap_fopen64(const char *path, const char *mode) {
    return g_vfs ? g_vfs->fopen(path, mode) : __real_fopen64(path, mode);
}

int __wrap_fclose(FILE *stream) {
    return g_vfs ? g_vfs->fclose(stream) : __real_fclose(stream);
}

int __wrap_stat(const char *path, struct stat *out) {
    return g_vfs ? g_vfs->stat(path, out) : __real_stat(path, out);
}

int __wrap_stat64(const char *path, struct stat *out) {
    return g_vfs ? g_vfs->stat(path, out) : __real_stat64(path, out);
}

int __wrap_lstat(const char *path, struct stat *out) {
    return g_vfs ? g_vfs->lstat(path, out) : __real_lstat(path, out);
}

int __wrap_lstat64(const char *path, struct stat *out) {
    return g_vfs ? g_vfs->lstat(path, out) : __real_lstat64(path, out);
}

int __wrap_fstatat(int dirfd, const char *path, struct stat *out, int flags) {
    return g_vfs ? g_vfs->fstatat(dirfd, path, out, flags) : __real_fstatat(dirfd, path, out, flags);
}

int __wrap_fstatat64(int dirfd, const char *path, struct stat *out, int flags) {
    return g_vfs ? g_vfs->fstatat(dirfd, path, out, flags) : __real_fstatat64(dirfd, path, out, flags);
}

int __wrap_access(const char *path, int mode) {
    return g_vfs ? g_vfs->access(path, mode) : __real_access(path, mode);
}

int __wrap_faccessat(int dirfd, const char *path, int mode, int flags) {
    return g_vfs ? g_vfs->faccessat(dirfd, path, mode, flags) : __real_faccessat(dirfd, path, mode, flags);
}

DIR *__wrap_opendir(const char *path) {
    return g_vfs ? g_vfs->opendir(path) : __real_opendir(path);
}

struct dirent *__wrap_readdir(DIR *dir) {
    return g_vfs ? g_vfs->readdir(dir) : __real_readdir(dir);
}

struct dirent *__wrap_readdir64(DIR *dir) {
    return g_vfs ? g_vfs->readdir(dir) : __real_readdir64(dir);
}

int __wrap_closedir(DIR *dir) {
    return g_vfs ? g_vfs->closedir(dir) : __real_closedir(dir);
}

void __wrap_rewinddir(DIR *dir) {
    if (g_vfs) g_vfs->rewinddir(dir); else __real_rewinddir(dir);
}

int __wrap_dirfd(DIR *dir) {
    return g_vfs ? g_vfs->dirfd(dir) : __real_dirfd(dir);
}

int __wrap_mkdir(const char *path, mode_t mode) {
    return g_vfs ? g_vfs->mkdir(path, mode) : __real_mkdir(path, mode);
}

int __wrap_rmdir(const char *path) {
    return g_vfs ? g_vfs->rmdir(path) : __real_rmdir(path);
}

int __wrap_unlink(const char *path) {
    return g_vfs ? g_vfs->unlink(path) : __real_unlink(path);
}

int __wrap_unlinkat(int dirfd, const char *path, int flags) {
    return g_vfs ? g_vfs->unlinkat(dirfd, path, flags) : __real_unlinkat(dirfd, path, flags);
}

int __wrap_remove(const char *path) {
    return g_vfs ? g_vfs->remove(path) : __real_remove(path);
}

int __wrap_rename(const char *from, const char *to) {
    return g_vfs ? g_vfs->rename(from, to) : __real_rename(from, to);
}

int __wrap_chdir(const char *path) {
    return g_vfs ? g_vfs->chdir(path) : __real_chdir(path);
}

char *__wrap_getcwd(char *buffer, size_t size) {
    return g_vfs ? g_vfs->getcwd(buffer, size) : __real_getcwd(buffer, size);
}

char *__wrap_realpath(const char *path, char *resolved) {
    return g_vfs ? g_vfs->realpath(path, resolved) : __real_realpath(path, resolved);
}

int __wrap_scandir(const char *path, struct dirent ***names,
                   int (*filter)(const struct dirent *),
                   int (*compare)(const struct dirent **, const struct dirent **)) {
    return g_vfs ? g_vfs->scandir(path, names, filter, compare) : __real_scandir(path, names, filter, compare);
}
