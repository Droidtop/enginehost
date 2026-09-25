package dev.enginehost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import dev.enginehost.api.EngineControllerEvent
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
    // Non-null only for an isolatable plugin (docs/engine-sandbox.md "Layer
    // 2"); plugin above is then IsolatedControllerProxy, a stand-in so the
    // existing controller/lifecycle dispatch below needs no branch of its
    // own -- it already only ever calls EnginePlugin methods.
    private var isolatedRuntime: IsolatedRuntimeHost? = null
    // Scoped to the engine this session is running, so the user's
    // per-engine mappings apply while playing rather than only in settings.
    private val controllers by lazy {
        RuntimeControllerRouter(
            this,
            ControllerScope.of(intent.getStringExtra(EXTRA_ENGINE), intent.getStringExtra(EXTRA_ENGINE_CONTEXT)),
        ) { plugin }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val display = FrameLayout(this)
        setContentView(display)
        val gameFolder = intent.getStringExtra(EXTRA_PATH)?.let(::File)
            ?: return failAndFinish("Runtime launch omitted the game path")
        val expectedBundle = intent.getStringExtra(EXTRA_PLUGIN_BUNDLE)
            ?: return failAndFinish("Runtime launch omitted the selected plugin")
        // Made and checked by the planner; not worked out a second time here.
        val saveFolder = intent.getStringExtra(EXTRA_SAVE_PATH)?.let(::File)
            ?: return failAndFinish("Runtime launch omitted the save folder")
        val config = try {
            EngineConfigReader.resolve(
                gameFolder, intent.getStringExtra(EXTRA_CALLER_CONFIG), intent.getStringExtra(EXTRA_TESTING_CONFIG),
            )
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
        if (resolved.plugin.isolatable) {
            return startIsolatedRuntime(resolved, config, gameFolder, saveFolder, display)
        }

        CrashWatch.arm(this, gameFolder, resolved.plugin.bundleId)
        try {
            val verifiedManifest = InstalledBundleVerifier.verify(this, resolved.plugin)
            val instance = loadPlugin(resolved.plugin)
            val host = RuntimeHost(this, gameFolder, resolved.plugin.bundleId, saveFolder)
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
            failAndFinish(STARTUP_FAILED + (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun onStart() {
        super.onStart()
        if (runtimeStarted) callPlugin("start") { onStart() }
    }

    private fun loadPlugin(installed: InstalledPlugin): EnginePlugin {
        val loaded = loadEnginePlugin(
            this, installed.directory, installed.entrypointClass,
            installed.dexFiles, installed.resourceApks, classLoader,
        )
        resourceHandles += loaded.resourceHandles
        return loaded.plugin
    }

    /**
     * Sandbox layer 2, first milestone (docs/engine-sandbox.md): this
     * bundle's own :runtime runs isolated instead of here. [plugin] is set
     * to a stand-in ([IsolatedControllerProxy]) purely so the controller
     * and lifecycle dispatch above -- dispatchKeyEvent, onResume/onPause/
     * onDestroy -- needs no branch of its own; [isolatedRuntime] is the
     * real thing and carries the frame/file/callback wiring.
     *
     * A game whose plugin throws EnginePatchRequiredException from
     * onCreate is not offered the patch dialog under isolation: the
     * exception is raised inside the isolated service, across Binder,
     * which does not preserve a custom exception's own type or its
     * requiredFile() -- only its message survives, so this path ends in
     * an ordinary failure instead. Recorded, not silently dropped: none
     * of the milestone's own rig-checked games need a patch.
     */
    private fun startIsolatedRuntime(
        resolved: ResolvedPlugin,
        config: EngineConfig,
        gameFolder: File,
        saveFolder: File,
        display: FrameLayout,
    ) {
        val verifiedManifest = try {
            InstalledBundleVerifier.verify(this, resolved.plugin).also {
                check(it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Plugin verification failed", e)
            return failAndFinish(STARTUP_FAILED + (e.message ?: e.javaClass.simpleName))
        }
        Log.i(TAG, "verified bundle ${resolved.plugin.bundleId} for isolated launch, apiVersion ${verifiedManifest.apiVersion}")
        CrashWatch.arm(this, gameFolder, resolved.plugin.bundleId)
        val runtime = IsolatedRuntimeHost(this)
        isolatedRuntime = runtime
        plugin = IsolatedControllerProxy()
        runtimeStarted = true
        runtime.start(
            resolved.plugin,
            config.engine,
            config.engineContext ?: DEFAULT_ENGINE_CONTEXT,
            config.engineVersion.toString(),
            resolved.capability.runtimeVersion.toString(),
            resolved.capability.id,
            config.execFile,
            config.options?.toString(),
            config.runtimeRequirements.mapValues { it.value.toString() },
            intent.getStringArrayExtra(EXTRA_RESTART_ARGUMENTS) ?: emptyArray(),
            gameFolder,
            saveFolder,
            display,
        ) { message -> failAndFinish(STARTUP_FAILED + message) }
    }

    /** Routes the pad through to the isolated runtime; see [startIsolatedRuntime]. */
    private inner class IsolatedControllerProxy : EnginePlugin {
        override fun onCreate(session: EnginePluginSession) {}
        override fun onControllerEvent(event: EngineControllerEvent): Boolean = isolatedRuntime?.onControllerEvent(
            event.action(), event.value(), event.deviceId(), event.deviceDescriptor(), event.eventTime(),
        ) ?: false
    }

    override fun onResume() {
        super.onResume()
        if (runtimeStarted) callPlugin("resume") { onResume() }
        isolatedRuntime?.resume()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controllers.key(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controllers.motion(event) || super.dispatchGenericMotionEvent(event)
    override fun onPause() {
        if (runtimeStarted) callPlugin("pause") { onPause() }
        isolatedRuntime?.pause()
        super.onPause()
    }
    override fun onStop() {
        if (runtimeStarted) callPlugin("stop") { onStop() }
        super.onStop()
    }
    override fun onDestroy() {
        callPlugin("destroy") { onDestroy() }
        isolatedRuntime?.destroy()
        isolatedRuntime = null
        plugin = null
        resourceHandles.asReversed().forEach { runCatching { it.close() } }
        resourceHandles.clear()
        super.onDestroy()
        if (isFinishing) {
            CrashWatch.disarm(this)
            Process.killProcess(Process.myPid())
        }
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
        Sheet(this)
            .title("This game requires a patch")
            .message(
                "This game's data is packed in a form the engine can't read on its own. " +
                    "If you have the compatibility patch for it, choose the file and " +
                    "Enginehost will put it in place.",
            )
            .choice("Choose file") {
                runCatching {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"),
                        REQUEST_PATCH,
                    )
                }.onFailure { failAndFinish("No file picker available on this device") }
            }
            .onCancel { finish() }
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

    /**
     * Startup cannot continue and the reason is known. The launch screen
     * beneath this one shows the sentence; nothing is toasted over a screen
     * that is about to disappear.
     */
    internal fun failAndFinish(message: String) {
        Log.e(TAG, message)
        setResult(RESULT_FIRST_USER, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    companion object {
        private const val TAG = "enginehost-runtime"
        const val STARTUP_FAILED = "Plugin startup failed: "
        private const val REQUEST_PATCH = 0x9a71
        const val EXTRA_PATH = "dev.enginehost.runtime.PATH"
        /** On a failed startup's result: the sentence the runtime has for the launch screen. */
        const val EXTRA_ERROR = "dev.enginehost.runtime.ERROR"
        /** Result extra: the engine asked to be restarted. See [EngineHost.restart]. */
        const val EXTRA_RESTART = "dev.enginehost.runtime.RESTART"
        /**
         * The game's restart arguments: in the result of a run that asked to
         * be restarted, and in the launch intent of the run that follows it.
         */
        const val EXTRA_RESTART_ARGUMENTS = "dev.enginehost.runtime.RESTART_ARGUMENTS"
        const val EXTRA_PLUGIN_BUNDLE = "dev.enginehost.runtime.PLUGIN_BUNDLE"
        const val EXTRA_CALLER_CONFIG = "dev.enginehost.runtime.CALLER_CONFIG"
        /** Game setup's Test: the testing configuration standing in for the folder's (see TestingConfigStore). */
        const val EXTRA_TESTING_CONFIG = "dev.enginehost.runtime.TESTING_CONFIG"
        const val EXTRA_SAVE_PATH = "dev.enginehost.runtime.SAVE_PATH"
        const val EXTRA_ENGINE = "dev.enginehost.runtime.ENGINE"
        const val EXTRA_ENGINE_CONTEXT = "dev.enginehost.runtime.ENGINE_CONTEXT"
        const val EXTRA_ENGINE_VERSION = "dev.enginehost.runtime.ENGINE_VERSION"
        const val EXTRA_RUNTIME_VERSION = "dev.enginehost.runtime.RUNTIME_VERSION"
        const val EXTRA_CAPABILITY_ID = "dev.enginehost.runtime.CAPABILITY_ID"
        const val EXTRA_RUNTIME_REQUIREMENTS = "dev.enginehost.runtime.RUNTIME_REQUIREMENTS"
        const val EXTRA_EXEC_FILE = "dev.enginehost.runtime.EXEC_FILE"
        const val EXTRA_OPTIONS = "dev.enginehost.runtime.OPTIONS"
        /**
         * The person's controller map for this engine; see
         * ControllerBindingStore.exportJson. Absent means the engine's own
         * controller handling is in use and the host sends no map at all.
         */
        const val EXTRA_CONTROLLER_BINDINGS = "dev.enginehost.runtime.CONTROLLER_BINDINGS"
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

/**
 * A path under [root], with no ".." or symlink escape -- the same check
 * RuntimeFileSystem applies for the in-process EngineFileSystem, and what
 * IsolatedRuntimeHost's IEngineFileBroker implementations apply for a
 * launch's isolated runtime (docs/engine-sandbox.md "Host file service
 * design"). One mechanism, both callers.
 */
internal fun resolveWithinRoot(root: File, relativePath: String): File {
    if (File(relativePath).isAbsolute) throw FileNotFoundException("Absolute paths are not accepted")
    val canonicalRoot = root.canonicalFile
    val resolved = File(canonicalRoot, relativePath).canonicalFile
    val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
    if (resolved != canonicalRoot && !resolved.path.startsWith(rootPath)) {
        throw FileNotFoundException("Path leaves its root")
    }
    return resolved
}

private class RuntimeHost(
    private val activity: Activity,
    private val gameFolder: File,
    pluginPackage: String,
    private val save: File,
) : EngineHost {
    private val gameId = MessageDigest.getInstance("SHA-256")
        .digest(gameFolder.canonicalPath.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
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

    /**
     * The result tells the launch screen waiting beneath this activity that
     * the end is a restart, not an exit or a crash; finishing is what ends
     * the process (onDestroy kills it when the activity is finishing), and
     * the launch screen starts the game again once that process is gone.
     */
    override fun restart(arguments: Array<String>) {
        activity.setResult(
            Activity.RESULT_FIRST_USER,
            Intent()
                .putExtra(RuntimeActivity.EXTRA_RESTART, true)
                .putExtra(RuntimeActivity.EXTRA_RESTART_ARGUMENTS, arguments),
        )
        activity.finish()
    }

    override fun restartArguments(): Array<String> =
        activity.intent.getStringArrayExtra(RuntimeActivity.EXTRA_RESTART_ARGUMENTS) ?: emptyArray()

    /** The same end as a plugin whose onCreate threw: the launch screen shows [message]. */
    override fun fail(message: String) {
        (activity as? RuntimeActivity)?.failAndFinish(RuntimeActivity.STARTUP_FAILED + message) ?: activity.finish()
    }
}

private class RuntimeFileSystem(private val root: File) : EngineFileSystem {
    override fun openRead(relativePath: String): InputStream = FileInputStream(resolve(relativePath))
    override fun openWrite(relativePath: String, append: Boolean): OutputStream {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        return FileOutputStream(file, append)
    }
    override fun exists(relativePath: String): Boolean = runCatching { resolve(relativePath).exists() }.getOrDefault(false)
    override fun list(relativePath: String): Array<String> = resolve(relativePath).list() ?: emptyArray()

    private fun resolve(relativePath: String): File = resolveWithinRoot(root, relativePath)
}
