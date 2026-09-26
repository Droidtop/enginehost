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
 * This is that bridge: dlopen the plugin's own .so (a /proc/self/fd
 * path, the same host-verified descriptor every launch shape uses) and
 * dlsym a single, fixed, non-JNI-style symbol name every isolatable
 * plugin exports -- enginehost_register_natives(JNIEnv*, jclass) --
 * calling it once with the plugin's own Class object, already resolved
 * correctly by ordinary Java reflection on the Kotlin side (no
 * FindClass, so no classloader-association ambiguity about which
 * loader's native-method table gets the binding). The plugin's own
 * implementation of that function does nothing but call
 * RegisterNatives with function pointers it already has at compile
 * time, so this file never needs to know anything about a given
 * plugin's own native methods.
 */
#include <dlfcn.h>
#include <jni.h>


typedef void (*enginehost_register_natives_fn)(JNIEnv *, jclass);

JNIEXPORT jboolean JNICALL
Java_dev_enginehost_IsolatedNativeBridge_registerPluginNatives0(
        JNIEnv *env, jclass type, jstring library_path, jclass plugin_class) {
    (void) type;
    const char *path = (*env)->GetStringUTFChars(env, library_path, NULL);
    void *handle = dlopen(path, RTLD_NOW);
    (*env)->ReleaseStringUTFChars(env, library_path, path);
    if (handle == NULL) return JNI_FALSE;
    void *symbol = dlsym(handle, "enginehost_register_natives");
    if (symbol == NULL) return JNI_FALSE;
    enginehost_register_natives_fn entry = (enginehost_register_natives_fn) symbol;
    entry(env, plugin_class);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}
