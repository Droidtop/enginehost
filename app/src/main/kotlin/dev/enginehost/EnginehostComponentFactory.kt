package dev.enginehost

import android.app.Activity
import android.app.AppComponentFactory
import android.content.Intent
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Constructs an Android frontend carried inside a verified engine bundle.
 * The component remains Enginehost-owned: no plugin package is installed and
 * the frontend executes under Enginehost's UID and isolated runtime process.
 */
class EnginehostComponentFactory : AppComponentFactory() {
    override fun instantiateActivity(
        classLoader: ClassLoader,
        className: String,
        intent: Intent?,
    ): Activity {
        if (className != BundledActivityProxy::class.java.name || intent == null) {
            return super.instantiateActivity(classLoader, className, intent)
        }
        val context = EnginehostApplication.instance
        val bundleId = intent.getStringExtra(RuntimeActivity.EXTRA_PLUGIN_BUNDLE)
            ?: error("Bundled runtime omitted its bundle ID")
        val installed = PluginRegistry.discover(context).firstOrNull { it.bundleId == bundleId }
            ?: error("The selected engine bundle is not installed")
        require(installed.runtimeTransport == RUNTIME_TRANSPORT_ACTIVITY)
        require(PluginTrustStore(context).isApproved(installed)) { "Engine bundle approval is missing" }
        val manifest = InstalledBundleVerifier.verify(context, installed)
        require(manifest.entrypoint == installed.entrypointClass)
        Log.i(TAG, "Loading bundle=$bundleId entrypoint=${installed.entrypointClass}")
        val resourceApks = installed.resourceApks.map {
            safeRuntimeChild(installed.directory.canonicalFile, it)
        }
        // Retained for the life of this short-lived :runtime process. The
        // descriptors must outlive the call: a collected ParcelFileDescriptor
        // closes the file the loaded resources are still reading from.
        retainedResources += PluginResources.attach(context, resourceApks)
        intent.putStringArrayListExtra(EXTRA_RESOURCE_APKS, ArrayList(resourceApks.map(File::getPath)))
        Log.i(TAG, "Runtime resource APKs=${resourceApks.joinToString { it.path }}")
        resourceApks.firstOrNull()?.let { apk ->
            context.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName?.let {
                intent.putExtra(EXTRA_RESOURCE_PACKAGE, it)
            }
        }

        val root = installed.directory.canonicalFile
        intent.putExtra(EXTRA_BUNDLE_ROOT, root.path)
        val dex = installed.dexFiles.map { safeRuntimeChild(root, it) }
        require(dex.all(File::isFile)) { "A signed runtime dex file is missing" }
        val nativePaths = Build.SUPPORTED_ABIS.map { File(root, "lib/$it") }.filter(File::isDirectory)
        val loader = DexClassLoader(
            dex.joinToString(File.pathSeparator) { it.path },
            context.codeCacheDir.path,
            nativePaths.joinToString(File.pathSeparator) { it.path }.ifBlank { null },
            classLoader,
        )
        val runtime = Class.forName(installed.entrypointClass, true, loader)
        require(Activity::class.java.isAssignableFrom(runtime)) { "Bundle entrypoint is not an Activity" }
        @Suppress("UNCHECKED_CAST")
        return (runtime as Class<out Activity>).getDeclaredConstructor().newInstance().also {
            Log.i(TAG, "Instantiated runtime activity=${it.javaClass.name} loader=${it.javaClass.classLoader}")
        }
    }

    companion object {
        private val retainedResources = mutableListOf<AutoCloseable>()
        private const val TAG = "EnginehostRuntime"
        const val EXTRA_RESOURCE_APKS = "dev.enginehost.runtime.RESOURCE_APKS"
        const val EXTRA_RESOURCE_PACKAGE = "dev.enginehost.runtime.RESOURCE_PACKAGE"
        const val EXTRA_BUNDLE_ROOT = "dev.enginehost.runtime.BUNDLE_ROOT"
    }
}
