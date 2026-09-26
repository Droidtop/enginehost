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
 * native methods -- [IsolatedNativeBridge], which `dlopen`s the library
 * directly (still by descriptor, via [ownedCopy]'s sealed memfd copy:
 * unlike the dex, this is a real `dlopen()` of a real path, still
 * subject to the SELinux denial reopening the bundle's own raw fd hit
 * in dq-sandbox-05, so this half keeps that fix) and `dlsym`s a single,
 * fixed, non-JNI-style entry point every isolatable plugin exports
 * (`enginehost_register_natives`, see `isolated_native_bridge.c`).
 * Sealing that copy is no longer load-bearing the way it was assumed to
 * be for the dex -- `dlopen` never consulted ART's writable-dex check
 * in the first place -- but it costs nothing to keep as a second,
 * independent hardening layer over the bundle's own code either way.
 */
internal fun loadEnginePluginFromInMemoryDex(
    dexBuffers: Array<ByteBuffer>,
    entrypointClass: String,
    nativeLibraryFdPaths: Map<String, String>,
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
    nativeLibraryFdPaths.values.forEach { path -> IsolatedNativeBridge.registerPluginNatives(path, entrypoint) }
    val plugin = entrypoint.getDeclaredConstructor().newInstance() as EnginePlugin
    return LoadedPlugin(plugin, emptyList())
}
