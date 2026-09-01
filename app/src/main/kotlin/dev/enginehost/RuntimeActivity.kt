package dev.enginehost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import dalvik.system.DexClassLoader
import dev.enginehost.api.EngineFileSystem
import dev.enginehost.api.EngineHost
import dev.enginehost.api.EnginePlugin
import dev.enginehost.api.EnginePluginSession
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Host-owned execution boundary; plugin components are never started or bound. */
class RuntimeActivity : FragmentActivity() {
    private var plugin: EnginePlugin? = null
    private var runtimeStarted = false
    private var pendingRequiredFile: String? = null
    private val resourceHandles = mutableListOf<AutoCloseable>()
    // Scoped to the engine this session is running, so the user's
    // per-engine mappings apply while playing rather than only in settings.
    private val controllers by lazy {
        RuntimeControllerRouter(this, intent.getStringExtra(EXTRA_ENGINE)) { plugin }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val display = FrameLayout(this)
        setContentView(display)
        val gameFolder = intent.getStringExtra(EXTRA_PATH)?.let(::File)
            ?: return failAndFinish("Runtime launch omitted the game path")
        val expectedBundle = intent.getStringExtra(EXTRA_PLUGIN_BUNDLE)
            ?: return failAndFinish("Runtime launch omitted the selected plugin")
        val config = try {
            EngineConfigReader.resolve(gameFolder, intent.getStringExtra(EXTRA_CALLER_CONFIG))
        } catch (e: InvalidEngineConfigException) {
            return failAndFinish(e.message ?: "Invalid $CONFIG_FILE_NAME")
        }
        val resolved = PluginRegistry.resolve(
            this, config.engine, config.engineContext, config.engineVersion,
            config.runtimeRequirements, config.pluginVersionConstraint,
        ) ?: return failAndFinish("The selected plugin is no longer compatible or installed")
        if (resolved.plugin.bundleId != expectedBundle) {
            return failAndFinish("Plugin resolution changed before runtime startup; retry the launch")
        }
        if (!PluginTrustStore(this).isApproved(resolved.plugin)) {
            return failAndFinish("Plugin approval is missing")
        }

        try {
            val verifiedManifest = InstalledBundleVerifier.verify(this, resolved.plugin)
            val instance = loadPlugin(resolved.plugin)
            val host = RuntimeHost(this, gameFolder, resolved.plugin.bundleId)
            instance.onCreate(
                EnginePluginSession(
                    resolved.plugin.directory, display, host, gameFolder.absolutePath, config.engine,
                    config.engineContext ?: DEFAULT_ENGINE_CONTEXT, config.engineVersion.toString(),
                    resolved.capability.runtimeVersion.toString(), resolved.capability.id,
                    config.execFile, config.options?.toString(),
                    config.runtimeRequirements.mapValues { it.value.toString() },
                ),
            )
            check(verifiedManifest.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION)
            plugin = instance
            runtimeStarted = true
        } catch (e: dev.enginehost.api.EnginePatchRequiredException) {
            // The module recognised its own "content I cannot read"
            // failure. The user is told only that a patch is needed --
            // not which file -- and supplies one themselves.
            Log.w(TAG, "Plugin reported a required patch", e)
            offerPatch(e.requiredFile())
        } catch (e: Throwable) {
            Log.e(TAG, "Plugin startup failed", e)
            failAndFinish("Plugin startup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onStart() {
        super.onStart()
        if (runtimeStarted) callPlugin("start") { onStart() }
    }

    private fun loadPlugin(installed: InstalledPlugin): EnginePlugin {
        val root = installed.directory.canonicalFile
        installPluginResources(root, installed.resourceApks)
        val dexPaths = installed.dexFiles.map { safeRuntimeChild(root, it) }
        require(dexPaths.all(File::isFile)) { "A signed dex file is missing" }
        val nativeLibraryPaths = Build.SUPPORTED_ABIS.map { File(root, "lib/$it") }.filter(File::isDirectory)
        val loader = DexClassLoader(
            dexPaths.joinToString(File.pathSeparator) { it.absolutePath },
            codeCacheDir.absolutePath,
            nativeLibraryPaths.joinToString(File.pathSeparator) { it.absolutePath }.ifBlank { null },
            classLoader,
        )
        val entrypoint = Class.forName(installed.entrypointClass, true, loader)
        require(EnginePlugin::class.java.isAssignableFrom(entrypoint)) {
            "${installed.entrypointClass} does not implement EnginePlugin API v${installed.apiVersion}"
        }
        return entrypoint.getDeclaredConstructor().newInstance() as EnginePlugin
    }

    private fun installPluginResources(root: File, paths: List<String>) {
        paths.map { safeRuntimeChild(root, it) }.forEach { apk ->
            require(apk.isFile) { "A signed plugin resource APK is missing" }
            if (Build.VERSION.SDK_INT >= 30) {
                val descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
                val provider = ResourcesProvider.loadFromApk(descriptor)
                val loader = ResourcesLoader().apply { addProvider(provider) }
                resources.addLoaders(loader)
                resourceHandles += provider
                resourceHandles += descriptor
            } else {
                val method = resources.assets.javaClass.getMethod("addAssetPath", String::class.java)
                require((method.invoke(resources.assets, apk.absolutePath) as Int) != 0) {
                    "Could not attach plugin resources"
                }
                @Suppress("DEPRECATION") resources.updateConfiguration(resources.configuration, resources.displayMetrics)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (runtimeStarted) callPlugin("resume") { onResume() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controllers.key(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controllers.motion(event) || super.dispatchGenericMotionEvent(event)
    override fun onPause() {
        if (runtimeStarted) callPlugin("pause") { onPause() }
        super.onPause()
    }
    override fun onStop() {
        if (runtimeStarted) callPlugin("stop") { onStop() }
        super.onStop()
    }
    override fun onDestroy() {
        callPlugin("destroy") { onDestroy() }
        plugin = null
        resourceHandles.asReversed().forEach { runCatching { it.close() } }
        resourceHandles.clear()
        super.onDestroy()
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    private fun callPlugin(phase: String, block: EnginePlugin.() -> Unit) {
        runCatching { plugin?.block() }.onFailure { Log.e(TAG, "Plugin $phase failed", it) }
    }

    /**
     * "This game requires a patch", and a way to supply one.
     *
     * Enginehost never fetches the file and never goes looking in
     * Downloads for something that resembles it: the user obtains it
     * however they like and picks it explicitly. Auto-detection would be
     * Enginehost quietly choosing which untrusted script to run inside the
     * engine, under its own storage permission.
     *
     * [engineRequiredFile] is what the module's own patch loading was
     * after, when it could tell. It steers placement and archive
     * extraction only; it is never shown as a demand.
     */
    private fun offerPatch(engineRequiredFile: String?) {
        pendingRequiredFile = engineRequiredFile
        android.app.AlertDialog.Builder(this)
            .setTitle("This game requires a patch")
            .setMessage(
                "This game's data is packed in a form the engine can't read on its own. " +
                    "If you have the compatibility patch for it, choose the file and " +
                    "Enginehost will put it in place.",
            )
            .setPositiveButton("Choose file") { _, _ ->
                runCatching {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"),
                        REQUEST_PATCH,
                    )
                }.onFailure { failAndFinish("No file picker available on this device") }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PATCH) return
        val picked = data?.data
        if (resultCode != RESULT_OK || picked == null) {
            finish()
            return
        }
        // Re-derived rather than held: onCreate keeps it local, and the
        // intent is the authority on which folder this session is for.
        val folder = intent.getStringExtra(EXTRA_PATH)?.let(::File)
        if (folder == null) { finish(); return }
        val installed = PatchSupply.install(this, picked, folder, pendingRequiredFile)
        if (!installed) {
            failAndFinish("That file couldn't be used as a patch for this game")
            return
        }
        // Relaunch cleanly rather than resuming a half-started engine:
        // the module already failed once and its native state is not
        // guaranteed to be re-entrant.
        Toast.makeText(this, "Patch installed - restarting", Toast.LENGTH_SHORT).show()
        startActivity(intent)
        finish()
    }

    private fun failAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
        finish()
    }

    companion object {
        private const val TAG = "enginehost-runtime"
        private const val REQUEST_PATCH = 0x9a71
        const val EXTRA_PATH = "dev.enginehost.runtime.PATH"
        const val EXTRA_PLUGIN_BUNDLE = "dev.enginehost.runtime.PLUGIN_BUNDLE"
        const val EXTRA_CALLER_CONFIG = "dev.enginehost.runtime.CALLER_CONFIG"
        const val EXTRA_SAVE_PATH = "dev.enginehost.runtime.SAVE_PATH"
        const val EXTRA_ENGINE = "dev.enginehost.runtime.ENGINE"
        const val EXTRA_ENGINE_CONTEXT = "dev.enginehost.runtime.ENGINE_CONTEXT"
        const val EXTRA_ENGINE_VERSION = "dev.enginehost.runtime.ENGINE_VERSION"
        const val EXTRA_RUNTIME_VERSION = "dev.enginehost.runtime.RUNTIME_VERSION"
        const val EXTRA_CAPABILITY_ID = "dev.enginehost.runtime.CAPABILITY_ID"
        const val EXTRA_RUNTIME_REQUIREMENTS = "dev.enginehost.runtime.RUNTIME_REQUIREMENTS"
        const val EXTRA_EXEC_FILE = "dev.enginehost.runtime.EXEC_FILE"
        const val EXTRA_OPTIONS = "dev.enginehost.runtime.OPTIONS"
    }
}

internal fun safeRuntimeChild(root: File, relativePath: String): File {
    val path = validateBundlePath(relativePath)
    val child = File(root, path).canonicalFile
    require(child.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)) {
        "Bundle code path escaped its installation directory"
    }
    return child
}

private class RuntimeHost(
    private val activity: Activity,
    private val gameFolder: File,
    pluginPackage: String,
) : EngineHost {
    private val gameId = MessageDigest.getInstance("SHA-256")
        .digest(gameFolder.canonicalPath.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private val save = SaveLocationStore(activity).saveRoot()
    private val cache = File(activity.cacheDir, "plugins/$pluginPackage/$gameId").apply { mkdirs() }
    private val files = RuntimeFileSystem(gameFolder)

    override fun context(): Context = activity
    override fun saveDirectory(): File = save
    override fun cacheDirectory(): File = cache
    override fun fileSystem(): EngineFileSystem = files
    override fun log(priority: Int, tag: String, message: String, error: Throwable?) {
        val detail = error?.let { "\n${Log.getStackTraceString(it)}" }.orEmpty()
        Log.println(priority, "enginehost/$tag", message + detail)
    }
    override fun rumbleController(deviceId: Int, durationMs: Long, amplitude: Int): Boolean {
        val vibrator = InputDevice.getDevice(deviceId)?.vibrator ?: return false
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                durationMs.coerceIn(1, 10_000),
                amplitude.coerceIn(1, 255),
            ),
        )
        return true
    }
    override fun finish() = activity.finish()
}

private class RuntimeFileSystem(root: File) : EngineFileSystem {
    private val canonicalRoot = root.canonicalFile

    override fun openRead(relativePath: String): InputStream = FileInputStream(resolve(relativePath))
    override fun openWrite(relativePath: String, append: Boolean): OutputStream {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        return FileOutputStream(file, append)
    }
    override fun exists(relativePath: String): Boolean = runCatching { resolve(relativePath).exists() }.getOrDefault(false)
    override fun list(relativePath: String): Array<String> = resolve(relativePath).list() ?: emptyArray()

    private fun resolve(relativePath: String): File {
        if (File(relativePath).isAbsolute) throw FileNotFoundException("Absolute paths are not accepted")
        val resolved = File(canonicalRoot, relativePath).canonicalFile
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        if (resolved != canonicalRoot && !resolved.path.startsWith(rootPath)) {
            throw FileNotFoundException("Path leaves the game folder")
        }
        return resolved
    }
}
