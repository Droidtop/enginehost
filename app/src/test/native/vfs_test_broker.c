/*
 * A broker over two real directories, for isolated_vfs_test: built into
 * the same unwrapped shared library as isolated_vfs.c, exactly as the
 * host's own libenginehost_sandbox.so is, so its libc calls are real ones.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "isolated_vfs.h"

static char g_real[2][1024];

static void real_path(void *context, const char *relative, char *out) {
    const char *root = g_real[(intptr_t) context];
    if (relative[0] == '\0') snprintf(out, 4096, "%s", root);
    else snprintf(out, 4096, "%s/%s", root, relative);
}

static int fake_open_read(void *context, const char *relative) {
    char path[4096];
    real_path(context, relative, path);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    return fd < 0 ? -errno : fd;
}

static int fake_open_write(void *context, const char *relative) {
    char path[4096];
    real_path(context, relative, path);
    strncat(path, ".isolated-write", sizeof path - strlen(path) - 1);
    int fd = open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    return fd < 0 ? -errno : fd;
}

static int fake_commit_write(void *context, const char *relative) {
    char path[4096], pending[4200];
    real_path(context, relative, path);
    snprintf(pending, sizeof pending, "%s.isolated-write", path);
    return rename(pending, path) == 0 ? 0 : -errno;
}

static int fake_remove(void *context, const char *relative) {
    char path[4096];
    real_path(context, relative, path);
    return remove(path) == 0 ? 0 : -errno;
}

static int fake_make_directory(void *context, const char *relative) {
    char path[4096];
    real_path(context, relative, path);
    return mkdir(path, 0755) == 0 ? 0 : -errno;
}

static int fake_rename(void *context, const char *from, const char *to) {
    char a[4096], b[4096];
    real_path(context, from, a);
    real_path(context, to, b);
    return rename(a, b) == 0 ? 0 : -errno;
}

static int fake_list(void *context, const char *relative, struct ehvfs_entry **entries, int *count) {
    char path[4096];
    real_path(context, relative, path);
    DIR *dir = opendir(path);
    if (dir == NULL) return -errno;
    int capacity = 16, n = 0;
    struct ehvfs_entry *out = calloc((size_t) capacity, sizeof *out);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        size_t length = strlen(entry->d_name);
        if (length > 15 && strcmp(entry->d_name + length - 15, ".isolated-write") == 0) continue;
        char child[4400];
        snprintf(child, sizeof child, "%s/%s", path, entry->d_name);
        struct stat info;
        if (stat(child, &info) != 0) continue;
        if (n == capacity) {
            capacity *= 2;
            out = realloc(out, sizeof *out * (size_t) capacity);
        }
        out[n].name = strdup(entry->d_name);
        out[n].kind = S_ISDIR(info.st_mode) ? EHVFS_KIND_DIRECTORY : EHVFS_KIND_FILE;
        out[n].size = S_ISDIR(info.st_mode) ? 0 : info.st_size;
        out[n].mtime_ms = (int64_t) info.st_mtime * 1000;
        n++;
    }
    closedir(dir);
    *entries = out;
    *count = n;
    return 0;
}

int vfs_test_mount(const char *virtual_root, const char *real_root, int which, int writable) {
    snprintf(g_real[which], sizeof g_real[which], "%s", real_root);
    struct ehvfs_broker broker = {
        .context = (void *) (intptr_t) which,
        .open_read = fake_open_read,
        .open_write = fake_open_write,
        .commit_write = fake_commit_write,
        .remove = fake_remove,
        .make_directory = fake_make_directory,
        .rename = fake_rename,
        .list = fake_list,
    };
    return ehvfs_mount(virtual_root, &broker, writable);
}
