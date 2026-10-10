/*
 * End-to-end test of sbx (app/src/main/cpp/sbx): a child process locks
 * itself down against a broker in the parent and tries what an engine
 * would, allowed and not. Runs on the build host's kernel; the same code
 * runs on the device in Enginehost's :runtime.
 */
#define _GNU_SOURCE
#include "sbx/sbx.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef __NR_statx
#define __NR_statx 332
#endif

static int failures;

#define EXPECT(condition, what)                                                         \
    do {                                                                                \
        if (condition) printf("ok   %s\n", what);                                       \
        else { printf("FAIL %s (errno %d %s)\n", what, errno, strerror(errno)); failures++; } \
    } while (0)

static char root[256], game[300], save[300], secret[300];

static int refused(int result) { return result < 0 && errno == EACCES; }

static void write_file(const char *path, const char *text) {
    FILE *f = fopen(path, "w");
    if (!f) { perror(path); exit(2); }
    fputs(text, f);
    fclose(f);
}

static void *thread_open(void *argument) {
    char path[400];
    snprintf(path, sizeof(path), "%s/data.txt", game);
    int fd = open(path, O_RDONLY);
    *(int *) argument = fd >= 0;
    if (fd >= 0) close(fd);
    return NULL;
}

static void confined(int broker) {
    int locked = sbx_lockdown(broker);
    errno = -locked;
    EXPECT(locked == 0, "lockdown installed on every thread");
    if (locked != 0) return;
    char path[512], path2[512], buffer[512];

    snprintf(path, sizeof(path), "%s/data.txt", game);
    int fd = open(path, O_RDONLY);
    EXPECT(fd >= 0, "game file opens for reading");
    if (fd >= 0) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        EXPECT(n == 5 && memcmp(buffer, "hello", 5) == 0, "game file reads through the brokered descriptor");
        close(fd);
    }
    EXPECT(refused(open(path, O_WRONLY)), "game file refused for writing");
    struct stat st;
    EXPECT(stat(path, &st) == 0 && st.st_size == 5, "stat of a game file");
    fd = open("data.txt", O_RDONLY);
    EXPECT(fd >= 0, "relative path from the starting directory");
    if (fd >= 0) close(fd);
    int dir = open(game, O_RDONLY | O_DIRECTORY);
    EXPECT(dir >= 0, "game folder opens as a directory");
    if (dir >= 0) {
        fd = openat(dir, "data.txt", O_RDONLY);
        EXPECT(fd >= 0, "openat relative to a brokered directory");
        if (fd >= 0) close(fd);
        EXPECT(fstat(dir, &st) == 0 && S_ISDIR(st.st_mode), "fstat on a descriptor is untouched");
        close(dir);
    }
    DIR *listing = opendir(game);
    int seen = 0;
    if (listing) {
        struct dirent *entry;
        while ((entry = readdir(listing))) if (strcmp(entry->d_name, "data.txt") == 0) seen = 1;
        closedir(listing);
    }
    EXPECT(seen, "game folder lists");

    snprintf(path, sizeof(path), "%s/key", secret);
    EXPECT(refused(open(path, O_RDONLY)), "file outside the policy refused");
    EXPECT(refused(stat(path, &st)), "stat outside the policy refused");
    snprintf(path, sizeof(path), "%s/../secret/key", game);
    EXPECT(refused(open(path, O_RDONLY)), "\"..\" out of the game folder refused");
    snprintf(path, sizeof(path), "%s/escape", game);
    EXPECT(refused(open(path, O_RDONLY)), "symlink out of the game folder refused");
    EXPECT(stat(root, &st) == 0, "an ancestor of a rule can be stat()ed");
    EXPECT(opendir(root) == NULL && errno == EACCES, "an ancestor of a rule cannot be listed");
    EXPECT(refused(open("/etc/passwd", O_RDONLY)), "/etc/passwd refused");

    snprintf(path, sizeof(path), "%s/s.txt", save);
    FILE *f = fopen(path, "w");
    EXPECT(f != NULL, "save file created");
    if (f) {
        fputs("saved", f);
        fclose(f);
    }
    snprintf(path2, sizeof(path2), "%s/t.txt", save);
    EXPECT(rename(path, path2) == 0, "rename inside the save folder");
    snprintf(path, sizeof(path), "%s/d", save);
    EXPECT(mkdir(path, 0700) == 0 && rmdir(path) == 0, "mkdir and rmdir inside the save folder");
    EXPECT(unlink(path2) == 0, "unlink inside the save folder");
    snprintf(path, sizeof(path), "%s/new.txt", game);
    EXPECT(refused(open(path, O_WRONLY | O_CREAT, 0600)), "create in the read-only game folder refused");
    snprintf(path, sizeof(path), "%s/moved", secret);
    snprintf(path2, sizeof(path2), "%s/t.txt", save);
    write_file(path2, "x");
    EXPECT(refused(rename(path2, path)), "rename out of the save folder refused");

    ssize_t n = readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
    EXPECT(n > 0, "readlink /proc/self/exe");
    fd = open("/proc/self/status", O_RDONLY);
    EXPECT(fd >= 0, "own /proc/self/status");
    if (fd >= 0) close(fd);
    snprintf(path, sizeof(path), "/proc/%d/status", getppid());
    EXPECT(refused(open(path, O_RDONLY)), "another process's /proc entry refused");
    fd = open("/dev/null", O_WRONLY);
    EXPECT(fd >= 0, "an allowed device opens read-write");
    if (fd >= 0) close(fd);

    EXPECT(refused(socket(AF_INET, SOCK_STREAM, 0)), "socket(AF_INET) refused");
    EXPECT(refused(socket(AF_UNIX, SOCK_STREAM, 0)), "socket(AF_UNIX) refused");
    int pair[2];
    EXPECT(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "socketpair still works");
    char *argv[] = {"/bin/true", NULL};
    EXPECT(refused(execve("/bin/true", argv, NULL)), "execve refused");
    EXPECT(refused(chdir("/")), "chdir refused");
    errno = 0;
    EXPECT(syscall(__NR_statx, AT_FDCWD, "data.txt", 0, 0x7ff, buffer) < 0 && errno == ENOSYS,
           "statx answers ENOSYS so callers fall back");

    int from_thread = 0;
    pthread_t thread;
    pthread_create(&thread, NULL, thread_open, &from_thread);
    pthread_join(thread, NULL);
    EXPECT(from_thread, "a thread started after lockdown is confined and brokered too");
}

