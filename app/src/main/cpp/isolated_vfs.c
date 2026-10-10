/*
 * The host half of the isolated file layer: the table a bound plugin
 * library's wrapped libc calls land in (plugin-native/enginehost_vfs.h,
 * docs/engine-sandbox.md "Files by path: one host VFS for every native
 * engine").
 *
 * Paths under a mounted root (the game folder, the save folder) are
 * served by that root's broker; every other path goes to libc exactly as
 * the engine asked. What the engine sees under a root:
 *
 * - Names resolve case-insensitively when the exact name is absent, as
 *   they do on Android's shared storage and on the Windows filesystems
 *   these games were made for.
 * - A file opened for reading is the host's own read-only descriptor:
 *   seekable and mmap-able, nothing is copied.
 * - A file opened for writing is a fresh host file that replaces the real
 *   one when the engine closes it (close or fclose). Opening an existing
 *   file for update without O_TRUNC copies its bytes in first, so "r+" and
 *   append keep working.
 * - Directory listings are fetched once and cached; a change made through
 *   this layer drops the cached listings it touches.
 * - chdir into a root is remembered here (the isolated process could not
 *   enter it for real) and relative paths are resolved against it.
 *
 * Plain POSIX, no JNI: app/src/test/native/isolated_vfs_test.c runs all of
 * it in CI against a temporary directory.
 */
#define _GNU_SOURCE
#include "isolated_vfs.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#define EHVFS_PATH_MAX 4096

struct mount {
    char root[EHVFS_PATH_MAX];
    size_t root_length;
    struct ehvfs_broker broker;
    int writable;
};

static struct mount g_mounts[EHVFS_MAX_MOUNTS];
static int g_mount_count;
static pthread_mutex_t g_lock = PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP;

/* The working directory when the engine has chdir'd into a mount; empty otherwise. */
static char g_cwd[EHVFS_PATH_MAX];

/* ---- listing cache ---------------------------------------------------- */

struct listing {
    int mount;
    char *relative;
    struct ehvfs_entry *entries; /* sorted by strcmp on name */
    int count;
    struct listing *next;
};

#define LISTING_BUCKETS 1024
static struct listing *g_listings[LISTING_BUCKETS];

static unsigned hash_key(int mount, const char *relative) {
    unsigned h = 2166136261u ^ (unsigned) mount;
    for (const unsigned char *p = (const unsigned char *) relative; *p; p++) h = (h ^ *p) * 16777619u;
    return h % LISTING_BUCKETS;
}

static void free_entries(struct ehvfs_entry *entries, int count) {
    for (int i = 0; i < count; i++) free(entries[i].name);
    free(entries);
}

static int compare_entries(const void *a, const void *b) {
    return strcmp(((const struct ehvfs_entry *) a)->name, ((const struct ehvfs_entry *) b)->name);
}

/* The cached listing of directory `relative`, fetching it on first use. NULL with *error set on failure. */
static struct listing *listing_of(int mount, const char *relative, int *error) {
    unsigned bucket = hash_key(mount, relative);
    for (struct listing *l = g_listings[bucket]; l; l = l->next) {
        if (l->mount == mount && strcmp(l->relative, relative) == 0) return l;
    }
    struct ehvfs_entry *entries = NULL;
    int count = 0;
    const struct ehvfs_broker *broker = &g_mounts[mount].broker;
    int result = broker->list(broker->context, relative, &entries, &count);
    if (result < 0) {
        *error = result;
        return NULL;
    }
    struct listing *l = calloc(1, sizeof *l);
    char *copy = strdup(relative);
    if (l == NULL || copy == NULL) {
        free(l);
        free(copy);
        free_entries(entries, count);
        *error = -ENOMEM;
        return NULL;
    }
    if (count > 1) qsort(entries, (size_t) count, sizeof *entries, compare_entries);
    l->mount = mount;
    l->relative = copy;
    l->entries = entries;
    l->count = count;
    l->next = g_listings[bucket];
    g_listings[bucket] = l;
    return l;
}

/* Forgets one directory's cached listing (or, with relative == NULL, every listing of a mount). */
static void forget_listing(int mount, const char *relative) {
    for (unsigned bucket = 0; bucket < LISTING_BUCKETS; bucket++) {
        if (relative != NULL && bucket != hash_key(mount, relative)) continue;
        struct listing **link = &g_listings[bucket];
        while (*link) {
            struct listing *l = *link;
            if (l->mount == mount && (relative == NULL || strcmp(l->relative, relative) == 0)) {
                *link = l->next;
                free_entries(l->entries, l->count);
                free(l->relative);
                free(l);
            } else {
                link = &l->next;
            }
        }
    }
}

static const struct ehvfs_entry *find_entry(const struct listing *l, const char *name) {
    int low = 0, high = l->count - 1;
    while (low <= high) {
        int middle = (low + high) / 2;
        int order = strcmp(l->entries[middle].name, name);
        if (order == 0) return &l->entries[middle];
        if (order < 0) low = middle + 1; else high = middle - 1;
    }
    for (int i = 0; i < l->count; i++) {
        if (strcasecmp(l->entries[i].name, name) == 0) return &l->entries[i];
    }
    return NULL;
}

