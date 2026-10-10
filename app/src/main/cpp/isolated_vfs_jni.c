/*
 * The isolated file layer's broker, over Binder: isolated_vfs.c's
 * struct ehvfs_broker calls, made through IsolatedVfs (Kotlin), which
 * holds the game and save folders' IEngineFileBroker binders. Engine
 * threads are attached to the VM on first use and stay attached (as
 * daemons) for the life of the process, which is the life of one launch.
 */
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "isolated_vfs.h"

#define ROOT_GAME 0
#define ROOT_SAVE 1

static JavaVM *g_vm;
static jclass g_class;
static jmethodID g_open_read, g_open_write, g_commit_write, g_remove, g_make_directory, g_rename, g_list;
static jfieldID g_listing_names, g_listing_info;

static JNIEnv *thread_env(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "enginehost-engine", NULL };
    if ((*g_vm)->AttachCurrentThreadAsDaemon(g_vm, &env, &args) != JNI_OK) return NULL;
    return env;
}

static int root_of(void *context) {
    return (int) (intptr_t) context;
}

/* Calls one of IsolatedVfs's (int root, String relative) -> int statics. */
static int call_path(jmethodID method, void *context, const char *relative) {
    JNIEnv *env = thread_env();
    if (env == NULL) return -EIO;
    jstring path = (*env)->NewStringUTF(env, relative);
    if (path == NULL) {
        (*env)->ExceptionClear(env);
        return -ENOMEM;
    }
    jint result = (*env)->CallStaticIntMethod(env, g_class, method, (jint) root_of(context), path);
    (*env)->DeleteLocalRef(env, path);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -EIO;
    }
    return (int) result;
}

static int broker_open_read(void *context, const char *relative) {
    return call_path(g_open_read, context, relative);
}

static int broker_open_write(void *context, const char *relative) {
    return call_path(g_open_write, context, relative);
}

static int broker_commit_write(void *context, const char *relative) {
    return call_path(g_commit_write, context, relative);
}

static int broker_remove(void *context, const char *relative) {
    return call_path(g_remove, context, relative);
}

static int broker_make_directory(void *context, const char *relative) {
    return call_path(g_make_directory, context, relative);
}

static int broker_rename(void *context, const char *from, const char *to) {
    JNIEnv *env = thread_env();
    if (env == NULL) return -EIO;
    jstring from_path = (*env)->NewStringUTF(env, from);
    jstring to_path = from_path != NULL ? (*env)->NewStringUTF(env, to) : NULL;
    if (to_path == NULL) {
        (*env)->ExceptionClear(env);
        if (from_path != NULL) (*env)->DeleteLocalRef(env, from_path);
        return -ENOMEM;
    }
    jint result = (*env)->CallStaticIntMethod(env, g_class, g_rename, (jint) root_of(context), from_path, to_path);
    (*env)->DeleteLocalRef(env, from_path);
    (*env)->DeleteLocalRef(env, to_path);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -EIO;
    }
    return (int) result;
}

