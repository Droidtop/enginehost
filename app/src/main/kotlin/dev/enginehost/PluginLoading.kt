package dev.enginehost

import android.content.Context
import android.os.Build
import dalvik.system.InMemoryDexClassLoader
import dev.enginehost.api.EnginePlugin
import java.io.File
import java.nio.ByteBuffer

internal class LoadedPlugin(val plugin: EnginePlugin, val resourceHandles: List<AutoCloseable>)

/**
 * Loads one engine bundle's dex and native libraries and constructs its
 * entry point, for an in-process ("runtime") launch -- [bundleDirectory]
 * must already be readable by the calling process, true for this app's
 * own UID. Isolated launches use [loadEnginePluginFromInMemoryDex]
 * instead, a genuinely different mechanism (docs/engine-sandbox.md
 * "Audio", the InMemoryDexClassLoader pivot) rather than a variant of
 * this one: Android 14's "safer dynamic code loading" refuses to load
 * ANY path-based dex file this process itself could write to, seals or
 * no seals (dq-sandbox-09 confirmed this directly), which only a
 * genuinely file-less loader avoids.
 */
internal fun loadEnginePlugin(
    context: Context,
    bundleDirectory: File,
    entrypointClass: String,
    dexFiles: List<String>,
    resourceApks: List<String>,
    appClassLoader: ClassLoader,
): LoadedPlugin {
    val root = bundleDirectory.canonicalFile
    val resourceHandles = PluginResources.attach(context, resourceApks.map { safeRuntimeChild(root, it) })
    val dexPaths = dexFiles.map { safeRuntimeChild(root, it) }
    require(dexPaths.all(File::isFile)) { "A signed dex file is missing" }
    val nativeLibraryPaths = Build.SUPPORTED_ABIS.map { File(root, "lib/$it") }.filter(File::isDirectory)
    val dexPath = dexPaths.joinToString(File.pathSeparator) { it.absolutePath }
    val librarySearchPath = nativeLibraryPaths.joinToString(File.pathSeparator) { it.absolutePath }.ifBlank { null }
    // context.cacheDir, not codeCacheDir: this parameter has had no
    // effect since API 26 regardless (DexClassLoader's own reference
    // page), so any real, writable directory satisfies it.
    val loader = PluginDexLoader(dexPath, context.cacheDir.absolutePath, librarySearchPath, appClassLoader)
    RuntimeClassLoader.attach(appClassLoader, loader)
    val entrypoint = Class.forName(entrypointClass, true, loader)
    require(EnginePlugin::class.java.isAssignableFrom(entrypoint)) {
        "$entrypointClass does not implement EnginePlugin"
    }
    val plugin = entrypoint.getDeclaredConstructor().newInstance() as EnginePlugin
    return LoadedPlugin(plugin, resourceHandles)
}

/**
 * Sandbox layer 2 (docs/engine-sandbox.md "Audio", "the InMemoryDexClassLoader
 * pivot"): the isolated launch's own loading mechanism, genuinely
 * different from [loadEnginePlugin] rather than a variant of it.
 *
 * dq-sandbox-04 through dq-sandbox-09 found, in escalating detail, that
 * Android 14's "safer dynamic code loading"
 * (https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading,
 * gated on the app's targetSdk, not just the device's API level) refuses
 * to load ANY dex file by path that this process itself could have
 * written -- checked by the file's own permission bits, independent of
 * `F_SEAL_WRITE` or the specific fd's own open flags (dq-sandbox-09:
 * the seal was confirmed genuinely set, and ART rejected it anyway).
 * Since a memfd this process creates is unavoidably owner-writable by
 * Unix permission bits, and SELinux separately refuses to `fchmod` it
 * read-only on this app's isolated domain (dq-sandbox-07), no
 * path-based dex file this process can make for itself will ever
 * satisfy this check. [InMemoryDexClassLoader] sidesteps it entirely:
 * there is no path and no file, only bytes already read from the
 * host-verified descriptor Binder handed over directly (no reopen, so
 * no separate permission check either) into a plain [ByteBuffer].
 *
 * The cost: [InMemoryDexClassLoader] is `final` and cannot override
 * `findLibrary` the way [PluginDexLoader] does for the in-process path,
 * so a plugin's native library needs a different way to bind its own
 * native methods -- [IsolatedNativeBridge], which loads the library via
 * `android_dlopen_ext`/`ANDROID_DLEXT_USE_LIBRARY_FD` directly on
 * [ownedCopy]'s sealed memfd copy's own fd, never a path: dq-sandbox-10
 * found that even `dlopen()`ing a `/proc/self/fd/N` *path* is itself a
 * fresh `open()` SELinux re-checks against the underlying file -- the
 * exact class of denial dq-sandbox-05 found reopening the bundle's own
 * raw fd for the dex, now hit for the native library's own memfd copy
 * instead, once the dex half of that problem was fixed and this became
 * the next thing standing in the way. `ANDROID_DLEXT_USE_LIBRARY_FD`
 * reads the already-open fd's own bytes directly, with no second `open()`
 * at all, mirroring the dex's own "no reopen" reasoning above rather
 * than reintroducing the same class of bug one call site over. It then
 * `dlsym`s a single, fixed, non-JNI-style entry point every isolatable
 * plugin exports (`enginehost_register_natives`, see
 * `isolated_native_bridge.c`). Sealing that copy is no longer
 * load-bearing the way it was assumed to be for the dex -- neither
 * `dlopen` nor `android_dlopen_ext` ever consulted ART's writable-dex
 * check in the first place -- but it costs nothing to keep as a second,
 * independent hardening layer over the bundle's own code either way.
 */
internal fun loadEnginePluginFromInMemoryDex(
    dexBuffers: Array<ByteBuffer>,
    entrypointClass: String,
    nativeLibraryFds: Map<String, Int>,
    appClassLoader: ClassLoader,
): LoadedPlugin {
    val loader = InMemoryDexClassLoader(dexBuffers, appClassLoader)
    // Not initialized yet (initialize = false): the plugin's own static
    // initialiser may call System.loadLibrary, which always fails under
    // this loader (no findLibrary) -- CatSystem2Plugin already catches
    // and ignores that failure, but instantiation below is what actually
    // triggers the static initialiser, and native methods should be
    // bound before then regardless of what a plugin's own static block
    // does or does not do.
    val entrypoint = Class.forName(entrypointClass, false, loader)
    require(EnginePlugin::class.java.isAssignableFrom(entrypoint)) {
        "$entrypointClass does not implement EnginePlugin"
    }
    // Raw fd numbers, not paths: dq-sandbox-10 found dlopen()ing a
    // /proc/self/fd path is itself a fresh open() SELinux re-checks
    // against the underlying file (the same class of denial dq-sandbox-05
    // found for the dex) -- IsolatedNativeBridge uses
    // android_dlopen_ext/ANDROID_DLEXT_USE_LIBRARY_FD on the fd directly
    // instead, needing no second open() at all.
    nativeLibraryFds.values.forEach { fd -> IsolatedNativeBridge.registerPluginNatives(fd, entrypoint) }
    val plugin = entrypoint.getDeclaredConstructor().newInstance() as EnginePlugin
    return LoadedPlugin(plugin, emptyList())
}
