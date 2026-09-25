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
    val loader = PluginDexLoader(
        dexPaths.joinToString(File.pathSeparator) { it.absolutePath },
        context.codeCacheDir.absolutePath,
        nativeLibraryPaths.joinToString(File.pathSeparator) { it.absolutePath }.ifBlank { null },
        appClassLoader,
    )
    RuntimeClassLoader.attach(appClassLoader, loader)
    val entrypoint = Class.forName(entrypointClass, true, loader)
    require(EnginePlugin::class.java.isAssignableFrom(entrypoint)) {
        "$entrypointClass does not implement EnginePlugin"
    }
    val plugin = entrypoint.getDeclaredConstructor().newInstance() as EnginePlugin
    return LoadedPlugin(plugin, resourceHandles)
}
