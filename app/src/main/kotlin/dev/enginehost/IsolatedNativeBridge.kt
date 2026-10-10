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
     * Loads every one of the plugin's libraries from its already-open
     * descriptor ([libraryFds], by the name System.loadLibrary would use)
     * and binds the plugin's native methods to [pluginClass] from
     * whichever library exports `enginehost_register_natives`. A library
     * whose dependency is another of the plugin's own libraries fails
     * until that one is loaded, so failures are retried for as long as
     * each round loads something. Answers whether the plugin's native
     * methods were bound.
     */
    fun loadPluginLibraries(libraryFds: Map<String, Int>, pluginClass: Class<*>): Boolean {
        if (!ready) return false
        var pending = libraryFds.toList()
        var bound = false
        while (pending.isNotEmpty()) {
            val failed = pending.filter { (name, fd) ->
                val status = runCatching { loadPluginLibrary0(fd, name, pluginClass) }
                    .onFailure { Log.w(TAG, "could not load lib$name.so from fd $fd", it) }
                    .getOrDefault(NOT_LOADED)
                if (status == LOADED_WITH_NATIVES) bound = true
                status == NOT_LOADED
            }
            if (failed.size == pending.size) {
                Log.e(TAG, "could not load ${failed.joinToString { "lib${it.first}.so" }}")
                break
            }
            pending = failed
        }
        return bound
    }

    private const val LOADED_WITH_NATIVES = 0
    private const val NOT_LOADED = -1

    @JvmStatic private external fun loadPluginLibrary0(libraryFd: Int, libraryName: String, pluginClass: Class<*>): Int
}