/* ---- paths ------------------------------------------------------------ */

/* Collapses "//", "." and ".." in absolute path `path` into `out`. */
static int normalize(const char *path, char *out, size_t size) {
    size_t length = 0;
    const char *p = path;
    out[0] = '\0';
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char *end = strchr(p, '/');
        size_t part = end ? (size_t) (end - p) : strlen(p);
        if (part == 1 && p[0] == '.') {
            /* nothing */
        } else if (part == 2 && p[0] == '.' && p[1] == '.') {
            while (length > 0 && out[length - 1] != '/') length--;
            if (length > 0) length--;
            out[length] = '\0';
        } else {
            if (length + 1 + part + 1 > size) return -ENAMETOOLONG;
            out[length++] = '/';
            memcpy(out + length, p, part);
            length += part;
            out[length] = '\0';
        }
        p += part;
    }
    if (length == 0) {
        if (size < 2) return -ENAMETOOLONG;
        strcpy(out, "/");
    }
    return 0;
}

struct target {
    int mount;                 /* -1: not under a mount */
    char relative[EHVFS_PATH_MAX];
    char absolute[EHVFS_PATH_MAX];
    int use_absolute;          /* pass `absolute` to libc instead of the caller's path */
};

/*
 * Where `path` (relative to `dirfd`) points. A path that is not under a
 * mount goes to libc: unchanged, unless it was relative to a remembered
 * working directory inside a mount, in which case libc gets it made
 * absolute (the process's real working directory is elsewhere).
 */
static int resolve(int dirfd, const char *path, struct target *target) {
    target->mount = -1;
    target->use_absolute = 0;
    if (path == NULL) return -EFAULT;
    char joined[EHVFS_PATH_MAX];
    if (path[0] == '/') {
        if (strlen(path) >= sizeof joined) return -ENAMETOOLONG;
        strcpy(joined, path);
    } else if (dirfd == AT_FDCWD && g_cwd[0] != '\0') {
        if (path[0] == '\0') return -ENOENT;
        if ((size_t) snprintf(joined, sizeof joined, "%s/%s", g_cwd, path) >= sizeof joined) return -ENAMETOOLONG;
        target->use_absolute = 1;
    } else {
        return 0;
    }
    int result = normalize(joined, target->absolute, sizeof target->absolute);
    if (result < 0) return result;
    /* The deepest root wins: a save folder chosen inside the game folder is its own mount. */
    size_t best = 0;
    for (int m = 0; m < g_mount_count; m++) {
        const struct mount *mount = &g_mounts[m];
        if (mount->root_length <= best) continue;
        if (strncmp(target->absolute, mount->root, mount->root_length) != 0) continue;
        char next = target->absolute[mount->root_length];
        if (next != '\0' && next != '/') continue;
        target->mount = m;
        best = mount->root_length;
    }
    if (target->mount >= 0) {
        const char *rest = target->absolute + best;
        strcpy(target->relative, *rest == '/' ? rest + 1 : rest);
    }
    return 0;
}

static const char *libc_path(const char *path, const struct target *target) {
    return target->use_absolute ? target->absolute : path;
}

/*
 * Resolves `relative` component by component against the cached listings,
 * matching case-insensitively where the exact name is absent. On success
 * fills `entry` (kind and size) and `canonical` (the names as the folder
 * spells them). -ENOENT when a component is missing, -ENOTDIR when one
 * before the last is a file.
 */
static int lookup(int mount, const char *relative, struct ehvfs_entry *entry, char *canonical) {
    canonical[0] = '\0';
    entry->name = NULL;
    entry->kind = EHVFS_KIND_DIRECTORY;
    entry->size = 0;
    entry->mtime_ms = 0;
    const char *p = relative;
    while (*p) {
        if (entry->kind != EHVFS_KIND_DIRECTORY) return -ENOTDIR;
        const char *end = strchr(p, '/');
        size_t part = end ? (size_t) (end - p) : strlen(p);
        char name[NAME_MAX + 1];
        if (part > NAME_MAX) return -ENAMETOOLONG;
        memcpy(name, p, part);
        name[part] = '\0';
        int error = 0;
        struct listing *l = listing_of(mount, canonical, &error);
        if (l == NULL) return error == -ENOTDIR ? -ENOTDIR : -ENOENT;
        const struct ehvfs_entry *found = find_entry(l, name);
        if (found == NULL) return -ENOENT;
        size_t length = strlen(canonical);
        if (length + 1 + strlen(found->name) + 1 > EHVFS_PATH_MAX) return -ENAMETOOLONG;
        if (length > 0) canonical[length++] = '/';
        strcpy(canonical + length, found->name);
        *entry = *found;
        p += part;
        if (*p == '/') p++;
    }
    return 0;
}