int main(void) {
    snprintf(root, sizeof(root), "%s/sbx-test-XXXXXX", getenv("TMPDIR") ? getenv("TMPDIR") : "/tmp");
    if (!mkdtemp(root)) { perror("mkdtemp"); return 2; }
    snprintf(game, sizeof(game), "%s/game", root);
    snprintf(save, sizeof(save), "%s/save", root);
    snprintf(secret, sizeof(secret), "%s/secret", root);
    mkdir(game, 0700);
    mkdir(save, 0700);
    mkdir(secret, 0700);
    char path[400];
    snprintf(path, sizeof(path), "%s/data.txt", game);
    write_file(path, "hello");
    snprintf(path, sizeof(path), "%s/key", secret);
    write_file(path, "secret");
    char link[400];
    snprintf(link, sizeof(link), "%s/escape", game);
    if (symlink(path, link) != 0) { perror("symlink"); return 2; }

    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, pair) != 0) { perror("socketpair"); return 2; }
    struct sbx_policy *policy = sbx_policy_new();
    sbx_policy_add(policy, game, SBX_READ);
    sbx_policy_add(policy, save, SBX_READ | SBX_WRITE);
    sbx_policy_add(policy, "/proc", SBX_READ);
    sbx_policy_add(policy, "/dev/null", SBX_READ | SBX_WRITE);
    /* The broker must ask for credentials before the first request is sent. */
    int on = 1;
    setsockopt(pair[0], SOL_SOCKET, SO_PASSCRED, &on, sizeof(on));

    fflush(stdout);
    pid_t child = fork();
    if (child == 0) {
        close(pair[0]);
        if (chdir(game) != 0) _exit(2);
        confined(pair[1]);
        fflush(stdout);
        _exit(failures ? 1 : 0);
    }
    close(pair[1]);
    sbx_broker_serve(policy, pair[0]);
    int status = 0;
    waitpid(child, &status, 0);
    if (WIFSIGNALED(status)) {
        printf("FAIL confined process died with signal %d\n", WTERMSIG(status));
        return 1;
    }
    return WEXITSTATUS(status);
}
