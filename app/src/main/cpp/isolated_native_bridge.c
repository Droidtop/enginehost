/*
 * Sandbox layer 2's isolated-launch native seam (docs/engine-sandbox.md
 * "Audio", the InMemoryDexClassLoader pivot). Android 14's "safer
 * dynamic code loading" makes ART refuse to load a dex file by path if
 * the underlying file can be written by this process at all -- true of
 * any memfd this process created and sealed non-writable (F_SEAL_WRITE
 * stops write(2), it does not touch the file's own permission bits,
 * which ART's check is understood to actually inspect; dq-sandbox-09
 * confirmed the seal itself is not what the check looks at), and
 * SELinux separately denies fchmod-ing that memfd read-only on this
 * app's isolated domain (dq-sandbox-07). IsolatedRuntimeService.kt
 * loads the plugin's dex via InMemoryDexClassLoader instead, which
 * needs no path and so is not subject to this check at all -- but
 * being `final`, it cannot override findLibrary the way the in-process
 * launch's own loader does, so a plugin's native library needs a
 * different way to bind its own native methods once loaded under
 * isolation.
 *
 * This is that bridge: load the plugin's own .so and dlsym a single,
 * fixed, non-JNI-style symbol name every isolatable plugin exports --
 * enginehost_register_natives(JNIEnv*, jclass) -- calling it once with
 * the plugin's own Class object, already resolved correctly by
 * ordinary Java reflection on the Kotlin side (no FindClass, so no
 * classloader-association ambiguity about which loader's native-method
 * table gets the binding). The plugin's own implementation of that
 * function does nothing but call RegisterNatives with function
 * pointers it already has at compile time, so this file never needs to
 * know anything about a given plugin's own native methods.
 *
 * The library itself is loaded by fd, not by path: dq-sandbox-10 found
 * that dlopen()ing a /proc/self/fd/<N> PATH is itself a fresh open()
 * the kernel and SELinux re-check against the underlying file -- the
 * same class of denial dq-sandbox-05 found reopening the bundle's own
 * raw fd for the dex, now hit for the native library's own sealed
 * memfd copy once the dex half of that problem was fixed and this
 * became the next thing standing in the way. android_dlopen_ext with
 * ANDROID_DLEXT_USE_LIBRARY_FD reads the already-open fd's own bytes
 * directly, with no second open() at all -- the same "no reopen"
 * reasoning IsolatedRuntimeService.kt already applies to the dex
 * itself, applied here to the one remaining path-based open in this
 * whole launch sequence.
 *
 * Each library is loaded under its own name ("lib<name>.so"), so a
 * plugin that ships its engine in more than one library (an engine
 * linked against a separate libSDL2.so) can have the second one's
 * DT_NEEDED entry satisfied by the first, already loaded under that
 * soname; IsolatedNativeBridge retries libraries whose dependencies were
 * not loaded yet. Every library that carries the isolated file layer's
 * forwarder (plugin-native/enginehost_vfs_forward.c) is bound to the
 * host's table (isolated_vfs.c) as it loads.
 */
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

#include "isolated_vfs.h"

typedef void (*enginehost_register_natives_fn)(JNIEnv *, jclass);
typedef void (*enginehost_vfs_bind_fn)(const struct enginehost_vfs_table *);

#define LOADED_WITH_NATIVES 0
#define LOADED 1
#define NOT_LOADED (-1)

JNIEXPORT jint JNICALL
Java_dev_enginehost_IsolatedNativeBridge_loadPluginLibrary0(
        JNIEnv *env, jclass type, jint library_fd, jstring library_name, jclass plugin_class) {
    (void) type;
    const char *name = (*env)->GetStringUTFChars(env, library_name, NULL);
    if (name == NULL) return NOT_LOADED;
    char soname[256];
    snprintf(soname, sizeof soname, "lib%s.so", name);
    (*env)->ReleaseStringUTFChars(env, library_name, name);
    android_dlextinfo info;
    memset(&info, 0, sizeof info);
    info.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    info.library_fd = (int) library_fd;
    /* The name is the library's identity for DT_NEEDED matching only;
       every byte comes from info.library_fd, never from a path. */
    void *handle = android_dlopen_ext(soname, RTLD_NOW, &info);
    if (handle == NULL) {
        const char *error = dlerror();
        __android_log_print(ANDROID_LOG_INFO, "enginehost-isolated-runtime", "%s not loaded yet: %s",
                            soname, error != NULL ? error : "unknown error");
        return NOT_LOADED;
    }
    enginehost_vfs_bind_fn bind = (enginehost_vfs_bind_fn) dlsym(handle, "enginehost_vfs_bind");
    if (bind != NULL) {
        bind(ehvfs_table());
        __android_log_print(ANDROID_LOG_INFO, "enginehost-isolated-runtime",
                            "%s reaches game and save files through the host", soname);
    }
    enginehost_register_natives_fn entry =
        (enginehost_register_natives_fn) dlsym(handle, "enginehost_register_natives");
    if (entry == NULL) return LOADED;
    entry(env, plugin_class);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return LOADED;
    }
    return LOADED_WITH_NATIVES;
}