/* Splits canonical-or-not `relative` into its parent's canonical form and its last name. */
static int parent_of(int mount, const char *relative, char *parent, char *leaf) {
    const char *slash = strrchr(relative, '/');
    char raw_parent[EHVFS_PATH_MAX];
    if (slash == NULL) {
        raw_parent[0] = '\0';
        if (strlen(relative) > NAME_MAX) return -ENAMETOOLONG;
        strcpy(leaf, relative);
    } else {
        memcpy(raw_parent, relative, (size_t) (slash - relative));
        raw_parent[slash - relative] = '\0';
        if (strlen(slash + 1) > NAME_MAX) return -ENAMETOOLONG;
        strcpy(leaf, slash + 1);
    }
    if (leaf[0] == '\0') return -ENOENT;
    struct ehvfs_entry entry;
    int result = lookup(mount, raw_parent, &entry, parent);
    if (result < 0) return result;
    return entry.kind == EHVFS_KIND_DIRECTORY ? 0 : -ENOTDIR;
}

/* `first` and `second` joined with a '/' (just `second` when `first` is empty) into EHVFS_PATH_MAX bytes. */
static int join(const char *first, const char *second, char *out) {
    size_t a = strlen(first), b = strlen(second);
    size_t separator = a > 0 && b > 0 ? 1 : 0;
    if (a + separator + b + 1 > EHVFS_PATH_MAX) return -ENAMETOOLONG;
    memmove(out, first, a);
    if (separator) out[a] = '/';
    memmove(out + a + separator, second, b + 1);
    return 0;
}

static int fail(int negative_errno) {
    errno = -negative_errno;
    return -1;
}

/* ---- descriptors written through the layer ----------------------------- */

struct pending {
    int fd;
    int mount;
    char *relative;
    struct pending *next;
};

static struct pending *g_pending;

static void remember_pending(int fd, int mount, const char *relative) {
    struct pending *p = calloc(1, sizeof *p);
    if (p == NULL) return;
    p->relative = strdup(relative);
    if (p->relative == NULL) {
        free(p);
        return;
    }
    p->fd = fd;
    p->mount = mount;
    p->next = g_pending;
    g_pending = p;
}

static struct pending *take_pending(int fd) {
    for (struct pending **link = &g_pending; *link; link = &(*link)->next) {
        if ((*link)->fd == fd) {
            struct pending *p = *link;
            *link = p->next;
            return p;
        }
    }
    return NULL;
}

static void forget_parent_listing(int mount, const char *relative) {
    const char *slash = strrchr(relative, '/');
    if (slash == NULL) {
        forget_listing(mount, "");
    } else {
        char parent[EHVFS_PATH_MAX];
        memcpy(parent, relative, (size_t) (slash - relative));
        parent[slash - relative] = '\0';
        forget_listing(mount, parent);
    }
}

static int commit(struct pending *p) {
    const struct ehvfs_broker *broker = &g_mounts[p->mount].broker;
    int result = broker->commit_write(broker->context, p->relative);
    forget_parent_listing(p->mount, p->relative);
    free(p->relative);
    free(p);
    return result;
}

static int copy_into(int from, int to) {
    char buffer[65536];
    for (;;) {
        ssize_t n = read(from, buffer, sizeof buffer);
        if (n == 0) return 0;
        if (n < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        for (ssize_t done = 0; done < n;) {
            ssize_t w = write(to, buffer + done, (size_t) (n - done));
            if (w < 0) {
                if (errno == EINTR) continue;
                return -errno;
            }
            done += w;
        }
    }
}

static int open_in_mount(const struct target *target, int flags, int *out_fd) {
    const struct mount *mount = &g_mounts[target->mount];
    const struct ehvfs_broker *broker = &mount->broker;
    int access_mode = flags & O_ACCMODE;
    int writes = access_mode != O_RDONLY || (flags & (O_CREAT | O_TRUNC)) != 0;
    struct ehvfs_entry entry;
    char canonical[EHVFS_PATH_MAX];
    int found = lookup(target->mount, target->relative, &entry, canonical);
    if (found < 0 && found != -ENOENT) return found;
    int exists = found == 0;

    if (!writes) {
        if (!exists) return -ENOENT;
        if (entry.kind == EHVFS_KIND_DIRECTORY) return -EACCES;
        if (flags & O_DIRECTORY) return -ENOTDIR;
        int fd = broker->open_read(broker->context, canonical);
        if (fd < 0) return fd;
        *out_fd = fd;
        return 0;
    }

    if (!mount->writable) return -EROFS;
    if (exists && entry.kind == EHVFS_KIND_DIRECTORY) return -EISDIR;
    if (exists && (flags & O_CREAT) && (flags & O_EXCL)) return -EEXIST;
    if (!exists && !(flags & O_CREAT)) return -ENOENT;
    char target_relative[EHVFS_PATH_MAX];
    if (exists) {
        strcpy(target_relative, canonical);
    } else {
        char parent[EHVFS_PATH_MAX], leaf[NAME_MAX + 1];
        int result = parent_of(target->mount, target->relative, parent, leaf);
        if (result < 0) return result;
        result = join(parent, leaf, target_relative);
        if (result < 0) return result;
    }
    int fd = broker->open_write(broker->context, target_relative);
    if (fd < 0) return fd;
    if (exists && !(flags & O_TRUNC)) {
        int old = broker->open_read(broker->context, target_relative);
        int result = old < 0 ? old : copy_into(old, fd);
        if (old >= 0) close(old);
        if (result < 0) {
            close(fd);
            return result;
        }
    }
    if (flags & O_APPEND) {
        int status = fcntl(fd, F_GETFL);
        if (status >= 0) fcntl(fd, F_SETFL, status | O_APPEND);
    } else {
        lseek(fd, 0, SEEK_SET);
    }
    remember_pending(fd, target->mount, target_relative);
    *out_fd = fd;
    return 0;
}

/* ---- the table -------------------------------------------------------- */

static int vfs_openat(int dirfd, const char *path, int flags, mode_t mode) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(dirfd, path, &target);
    if (result < 0) {
        pthread_mutex_unlock(&g_lock);
        return fail(result);
    }
    if (target.mount < 0) {
        pthread_mutex_unlock(&g_lock);
        return target.use_absolute ? open(target.absolute, flags, mode) : openat(dirfd, path, flags, mode);
    }
    int fd = -1;
    result = open_in_mount(&target, flags, &fd);
    pthread_mutex_unlock(&g_lock);
    if (result < 0) return fail(result);
    if (flags & O_CLOEXEC) fcntl(fd, F_SETFD, FD_CLOEXEC);
    return fd;
}