static int broker_list(void *context, const char *relative, struct ehvfs_entry **entries, int *count) {
    JNIEnv *env = thread_env();
    if (env == NULL) return -EIO;
    if ((*env)->PushLocalFrame(env, 16) != JNI_OK) {
        (*env)->ExceptionClear(env);
        return -ENOMEM;
    }
    int result = 0;
    jstring path = (*env)->NewStringUTF(env, relative);
    jobject listing = path != NULL
        ? (*env)->CallStaticObjectMethod(env, g_class, g_list, (jint) root_of(context), path)
        : NULL;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        listing = NULL;
    }
    if (listing == NULL) {
        /* Not a directory the broker can list: absent, or a file. */
        (*env)->PopLocalFrame(env, NULL);
        return -ENOENT;
    }
    jobjectArray names = (jobjectArray) (*env)->GetObjectField(env, listing, g_listing_names);
    jlongArray info = (jlongArray) (*env)->GetObjectField(env, listing, g_listing_info);
    jsize n = names != NULL ? (*env)->GetArrayLength(env, names) : 0;
    if (info == NULL || (*env)->GetArrayLength(env, info) != n * 3) {
        (*env)->PopLocalFrame(env, NULL);
        return -EIO;
    }
    struct ehvfs_entry *out = calloc((size_t) (n > 0 ? n : 1), sizeof *out);
    jlong *details = n > 0 ? (*env)->GetLongArrayElements(env, info, NULL) : NULL;
    if (out == NULL || (n > 0 && details == NULL)) {
        free(out);
        (*env)->ExceptionClear(env);
        (*env)->PopLocalFrame(env, NULL);
        return -ENOMEM;
    }
    jsize filled = 0;
    for (; filled < n; filled++) {
        jstring name = (jstring) (*env)->GetObjectArrayElement(env, names, filled);
        const char *utf = name != NULL ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
        out[filled].name = utf != NULL ? strdup(utf) : NULL;
        if (utf != NULL) (*env)->ReleaseStringUTFChars(env, name, utf);
        if (name != NULL) (*env)->DeleteLocalRef(env, name);
        if (out[filled].name == NULL) {
            result = -ENOMEM;
            break;
        }
        out[filled].kind = details[3 * filled] == 2 ? EHVFS_KIND_DIRECTORY : EHVFS_KIND_FILE;
        out[filled].size = details[3 * filled + 1];
        out[filled].mtime_ms = details[3 * filled + 2];
    }
    if (details != NULL) (*env)->ReleaseLongArrayElements(env, info, details, JNI_ABORT);
    (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);
    if (result < 0) {
        for (jsize i = 0; i < filled; i++) free(out[i].name);
        free(out);
        return result;
    }
    *entries = out;
    *count = (int) n;
    return 0;
}

static int mount_root(JNIEnv *env, jstring root, int which, int writable) {
    if (root == NULL) return 0;
    const char *path = (*env)->GetStringUTFChars(env, root, NULL);
    if (path == NULL) return -ENOMEM;
    struct ehvfs_broker broker = {
        .context = (void *) (intptr_t) which,
        .open_read = broker_open_read,
        .open_write = broker_open_write,
        .commit_write = broker_commit_write,
        .remove = broker_remove,
        .make_directory = broker_make_directory,
        .rename = broker_rename,
        .list = broker_list,
    };
    int result = ehvfs_mount(path, &broker, writable);
    (*env)->ReleaseStringUTFChars(env, root, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_enginehost_IsolatedVfs_install0(JNIEnv *env, jclass local, jstring game_root, jboolean game_writable,
                                         jstring save_root) {
    if ((*env)->GetJavaVM(env, &g_vm) != JNI_OK) return -EIO;
    jclass listing = (*env)->FindClass(env, "dev/enginehost/runtime/BrokerListing");
    if (local == NULL || listing == NULL) return -EIO;
    g_class = (jclass) (*env)->NewGlobalRef(env, local);
    g_open_read = (*env)->GetStaticMethodID(env, g_class, "openRead", "(ILjava/lang/String;)I");
    g_open_write = (*env)->GetStaticMethodID(env, g_class, "openWrite", "(ILjava/lang/String;)I");
    g_commit_write = (*env)->GetStaticMethodID(env, g_class, "commitWrite", "(ILjava/lang/String;)I");
    g_remove = (*env)->GetStaticMethodID(env, g_class, "remove", "(ILjava/lang/String;)I");
    g_make_directory = (*env)->GetStaticMethodID(env, g_class, "makeDirectory", "(ILjava/lang/String;)I");
    g_rename = (*env)->GetStaticMethodID(env, g_class, "rename", "(ILjava/lang/String;Ljava/lang/String;)I");
    g_list = (*env)->GetStaticMethodID(env, g_class, "list",
                                       "(ILjava/lang/String;)Ldev/enginehost/runtime/BrokerListing;");
    g_listing_names = (*env)->GetFieldID(env, listing, "names", "[Ljava/lang/String;");
    g_listing_info = (*env)->GetFieldID(env, listing, "info", "[J");
    if ((*env)->ExceptionCheck(env) || g_class == NULL) return -EIO;
    int result = mount_root(env, game_root, ROOT_GAME, game_writable == JNI_TRUE);
    if (result == 0) result = mount_root(env, save_root, ROOT_SAVE, 1);
    return result;
}
