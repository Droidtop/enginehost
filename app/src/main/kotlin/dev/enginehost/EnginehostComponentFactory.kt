package dev.enginehost

import android.app.Activity
import android.app.AppComponentFactory
import android.content.Intent
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
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
        installResources(context, installed)
        val resourceApks = installed.resourceApks.map {
            safeRuntimeChild(installed.directory.canonicalFile, it)
        }
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

    private fun installResources(context: EnginehostApplication, installed: InstalledPlugin) {
        val root = installed.directory.canonicalFile
        installed.resourceApks.map { safeRuntimeChild(root, it) }.forEach { apk ->
            require(apk.isFile) { "A signed runtime resource APK is missing" }
            if (Build.VERSION.SDK_INT >= 30) {
                val descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
                val provider = ResourcesProvider.loadFromApk(descriptor)
                context.resources.addLoaders(ResourcesLoader().apply { addProvider(provider) })
                // Providers and descriptors intentionally live for this short-lived runtime process.
            } else {
                val method = context.resources.assets.javaClass.getMethod("addAssetPath", String::class.java)
                require((method.invoke(context.resources.assets, apk.path) as Int) != 0)
            }
        }
    }

    companion object {
        private const val TAG = "EnginehostRuntime"
        const val EXTRA_RESOURCE_APKS = "dev.enginehost.runtime.RESOURCE_APKS"
        const val EXTRA_RESOURCE_PACKAGE = "dev.enginehost.runtime.RESOURCE_PACKAGE"
        const val EXTRA_BUNDLE_ROOT = "dev.enginehost.runtime.BUNDLE_ROOT"
    }
}