static int vfs_open(const char *path, int flags, mode_t mode) {
    return vfs_openat(AT_FDCWD, path, flags, mode);
}

static int vfs_close(int fd) {
    pthread_mutex_lock(&g_lock);
    struct pending *p = take_pending(fd);
    pthread_mutex_unlock(&g_lock);
    int closed = close(fd);
    if (p == NULL) return closed;
    pthread_mutex_lock(&g_lock);
    int committed = commit(p);
    pthread_mutex_unlock(&g_lock);
    if (closed < 0) return closed;
    return committed < 0 ? fail(committed) : 0;
}

static int flags_of_mode(const char *mode, char *fdopen_mode) {
    int flags;
    switch (mode[0]) {
        case 'r': flags = O_RDONLY; break;
        case 'w': flags = O_WRONLY | O_CREAT | O_TRUNC; break;
        case 'a': flags = O_WRONLY | O_CREAT | O_APPEND; break;
        default: return -EINVAL;
    }
    size_t out = 0;
    fdopen_mode[out++] = mode[0];
    for (const char *p = mode + 1; *p; p++) {
        if (*p == '+') {
            flags = (flags & ~O_ACCMODE) | O_RDWR;
            fdopen_mode[out++] = '+';
        } else if (*p == 'x') {
            flags |= O_EXCL;
        } else if (*p == 'e') {
            flags |= O_CLOEXEC;
        } else if (*p == 'b' && out < 6) {
            fdopen_mode[out++] = 'b';
        }
    }
    fdopen_mode[out] = '\0';
    return flags;
}

static FILE *vfs_fopen(const char *path, const char *mode) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(AT_FDCWD, path, &target);
    if (result < 0 || target.mount < 0 || mode == NULL) {
        pthread_mutex_unlock(&g_lock);
        if (result < 0) {
            errno = -result;
            return NULL;
        }
        return fopen(libc_path(path, &target), mode);
    }
    char fdopen_mode[8];
    int flags = flags_of_mode(mode, fdopen_mode);
    int fd = -1;
    result = flags < 0 ? flags : open_in_mount(&target, flags, &fd);
    pthread_mutex_unlock(&g_lock);
    if (result < 0) {
        errno = -result;
        return NULL;
    }
    FILE *stream = fdopen(fd, fdopen_mode);
    if (stream == NULL) {
        int saved = errno;
        pthread_mutex_lock(&g_lock);
        struct pending *p = take_pending(fd);
        pthread_mutex_unlock(&g_lock);
        if (p != NULL) {
            free(p->relative);
            free(p);
        }
        close(fd);
        errno = saved;
    }
    return stream;
}

static int vfs_fclose(FILE *stream) {
    if (stream == NULL) return fclose(stream);
    int fd = fileno(stream);
    pthread_mutex_lock(&g_lock);
    struct pending *p = fd >= 0 ? take_pending(fd) : NULL;
    pthread_mutex_unlock(&g_lock);
    int closed = fclose(stream);
    if (p == NULL) return closed;
    pthread_mutex_lock(&g_lock);
    int committed = commit(p);
    pthread_mutex_unlock(&g_lock);
    if (closed != 0) return closed;
    if (committed < 0) {
        errno = -committed;
        return EOF;
    }
    return 0;
}

static unsigned long inode_of(const char *absolute) {
    unsigned long h = 5381;
    for (const unsigned char *p = (const unsigned char *) absolute; *p; p++) h = h * 33 + *p;
    return h | 1;
}

