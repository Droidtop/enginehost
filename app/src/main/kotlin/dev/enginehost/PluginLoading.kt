package dev.enginehost

import android.content.Context
import android.os.Build
import dev.enginehost.api.EnginePlugin
import java.io.File

internal class LoadedPlugin(val plugin: EnginePlugin, val resourceHandles: List<AutoCloseable>)

/**
 * Loads one engine bundle's dex and native libraries and constructs its
 * entry point -- the one mechanism RuntimeActivity (in-process, "runtime")
 * and IsolatedRuntimeService (isolated, "runtime_isolated") both use.
 * [bundleDirectory] must already be readable by the calling process: the
 * in-process runtime always can (it is this app's own UID); an isolated
 * runtime can only for a bundle EngineBundleInstaller made world-readable
 * because its manifest declared isolatable (docs/engine-bundle-format.md
 * "Sandboxing and the plugin contract").
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
    return loadFromDexPathList(
        context,
        dexPaths.joinToString(File.pathSeparator) { it.absolutePath },
        nativeLibraryPaths.joinToString(File.pathSeparator) { it.absolutePath }.ifBlank { null },
        emptyMap(),
        entrypointClass,
        appClassLoader,
        resourceHandles,
    )
}

/**
 * Sandbox layer 2 (docs/engine-sandbox.md): the same load, from
 * descriptors this process already holds open rather than real paths it
 * may not be able to reach by name at all. Android 10+ makes the app's
 * own private data directory 0700 -- world-executable ancestor
 * directories (PluginRegistry.root) close the gap on older releases but
 * cannot on 10+, since the isolated UID cannot even traverse into
 * `files/` in the first place there, regardless of what is readable at
 * the far end. The host opens each descriptor itself, against the
 * already hash-verified bundle (InstalledBundleVerifier, called before
 * either loading path runs), and this process converts each into a
 * `/proc/self/fd/N` path -- the same underlying open file, addressable
 * by a path string ordinary path-based APIs accept, without this process
 * ever resolving the real path itself. [nativeLibraryFdPaths] plugs into
 * [PluginDexLoader.findLibrary], so a plugin's own unmodified
 * `System.loadLibrary(name)` call (CatSystem2Plugin's static
 * initialiser, untouched) resolves it exactly as it would a real
 * directory search. No resource APKs: not part of this milestone's
 * shape (CatSystem2 ships none), and nothing in EngineBundlePackage's
 * own read of the manifest depends on this changing later.
 */
internal fun loadEnginePluginFromFds(
    context: Context,
    dexFdPaths: List<String>,
    entrypointClass: String,
    nativeLibraryFdPaths: Map<String, String>,
    appClassLoader: ClassLoader,
): LoadedPlugin = loadFromDexPathList(
    context,
    dexFdPaths.joinToString(File.pathSeparator),
    null,
    nativeLibraryFdPaths,
    entrypointClass,
    appClassLoader,
    emptyList(),
)

private fun loadFromDexPathList(
    context: Context,
    dexPath: String,
    librarySearchPath: String?,
    nativeLibraryFdPaths: Map<String, String>,
    entrypointClass: String,
    appClassLoader: ClassLoader,
    resourceHandles: List<AutoCloseable>,
): LoadedPlugin {
    // Not context.codeCacheDir: that resolves under
    // /data/user/0/<pkg>/code_cache, the APP UID's own private directory,
    // which an isolated launch's UID cannot create or reach at all -- the
    // exact same class of problem PluginRegistry.root() exists to solve
    // for files/ -- dq-sandbox-08 found a "ContextImpl: Failed to ensure
    // .../code_cache: mkdir failed: ENOENT" warning immediately before
    // ART's writable-dex rejection of a THIRD, never-sealed fd, and this
    // is the most likely source of it: this parameter is documented as
    // deprecated and inert since API 26, but a device where ART still
    // consults it at all, and falls back to an internal scratch file of
    // its own when the given directory cannot even be created, would
    // explain a THIRD fd that ownedCopy() never had a chance to seal --
    // it was never one of the descriptors this process opened.
    // context.cacheDir IS scoped per-UID even under isolation
    // (IsolatedEngineHost.cacheDirectory() already relies on exactly
    // this), so it exists and is writable for both launch shapes.
    val loader = PluginDexLoader(
        dexPath,
        context.cacheDir.absolutePath,
        librarySearchPath,
        appClassLoader,
        nativeLibraryFdPaths,
    )
    RuntimeClassLoader.attach(appClassLoader, loader)
    val entrypoint = Class.forName(entrypointClass, true, loader)
    require(EnginePlugin::class.java.isAssignableFrom(entrypoint)) {
        "$entrypointClass does not implement EnginePlugin"
    }
    val plugin = entrypoint.getDeclaredConstructor().newInstance() as EnginePlugin
    return LoadedPlugin(plugin, resourceHandles)
}
