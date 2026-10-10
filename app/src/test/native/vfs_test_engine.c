/*
 * isolated_vfs.c end to end, the way an engine meets it: this file is the
 * "engine", linked with plugin-native/enginehost_vfs_forward.c and the
 * --wrap flags from plugin-native/enginehost_vfs.cmake, against a shared
 * library holding isolated_vfs.c and a broker over two temporary
 * directories (vfs_test_broker.c). The game folder is mounted read-only
 * at /enginehost-test/game, the save folder writable at
 * /enginehost-test/save; neither path exists for real, so anything that
 * reaches libc instead of the layer fails visibly.
 *
 * Run by app/src/test/native/run.sh, in CI before the Gradle build.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <pthread.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "enginehost_vfs.h"

int vfs_test_mount(const char *virtual_root, const char *real_root, int which, int writable);
const struct enginehost_vfs_table *ehvfs_table(void);
void vfs_test_watch_exit(void);
int vfs_test_exit_status(void);

static void *engine_thread_that_quits(void *unused) {
    (void) unused;
    exit(7);
}

#define GAME "/enginehost-test/game"
#define SAVE "/enginehost-test/save"

static int g_failures;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "FAIL %s:%d: %s (errno %d: %s)\n", __FILE__, __LINE__, #condition, errno, strerror(errno)); \
        g_failures++; \
    } \
} while (0)

static void write_real(const char *path, const char *content) {
    FILE *f = fopen(path, "wb");
    if (f == NULL) {
        perror(path);
        exit(2);
    }
    fputs(content, f);
    fclose(f);
}

static char *read_all(const char *path) {
    FILE *f = fopen(path, "rb");
    if (f == NULL) return NULL;
    static char buffer[4096];
    size_t n = fread(buffer, 1, sizeof buffer - 1, f);
    buffer[n] = '\0';
    fclose(f);
    return buffer;
}

int main(void) {
    char base[] = "/tmp/enginehost-vfs-XXXXXX";
    if (mkdtemp(base) == NULL) {
        perror("mkdtemp");
        return 2;
    }
    char game[4096], save[4096], path[4096];
    snprintf(game, sizeof game, "%s/game", base);
    snprintf(save, sizeof save, "%s/save", base);
    mkdir(game, 0755);
    mkdir(save, 0755);
    snprintf(path, sizeof path, "%s/Sub", game);
    mkdir(path, 0755);
    snprintf(path, sizeof path, "%s/a.txt", game);
    write_real(path, "hello");
    snprintf(path, sizeof path, "%s/Sub/B.DAT", game);
    write_real(path, "0123456789");
    snprintf(path, sizeof path, "%s/old.sav", save);
    write_real(path, "abcdef");

    /* Unbound: the forwarder is libc, and the virtual roots do not exist. */
    errno = 0;
    CHECK(fopen(GAME "/a.txt", "rb") == NULL && errno == ENOENT);

    CHECK(vfs_test_mount(GAME, game, 0, 0) == 0);
    CHECK(vfs_test_mount(SAVE, save, 1, 1) == 0);
    enginehost_vfs_bind(ehvfs_table());

    /* Reading, exact and differently cased names. */
    char *text = read_all(GAME "/a.txt");
    CHECK(text != NULL && strcmp(text, "hello") == 0);
    text = read_all(GAME "/sub/b.dat");
    CHECK(text != NULL && strcmp(text, "0123456789") == 0);
    text = read_all(GAME "/./Sub/../a.txt");
    CHECK(text != NULL && strcmp(text, "hello") == 0);
    errno = 0;
    CHECK(fopen(GAME "/missing.txt", "rb") == NULL && errno == ENOENT);

    int fd = open(GAME "/Sub/B.DAT", O_RDONLY);
    CHECK(fd >= 0);
    if (fd >= 0) {
        char buffer[4] = { 0 };
        CHECK(lseek(fd, 3, SEEK_SET) == 3);
        CHECK(read(fd, buffer, 3) == 3 && memcmp(buffer, "345", 3) == 0);
        CHECK(close(fd) == 0);
    }

    /* stat and access. */
    struct stat info;
    CHECK(stat(GAME "/Sub/B.DAT", &info) == 0 && S_ISREG(info.st_mode) && info.st_size == 10);
    CHECK(stat(GAME "/sub", &info) == 0 && S_ISDIR(info.st_mode));
    CHECK(stat(GAME, &info) == 0 && S_ISDIR(info.st_mode));
    errno = 0;
    CHECK(stat(GAME "/nope", &info) == -1 && errno == ENOENT);
    errno = 0;
    CHECK(stat(GAME "/a.txt/inside", &info) == -1 && errno == ENOTDIR);
    CHECK(access(GAME "/a.txt", R_OK) == 0);
    errno = 0;
    CHECK(access(GAME "/a.txt", W_OK) == -1 && errno == EROFS);
    CHECK(lstat(GAME "/a.txt", &info) == 0 && info.st_size == 5);

    /* Listing. */
    DIR *dir = opendir(GAME);
    CHECK(dir != NULL);
    int seen = 0, saw_sub = 0;
    struct dirent *entry;
    while (dir != NULL && (entry = readdir(dir)) != NULL) {
        seen++;
        if (strcmp(entry->d_name, "Sub") == 0) saw_sub = entry->d_type == DT_DIR;
    }
    CHECK(seen == 4 && saw_sub);
    if (dir != NULL) {
        rewinddir(dir);
        CHECK(readdir(dir) != NULL);
        CHECK(closedir(dir) == 0);
    }
    struct dirent **names = NULL;
    int count = scandir(GAME "/Sub", &names, NULL, alphasort);
    CHECK(count == 3);
    for (int i = 0; i < count; i++) free(names[i]);
    free(names);

    /* The game folder is read-only. */
    errno = 0;
    CHECK(fopen(GAME "/new.txt", "wb") == NULL && errno == EROFS);
    errno = 0;
    CHECK(open(GAME "/a.txt", O_WRONLY) == -1 && errno == EROFS);
    errno = 0;
    CHECK(unlink(GAME "/a.txt") == -1 && errno == EROFS);

    /* A working directory inside the game folder. */
    CHECK(chdir(GAME "/sub") == 0);
    char cwd[4096];
    CHECK(getcwd(cwd, sizeof cwd) != NULL && strcmp(cwd, GAME "/Sub") == 0);
    text = read_all("b.dat");
    CHECK(text != NULL && strcmp(text, "0123456789") == 0);
    text = read_all("../a.txt");
    CHECK(text != NULL && strcmp(text, "hello") == 0);
    char *resolved = realpath("b.dat", NULL);
    CHECK(resolved != NULL && strcmp(resolved, GAME "/Sub/B.DAT") == 0);
    free(resolved);
    /* Relative paths that leave the mount reach libc as absolute paths. */
    errno = 0;
    CHECK(fopen("../../elsewhere.txt", "rb") == NULL && errno == ENOENT);
    CHECK(chdir(base) == 0);
    CHECK(getcwd(cwd, sizeof cwd) != NULL && strcmp(cwd, base) == 0);
    text = read_all("game/a.txt");
    CHECK(text != NULL && strcmp(text, "hello") == 0);

    /* Writing to the save folder: invisible until closed. */
    FILE *f = fopen(SAVE "/slot1.sav", "wb");
    CHECK(f != NULL);
    if (f != NULL) {
        fputs("saved", f);
        errno = 0;
        CHECK(stat(SAVE "/slot1.sav", &info) == -1 && errno == ENOENT);
        CHECK(fclose(f) == 0);
    }
    snprintf(path, sizeof path, "%s/slot1.sav", save);
    text = read_all(path);
    CHECK(text != NULL && strcmp(text, "saved") == 0);
    CHECK(stat(SAVE "/slot1.sav", &info) == 0 && info.st_size == 5);

    /* Update in place keeps the bytes it does not touch; append appends. */
    f = fopen(SAVE "/OLD.SAV", "r+b");
    CHECK(f != NULL);
    if (f != NULL) {
        fseek(f, 2, SEEK_SET);
        fputs("XY", f);
        CHECK(fclose(f) == 0);
    }
    snprintf(path, sizeof path, "%s/old.sav", save);
    text = read_all(path);
    CHECK(text != NULL && strcmp(text, "abXYef") == 0);
    f = fopen(SAVE "/old.sav", "ab");
    CHECK(f != NULL);
    if (f != NULL) {
        fputs("!", f);
        CHECK(fclose(f) == 0);
    }
    text = read_all(path);
    CHECK(text != NULL && strcmp(text, "abXYef!") == 0);

    /* open/close write, the replace-by-rename save pattern, directories. */
    fd = open(SAVE "/tmp.bin", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    CHECK(fd >= 0);
    if (fd >= 0) {
        CHECK(write(fd, "new", 3) == 3);
        CHECK(close(fd) == 0);
    }
    CHECK(rename(SAVE "/tmp.bin", SAVE "/slot1.sav") == 0);
    snprintf(path, sizeof path, "%s/slot1.sav", save);
    text = read_all(path);
    CHECK(text != NULL && strcmp(text, "new") == 0);
    errno = 0;
    CHECK(stat(SAVE "/tmp.bin", &info) == -1 && errno == ENOENT);
    CHECK(mkdir(SAVE "/Profiles", 0755) == 0);
    errno = 0;
    CHECK(mkdir(SAVE "/profiles", 0755) == -1 && errno == EEXIST);
    f = fopen(SAVE "/Profiles/p.cfg", "w");
    CHECK(f != NULL);
    if (f != NULL) fclose(f);
    errno = 0;
    CHECK(rmdir(SAVE "/Profiles") == -1 && errno == ENOTEMPTY);
    CHECK(unlink(SAVE "/Profiles/p.cfg") == 0);
    CHECK(rmdir(SAVE "/Profiles") == 0);
    errno = 0;
    CHECK(stat(SAVE "/Profiles", &info) == -1 && errno == ENOENT);
    errno = 0;
    CHECK(rename(SAVE "/slot1.sav", GAME "/slot1.sav") == -1 && errno == EXDEV);
    errno = 0;
    CHECK(fopen(SAVE "/nofolder/x.sav", "wb") == NULL && errno == ENOENT);
    CHECK(remove(SAVE "/slot1.sav") == 0);

    /* Paths outside both mounts are libc's. */
    snprintf(path, sizeof path, "%s/outside.txt", base);
    write_real(path, "plain");
    text = read_all(path);
    CHECK(text != NULL && strcmp(text, "plain") == 0);

    /* exit() from an engine thread ends that thread and tells the host; the process goes on. */
    vfs_test_watch_exit();
    pthread_t quitter;
    CHECK(pthread_create(&quitter, NULL, engine_thread_that_quits, NULL) == 0);
    CHECK(pthread_join(quitter, NULL) == 0);
    CHECK(vfs_test_exit_status() == 7);

    if (g_failures == 0) printf("isolated_vfs: all checks passed\n");
    return g_failures == 0 ? 0 : 1;
}