static void fill_stat(const struct mount *mount, const struct ehvfs_entry *entry, const char *absolute, struct stat *out) {
    memset(out, 0, sizeof *out);
    out->st_dev = 0xE4;
    out->st_ino = inode_of(absolute);
    if (entry->kind == EHVFS_KIND_DIRECTORY) {
        out->st_mode = S_IFDIR | (mount->writable ? 0755 : 0555);
        out->st_nlink = 2;
        out->st_size = 4096;
    } else {
        out->st_mode = S_IFREG | (mount->writable ? 0644 : 0444);
        out->st_nlink = 1;
        out->st_size = entry->size;
    }
    out->st_uid = getuid();
    out->st_gid = getgid();
    out->st_blksize = 4096;
    out->st_blocks = (out->st_size + 511) / 512;
    out->st_mtim.tv_sec = entry->mtime_ms / 1000;
    out->st_mtim.tv_nsec = (entry->mtime_ms % 1000) * 1000000;
    out->st_atim = out->st_mtim;
    out->st_ctim = out->st_mtim;
}

static int vfs_fstatat(int dirfd, const char *path, struct stat *out, int flags) {
    if (path != NULL && path[0] == '\0') return fstatat(dirfd, path, out, flags);
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(dirfd, path, &target);
    if (result < 0 || target.mount < 0) {
        pthread_mutex_unlock(&g_lock);
        if (result < 0) return fail(result);
        return target.use_absolute ? fstatat(AT_FDCWD, target.absolute, out, flags) : fstatat(dirfd, path, out, flags);
    }
    struct ehvfs_entry entry;
    char canonical[EHVFS_PATH_MAX];
    result = lookup(target.mount, target.relative, &entry, canonical);
    if (result == 0) fill_stat(&g_mounts[target.mount], &entry, target.absolute, out);
    pthread_mutex_unlock(&g_lock);
    return result < 0 ? fail(result) : 0;
}

static int vfs_stat(const char *path, struct stat *out) {
    return vfs_fstatat(AT_FDCWD, path, out, 0);
}

static int vfs_lstat(const char *path, struct stat *out) {
    return vfs_fstatat(AT_FDCWD, path, out, AT_SYMLINK_NOFOLLOW);
}

static int vfs_faccessat(int dirfd, const char *path, int mode, int flags) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(dirfd, path, &target);
    if (result < 0 || target.mount < 0) {
        pthread_mutex_unlock(&g_lock);
        if (result < 0) return fail(result);
        return target.use_absolute ? faccessat(AT_FDCWD, target.absolute, mode, flags) : faccessat(dirfd, path, mode, flags);
    }
    struct ehvfs_entry entry;
    char canonical[EHVFS_PATH_MAX];
    result = lookup(target.mount, target.relative, &entry, canonical);
    int writable = g_mounts[target.mount].writable;
    pthread_mutex_unlock(&g_lock);
    if (result < 0) return fail(result);
    if ((mode & W_OK) && !writable) return fail(-EROFS);
    if ((mode & X_OK) && entry.kind != EHVFS_KIND_DIRECTORY) return fail(-EACCES);
    return 0;
}

static int vfs_access(const char *path, int mode) {
    return vfs_faccessat(AT_FDCWD, path, mode, 0);
}

/* A directory listed through the layer. Its address is what the engine holds as a DIR*. */
struct listed_dir {
    struct listed_dir *next;
    struct ehvfs_entry *entries;
    int count;
    int position; /* 0 ".", 1 "..", then entries */
    unsigned long inode;
    struct dirent current;
};

static struct listed_dir *g_dirs;

static struct listed_dir *as_listed(DIR *dir) {
    for (struct listed_dir *d = g_dirs; d; d = d->next) {
        if ((DIR *) d == dir) return d;
    }
    return NULL;
}

/* A private copy of a mount directory's listing, for opendir and scandir. */
static int copy_listing(const struct target *target, struct ehvfs_entry **entries, int *count) {
    struct ehvfs_entry entry;
    char canonical[EHVFS_PATH_MAX];
    int result = lookup(target->mount, target->relative, &entry, canonical);
    if (result < 0) return result;
    if (entry.kind != EHVFS_KIND_DIRECTORY) return -ENOTDIR;
    int error = 0;
    struct listing *l = listing_of(target->mount, canonical, &error);
    if (l == NULL) return error;
    struct ehvfs_entry *copy = calloc((size_t) (l->count > 0 ? l->count : 1), sizeof *copy);
    if (copy == NULL) return -ENOMEM;
    for (int i = 0; i < l->count; i++) {
        copy[i] = l->entries[i];
        copy[i].name = strdup(l->entries[i].name);
        if (copy[i].name == NULL) {
            free_entries(copy, i);
            return -ENOMEM;
        }
    }
    *entries = copy;
    *count = l->count;
    return 0;
}

