package dev.enginehost

import android.util.Log

/**
 * The isolated launch's native-method binding seam (docs/engine-sandbox.md
 * "Audio", the InMemoryDexClassLoader pivot; `isolated_native_bridge.c`
 * has the full reasoning). [InMemoryDexClassLoader] cannot override
 * `findLibrary`, so a plugin's own `System.loadLibrary` call always
 * fails under isolation and its native methods are never auto-bound the
 * ordinary way. This loads the plugin's own `.so` directly by its own
 * already-open fd (`android_dlopen_ext`/`ANDROID_DLEXT_USE_LIBRARY_FD`,
 * never a path -- dq-sandbox-10 found even a `/proc/self/fd` *path*
 * reopen hits the same SELinux denial dq-sandbox-05 found for the dex)
 * and asks it to bind its own native methods via a single, fixed,
 * non-JNI-style entry point every isolatable plugin exports:
 * `enginehost_register_natives(JNIEnv*, jclass)`.
 */
internal object IsolatedNativeBridge {
    private const val TAG = "enginehost-isolated-runtime"

    /** Loads `enginehost_sandbox` once; shares [RuntimeSandbox]'s own library, already proven loadable in this process. */
    private val ready: Boolean = runCatching { System.loadLibrary("enginehost_sandbox") }
        .onFailure { Log.w(TAG, "isolated native bridge unavailable: the sandbox library did not load", it) }
        .isSuccess

    /**
     * `true` when the plugin's own native methods are now bound to
     * [pluginClass] and safe to call; `false` when the library (opened
     * from [libraryFd], an already-open descriptor this process itself
     * holds -- no path, no second `open()`) or its
     * `enginehost_register_natives` entry point could not be found, in
     * which case the plugin's own native methods remain unbound and any
     * call into them will fail with the ordinary
     * [UnsatisfiedLinkError] that implies.
     */
    fun registerPluginNatives(libraryFd: Int, pluginClass: Class<*>): Boolean {
        if (!ready) return false
        return runCatching { registerPluginNatives0(libraryFd, pluginClass) }
            .onFailure { Log.w(TAG, "could not register $pluginClass's native methods from fd $libraryFd", it) }
            .getOrDefault(false)
    }

    @JvmStatic private external fun registerPluginNatives0(libraryFd: Int, pluginClass: Class<*>): Boolean
}