static DIR *vfs_opendir(const char *path) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(AT_FDCWD, path, &target);
    if (result < 0 || target.mount < 0) {
        pthread_mutex_unlock(&g_lock);
        if (result < 0) {
            errno = -result;
            return NULL;
        }
        return opendir(libc_path(path, &target));
    }
    struct listed_dir *d = calloc(1, sizeof *d);
    if (d == NULL) {
        pthread_mutex_unlock(&g_lock);
        errno = ENOMEM;
        return NULL;
    }
    result = copy_listing(&target, &d->entries, &d->count);
    if (result < 0) {
        pthread_mutex_unlock(&g_lock);
        free(d);
        errno = -result;
        return NULL;
    }
    d->inode = inode_of(target.absolute);
    d->next = g_dirs;
    g_dirs = d;
    pthread_mutex_unlock(&g_lock);
    return (DIR *) d;
}

static void set_dirent(struct dirent *out, unsigned long inode, long offset, unsigned char type, const char *name) {
    memset(out, 0, sizeof *out);
    out->d_ino = inode;
    out->d_off = offset;
    out->d_reclen = sizeof *out;
    out->d_type = type;
    snprintf(out->d_name, sizeof out->d_name, "%s", name);
}

static struct dirent *vfs_readdir(DIR *dir) {
    pthread_mutex_lock(&g_lock);
    struct listed_dir *d = as_listed(dir);
    if (d == NULL) {
        pthread_mutex_unlock(&g_lock);
        return readdir(dir);
    }
    struct dirent *out = NULL;
    if (d->position == 0) {
        set_dirent(&d->current, d->inode, 1, DT_DIR, ".");
        out = &d->current;
    } else if (d->position == 1) {
        set_dirent(&d->current, d->inode ^ 2, 2, DT_DIR, "..");
        out = &d->current;
    } else if (d->position - 2 < d->count) {
        const struct ehvfs_entry *e = &d->entries[d->position - 2];
        set_dirent(&d->current, d->inode ^ inode_of(e->name), d->position + 1,
                   e->kind == EHVFS_KIND_DIRECTORY ? DT_DIR : DT_REG, e->name);
        out = &d->current;
    }
    if (out != NULL) d->position++;
    pthread_mutex_unlock(&g_lock);
    return out;
}

static int vfs_closedir(DIR *dir) {
    pthread_mutex_lock(&g_lock);
    for (struct listed_dir **link = &g_dirs; *link; link = &(*link)->next) {
        if ((DIR *) *link == dir) {
            struct listed_dir *d = *link;
            *link = d->next;
            pthread_mutex_unlock(&g_lock);
            free_entries(d->entries, d->count);
            free(d);
            return 0;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return closedir(dir);
}

static void vfs_rewinddir(DIR *dir) {
    pthread_mutex_lock(&g_lock);
    struct listed_dir *d = as_listed(dir);
    if (d != NULL) d->position = 0;
    pthread_mutex_unlock(&g_lock);
    if (d == NULL) rewinddir(dir);
}

static int vfs_dirfd(DIR *dir) {
    pthread_mutex_lock(&g_lock);
    struct listed_dir *d = as_listed(dir);
    pthread_mutex_unlock(&g_lock);
    if (d != NULL) return fail(-ENOTSUP);
    return dirfd(dir);
}

static int vfs_scandir(const char *path, struct dirent ***names,
                       int (*filter)(const struct dirent *),
                       int (*compare)(const struct dirent **, const struct dirent **)) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(AT_FDCWD, path, &target);
    if (result < 0 || target.mount < 0) {
        pthread_mutex_unlock(&g_lock);
        if (result < 0) return fail(result);
        return scandir(libc_path(path, &target), names, filter, compare);
    }
    struct ehvfs_entry *entries = NULL;
    int count = 0;
    result = copy_listing(&target, &entries, &count);
    unsigned long inode = inode_of(target.absolute);
    pthread_mutex_unlock(&g_lock);
    if (result < 0) return fail(result);
    struct dirent **list = malloc(sizeof *list * (size_t) (count + 2));
    if (list == NULL) {
        free_entries(entries, count);
        return fail(-ENOMEM);
    }
    int kept = 0;
    for (int i = -2; i < count; i++) {
        struct dirent candidate;
        if (i == -2) set_dirent(&candidate, inode, 1, DT_DIR, ".");
        else if (i == -1) set_dirent(&candidate, inode ^ 2, 2, DT_DIR, "..");
        else set_dirent(&candidate, inode ^ inode_of(entries[i].name), i + 3,
                        entries[i].kind == EHVFS_KIND_DIRECTORY ? DT_DIR : DT_REG, entries[i].name);
        if (filter != NULL && !filter(&candidate)) continue;
        struct dirent *copy = malloc(sizeof *copy);
        if (copy == NULL) {
            for (int k = 0; k < kept; k++) free(list[k]);
            free(list);
            free_entries(entries, count);
            return fail(-ENOMEM);
        }
        *copy = candidate;
        list[kept++] = copy;
    }
    free_entries(entries, count);
    if (compare != NULL && kept > 1) {
        qsort(list, (size_t) kept, sizeof *list, (int (*)(const void *, const void *)) compare);
    }
    *names = list;
    return kept;
}

/* Resolves the target of a change: 1 when it is not under a mount (libc's), -EROFS on a read-only one. Under the lock. */
static int mutate(const char *path, int dirfd, struct target *target) {
    int result = resolve(dirfd, path, target);
    if (result < 0) return result;
    if (target->mount < 0) return 1;
    if (!g_mounts[target->mount].writable) return -EROFS;
    return 0;
}

static int vfs_mkdir(const char *path, mode_t mode) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = mutate(path, AT_FDCWD, &target);
    if (result == 1) {
        pthread_mutex_unlock(&g_lock);
        return mkdir(libc_path(path, &target), mode);
    }
    if (result == 0) {
        struct ehvfs_entry entry;
        char canonical[EHVFS_PATH_MAX];
        if (target.relative[0] == '\0' || lookup(target.mount, target.relative, &entry, canonical) == 0) {
            result = -EEXIST;
        } else {
            char parent[EHVFS_PATH_MAX], leaf[NAME_MAX + 1], joined[EHVFS_PATH_MAX];
            result = parent_of(target.mount, target.relative, parent, leaf);
            if (result == 0) result = join(parent, leaf, joined);
            if (result == 0) {
                const struct ehvfs_broker *broker = &g_mounts[target.mount].broker;
                result = broker->make_directory(broker->context, joined);
                forget_listing(target.mount, parent);
            }
        }
    }
    pthread_mutex_unlock(&g_lock);
    return result < 0 ? fail(result) : 0;
}

/* Removes a file (want_directory 0), an empty directory (1), or either (-1). */
static int remove_in_mount(const struct target *target, int want_directory) {
    if (target->relative[0] == '\0') return -EBUSY;
    struct ehvfs_entry entry;
    char canonical[EHVFS_PATH_MAX];
    int result = lookup(target->mount, target->relative, &entry, canonical);
    if (result < 0) return result;
    int is_directory = entry.kind == EHVFS_KIND_DIRECTORY;
    if (want_directory == 0 && is_directory) return -EISDIR;
    if (want_directory == 1 && !is_directory) return -ENOTDIR;
    if (is_directory) {
        int error = 0;
        struct listing *l = listing_of(target->mount, canonical, &error);
        if (l == NULL) return error;
        if (l->count > 0) return -ENOTEMPTY;
    }
    const struct ehvfs_broker *broker = &g_mounts[target->mount].broker;
    result = broker->remove(broker->context, canonical);
    if (is_directory) forget_listing(target->mount, NULL);
    else forget_parent_listing(target->mount, canonical);
    return result;
}

static int vfs_remove_kind(int dirfd, const char *path, int want_directory) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = mutate(path, dirfd, &target);
    if (result == 1) {
        pthread_mutex_unlock(&g_lock);
        const char *p = libc_path(path, &target);
        int at = target.use_absolute ? AT_FDCWD : dirfd;
        if (want_directory == 1) return unlinkat(at, p, AT_REMOVEDIR);
        if (want_directory == 0) return unlinkat(at, p, 0);
        return target.use_absolute || dirfd == AT_FDCWD ? remove(p) : unlinkat(at, p, 0);
    }
    if (result == 0) result = remove_in_mount(&target, want_directory);
    pthread_mutex_unlock(&g_lock);
    return result < 0 ? fail(result) : 0;
}

static int vfs_rmdir(const char *path) { return vfs_remove_kind(AT_FDCWD, path, 1); }
static int vfs_unlink(const char *path) { return vfs_remove_kind(AT_FDCWD, path, 0); }
static int vfs_remove(const char *path) { return vfs_remove_kind(AT_FDCWD, path, -1); }

static int vfs_unlinkat(int dirfd, const char *path, int flags) {
    return vfs_remove_kind(dirfd, path, (flags & AT_REMOVEDIR) ? 1 : 0);
}

static int vfs_rename(const char *from, const char *to) {
    pthread_mutex_lock(&g_lock);
    struct target source, destination;
    int result = resolve(AT_FDCWD, from, &source);
    if (result == 0) result = resolve(AT_FDCWD, to, &destination);
    if (result < 0) {
        pthread_mutex_unlock(&g_lock);
        return fail(result);
    }
    if (source.mount < 0 && destination.mount < 0) {
        pthread_mutex_unlock(&g_lock);
        return rename(libc_path(from, &source), libc_path(to, &destination));
    }
    if (source.mount != destination.mount) result = -EXDEV;
    else if (!g_mounts[source.mount].writable) result = -EROFS;
    if (result == 0) {
        struct ehvfs_entry entry;
        char canonical[EHVFS_PATH_MAX];
        result = lookup(source.mount, source.relative, &entry, canonical);
        char parent[EHVFS_PATH_MAX], leaf[NAME_MAX + 1], joined[EHVFS_PATH_MAX];
        if (result == 0) result = parent_of(destination.mount, destination.relative, parent, leaf);
        if (result == 0) result = join(parent, leaf, joined);
        if (result == 0) {
            struct ehvfs_entry existing;
            char existing_canonical[EHVFS_PATH_MAX];
            /* Replace a differently cased existing name rather than create a twin beside it. */
            if (lookup(destination.mount, joined, &existing, existing_canonical) == 0) {
                strcpy(joined, existing_canonical);
            }
            const struct ehvfs_broker *broker = &g_mounts[source.mount].broker;
            result = broker->rename(broker->context, canonical, joined);
            forget_listing(source.mount, NULL);
        }
    }
    pthread_mutex_unlock(&g_lock);
    return result < 0 ? fail(result) : 0;
}

static int vfs_chdir(const char *path) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(AT_FDCWD, path, &target);
    if (result < 0) {
        pthread_mutex_unlock(&g_lock);
        return fail(result);
    }
    if (target.mount < 0) {
        result = chdir(libc_path(path, &target));
        if (result == 0) g_cwd[0] = '\0';
        pthread_mutex_unlock(&g_lock);
        return result;
    }
    struct ehvfs_entry entry;
    char canonical[EHVFS_PATH_MAX];
    result = lookup(target.mount, target.relative, &entry, canonical);
    if (result == 0 && entry.kind != EHVFS_KIND_DIRECTORY) result = -ENOTDIR;
    if (result == 0) result = join(g_mounts[target.mount].root, canonical, g_cwd);
    pthread_mutex_unlock(&g_lock);
    return result < 0 ? fail(result) : 0;
}

static char *vfs_getcwd(char *buffer, size_t size) {
    pthread_mutex_lock(&g_lock);
    if (g_cwd[0] == '\0') {
        pthread_mutex_unlock(&g_lock);
        return getcwd(buffer, size);
    }
    size_t needed = strlen(g_cwd) + 1;
    char *out = buffer;
    if (out == NULL) {
        out = malloc(size > needed ? size : needed);
        if (out == NULL) {
            pthread_mutex_unlock(&g_lock);
            errno = ENOMEM;
            return NULL;
        }
    } else if (size < needed) {
        pthread_mutex_unlock(&g_lock);
        errno = size == 0 ? EINVAL : ERANGE;
        return NULL;
    }
    memcpy(out, g_cwd, needed);
    pthread_mutex_unlock(&g_lock);
    return out;
}

static char *vfs_realpath(const char *path, char *resolved) {
    pthread_mutex_lock(&g_lock);
    struct target target;
    int result = resolve(AT_FDCWD, path, &target);
    if (result < 0 || target.mount < 0) {
        pthread_mutex_unlock(&g_lock);
        if (result < 0) {
            errno = -result;
            return NULL;
        }
        return realpath(libc_path(path, &target), resolved);
    }
    struct ehvfs_entry entry;
    char canonical[EHVFS_PATH_MAX];
    result = lookup(target.mount, target.relative, &entry, canonical);
    char full[EHVFS_PATH_MAX];
    if (result == 0) result = join(g_mounts[target.mount].root, canonical, full);
    pthread_mutex_unlock(&g_lock);
    if (result < 0) {
        errno = -result;
        return NULL;
    }
    if (strlen(full) >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return NULL;
    }
    char *out = resolved != NULL ? resolved : malloc(PATH_MAX);
    if (out == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    strcpy(out, full);
    return out;
}

static const struct enginehost_vfs_table g_table = {
    .size = sizeof(struct enginehost_vfs_table),
    .version = ENGINEHOST_VFS_VERSION,
    .open = vfs_open,
    .openat = vfs_openat,
    .close = vfs_close,
    .fopen = vfs_fopen,
    .fclose = vfs_fclose,
    .stat = vfs_stat,
    .lstat = vfs_lstat,
    .fstatat = vfs_fstatat,
    .access = vfs_access,
    .faccessat = vfs_faccessat,
    .opendir = vfs_opendir,
    .readdir = vfs_readdir,
    .closedir = vfs_closedir,
    .rewinddir = vfs_rewinddir,
    .dirfd = vfs_dirfd,
    .mkdir = vfs_mkdir,
    .rmdir = vfs_rmdir,
    .unlink = vfs_unlink,
    .unlinkat = vfs_unlinkat,
    .remove = vfs_remove,
    .rename = vfs_rename,
    .chdir = vfs_chdir,
    .getcwd = vfs_getcwd,
    .realpath = vfs_realpath,
    .scandir = vfs_scandir,
};

const struct enginehost_vfs_table *ehvfs_table(void) {
    return &g_table;
}

int ehvfs_mount(const char *root, const struct ehvfs_broker *broker, int writable) {
    if (root == NULL || root[0] != '/' || broker == NULL) return -EINVAL;
    pthread_mutex_lock(&g_lock);
    if (g_mount_count >= EHVFS_MAX_MOUNTS) {
        pthread_mutex_unlock(&g_lock);
        return -ENOSPC;
    }
    struct mount *mount = &g_mounts[g_mount_count];
    int result = normalize(root, mount->root, sizeof mount->root);
    if (result == 0 && strcmp(mount->root, "/") == 0) result = -EINVAL;
    if (result == 0) {
        mount->root_length = strlen(mount->root);
        mount->broker = *broker;
        mount->writable = writable;
        g_mount_count++;
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}
