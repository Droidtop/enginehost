package dev.enginehost

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * The primary, intended entry point, the launch screen, and the floor the
 * game stands on, in one. A caller (droidtop or anything else) fires
 * `ACTION dev.enginehost.LAUNCH` with `path` (an absolute folder path,
 * required) and optionally `config` (a raw JSON string in the same shape as
 * a real enginehost.json). `config` may fill fields the folder config
 * omitted, but cannot override values already present in the folder: see
 * [EngineConfigReader.resolve].
 *
 * Between tapping a game and the engine's first frame this screen shows the
 * game's icon and title over the engine and runtime it is starting on, with
 * a way out. The runtime activities declare `windowDisablePreview`, so
 * nothing covers it until the engine has actually drawn.
 *
 * It then stays alive underneath the game for the whole session. Games run
 * in their own process, and a process is the only thing Android can isolate
 * a crash to: when the runtime dies, whatever is under it is what the person
 * lands on. With this screen there, that is Enginehost, which can say what
 * happened and offer to try again or report it, instead of dropping them
 * back to wherever they came from with nothing said. A game that ends the
 * way it meant to passes straight through, and the caller sees this screen
 * for no longer than a frame.
 *
 * Every other in-app launch (library, config editor test run, trust
 * approval) also comes through here via [GameRunner.run], so there is one
 * launch path and one launch look.
 */
class LaunchActivity : AppCompatActivity() {
    private lateinit var gameFolder: File
    private var runtimeStarted = false
    /** Set once the runtime has covered this screen: the engine drew, and a later exit is the game ending or dying. */
    private var runtimeCovered = false
    private var runtimePlugin: String? = null
    private var lastCrash: CrashWatch.Crash? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        gameFolder = File(path)
        runtimeCovered = savedInstanceState?.getBoolean(STATE_COVERED) ?: false
        runtimeStarted = savedInstanceState?.getBoolean(STATE_STARTED) ?: false
        runtimePlugin = savedInstanceState?.getString(STATE_PLUGIN)
        setContentView(R.layout.activity_launch)
        findViewById<Button>(R.id.cancelButton).setOnClickListener { cancel() }
        findViewById<Button>(R.id.retryButton).setOnClickListener { launch() }
        findViewById<Button>(R.id.reportButton).setOnClickListener { report() }
        if (savedInstanceState == null) launch() else showTitle(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_COVERED, runtimeCovered)
        outState.putBoolean(STATE_STARTED, runtimeStarted)
        outState.putString(STATE_PLUGIN, runtimePlugin)
    }

    /** Earlier saves were offered on this screen and answered; a retry or restart does not ask again. */
    private var earlierSavesAnswered = false

    /** What the run that just ended passed to EngineHost.restart, until the next run takes it. */
    private var restartArguments: Array<String>? = null

    /** Plan the launch and enter the runtime, or go where the plan says first. */
    private fun launch(waitedForRuntime: Boolean = false) {
        val inlineJson = intent.getStringExtra(EXTRA_CONFIG)
        when (val plan = GameRunner.plan(this, gameFolder, inlineJson, intent.getBooleanExtra(EXTRA_AUTOINSTALL, false))) {
            is GameRunner.Plan.Detour -> {
                plan.notice?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                startActivity(plan.intent)
                finish()
            }
            is GameRunner.Plan.Failure -> {
                Log.e(TAG, plan.message)
                showTitle(null)
                showFailure(plan.message, retry = plan.retry)
            }
            is GameRunner.Plan.Runtime -> {
                showTitle(plan)
                showStarting()
                plan.earlierSaves?.takeUnless { earlierSavesAnswered }?.let {
                    offerEarlierSaves(it, waitedForRuntime)
                    return
                }
                // One runtime process, one game. This screen is the root of
                // a fresh game task (see [start]), so a game that was running
                // has just been finished along with its task, and its process
                // may still be on its way out. A RuntimeActivity started now
                // would land inside it, and the plugin's engine cannot be
                // loaded twice in one process: the person got the linker's
                // refusal as a "plugin startup failed" (rig, 2026-09-18).
                if (!waitedForRuntime && runtimeAlive()) {
                    launchWhenRuntimeGone()
                    return
                }
                runtimeCovered = false
                lastCrash = null
                runtimePlugin = plan.resolved.plugin.bundleId
                // Only the run that follows a restart request carries its
                // arguments; a retry or a later launch starts clean.
                restartArguments?.let { plan.intent.putExtra(RuntimeActivity.EXTRA_RESTART_ARGUMENTS, it) }
                restartArguments = null
                runCatching { startActivityForResult(plan.intent, REQUEST_RUNTIME) }
                    .onFailure {
                        val message = "Failed to enter the Enginehost runtime: ${it.message}"
                        Log.e(TAG, message)
                        showFailure(message, retry = true)
                        return
                    }
                runtimeStarted = true
                RunningGame.folder = gameFolder
                // The home screen is the library of every game played here,
                // whichever front door started it: a launch from droidtop
                // is as much a game of this app's as one picked on its own
                // home (README, "two front doors"). Only a run that really
                // started counts; a detour or a refusal adds nothing.
                GameLibraryStore(this).remember(gameFolder)
            }
        }
    }

    /**
     * This game's engine saves beside the game, and an earlier Enginehost
     * kept its saves in a folder of its own, where the game no longer looks.
     * The person decides: copy them into the game folder (nothing is
     * deleted or overwritten), leave them for now, or leave them for good.
     */
    private fun offerEarlierSaves(saves: EarlierSaves, waitedForRuntime: Boolean) {
        fun answered() {
            earlierSavesAnswered = true
            launch(waitedForRuntime)
        }
        Sheet(this)
            .title(R.string.earlier_saves_title)
            .message(
                resources.getQuantityString(
                    R.plurals.earlier_saves_message, saves.missing.size,
                    saves.missing.size, saves.from.absolutePath,
                ),
            )
            .choice(R.string.earlier_saves_copy) {
                Thread {
                    val result = saves.copy()
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        val message = if (result.failed == 0) {
                            resources.getQuantityString(R.plurals.earlier_saves_copied, result.copied, result.copied)
                        } else {
                            resources.getQuantityString(
                                R.plurals.earlier_saves_failed, result.failed, result.failed, saves.from.absolutePath,
                            )
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        answered()
                    }
                }.start()
            }
            .choice(R.string.earlier_saves_not_now) { answered() }
            .choice(R.string.earlier_saves_leave) {
                SaveLocationStore(this).leaveEarlierSaves(saves)
                answered()
            }
            .onCancel { cancel() }
            .show()
    }

    private fun showTitle(plan: GameRunner.Plan.Runtime?) {
        val config = plan?.config
        findViewById<TextView>(R.id.gameTitle).text = config?.title ?: gameFolder.name
        findViewById<TextView>(R.id.engineLine).apply {
            text = config?.let { getString(R.string.launch_engine_line, EngineNames.line(it.engine, it.engineContext), it.engineVersion.toString()) }
            visibility = if (config == null) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.runtimeLine).apply {
            text = plan?.let { getString(R.string.launch_runtime_line, it.resolved.plugin.bundleId, it.resolved.capability.runtimeVersion.toString()) }
            visibility = if (plan == null) View.GONE else View.VISIBLE
        }
        val icon = findViewById<ImageView>(R.id.gameIcon)
        // The executable can be large and sits on removable storage; never
        // read it on the main thread while the runtime is starting.
        // Without a config there is no engine to look the icon up by.
        if (config == null) {
            icon.visibility = View.GONE
            return
        }
        Thread {
            val bitmap = GameIcon.load(gameFolder, config)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (bitmap != null) icon.setImageBitmap(bitmap) else icon.visibility = View.GONE
            }
        }.start()
    }

    private fun showStarting() {
        findViewById<TextView>(R.id.launchStatus).apply {
            setText(R.string.launch_starting)
            setTextColor(getColor(R.color.eh_text_secondary))
        }
        findViewById<View>(R.id.retryButton).visibility = View.GONE
        findViewById<View>(R.id.reportButton).visibility = View.GONE
        findViewById<Button>(R.id.cancelButton).setText(R.string.cancel)
    }

    /** The game is not running and the person is owed a sentence about why. */
    private fun showFailure(message: String, retry: Boolean) {
        runtimeStarted = false
        findViewById<TextView>(R.id.launchStatus).apply {
            text = message
            setTextColor(getColor(R.color.eh_caution))
        }
        findViewById<View>(R.id.retryButton).visibility = if (retry) View.VISIBLE else View.GONE
        findViewById<View>(R.id.reportButton).visibility = View.VISIBLE
        findViewById<Button>(R.id.cancelButton).setText(R.string.back)
    }

    private fun report() {
        startActivity(ProblemReportActivity.intent(this, gameFolder, lastCrash, beforeStart = !runtimeCovered))
    }

    /**
     * The person changed their mind while the engine was still coming up, or
     * is leaving after a failure. A starting runtime is finished wherever it
     * is and its process killed: it exists only for this launch and may be
     * mid-way through loading native code that has nowhere to render.
     */
    private fun cancel() {
        if (runtimeStarted) {
            finishActivity(REQUEST_RUNTIME)
            val runtimeProcess = "$packageName:runtime"
            getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.filter { it.processName == runtimeProcess }
                ?.forEach { Process.killProcess(it.pid) }
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Stopped while the runtime is up means the engine has drawn its
        // first frame and now fills the screen. This screen waits beneath it.
        if (runtimeStarted && !isFinishing) runtimeCovered = true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RUNTIME) return
        RunningGame.ended(gameFolder)
        if (data?.getBooleanExtra(RuntimeActivity.EXTRA_RESTART, false) == true) {
            // The engine asked to be restarted (EngineHost.restart). Not an
            // exit and not a crash: plan the launch again, from this same
            // intent, once the old runtime process has gone.
            restartArguments = data?.getStringArrayExtra(RuntimeActivity.EXTRA_RESTART_ARGUMENTS)
            showStarting()
            launchWhenRuntimeGone()
            return
        }
        val reported = data?.getStringExtra(RuntimeActivity.EXTRA_ERROR)
        if (reported != null) {
            // The runtime knew what went wrong and said so. That sentence beats
            // anything a crash record could add.
            showFailure(reported, retry = true)
            return
        }
        // Android records why a process ended a moment after delivering the
        // cancelled result for its activity; give the record time to exist.
        handler.postDelayed({ if (!isDestroyed && !isFinishing) classifyExit() }, EXIT_RECORD_DELAY_MS)
    }

    /**
     * A natively crashing runtime is still alive when its activity's result
     * arrives (crash_dump holds it while the tombstone is written), and the
     * exit record follows later still. Wait, in short steps and for a bounded
     * time, until the process is gone and recorded; only then decide.
     */
    private fun classifyExit(attempt: Int = 0) {
        if (attempt < EXIT_RECORD_MAX_ATTEMPTS && CrashWatch.pending(this)) {
            handler.postDelayed({ if (!isDestroyed && !isFinishing) classifyExit(attempt + 1) }, EXIT_RECORD_DELAY_MS)
            return
        }
        val crash = CrashWatch.consume(this)
        when {
            crash != null -> {
                lastCrash = crash
                showFailure(getString(R.string.launch_crashed, crash.reason), retry = true)
            }
            runtimeCovered -> finish()
            // Ended before drawing anything, with nothing said and no crash on
            // record: the runtime never got going.
            else -> showFailure(getString(R.string.launch_runtime_died, runtimePlugin ?: ""), retry = true)
        }
    }

    /**
     * A runtime that is ending (a restart request, or a game whose task was
     * cleared for another) is still a live process for a moment after its
     * activity is gone. Starting the runtime activity before that process has exited
     * would put the new game in the OLD process, with the engine's native
     * state still loaded -- the very thing a restart exists to get rid of,
     * and a load the linker refuses outright for a second plugin. Wait for
     * the process to go, in short steps and for a bounded time, then launch
     * exactly as a first launch does.
     */
    private fun launchWhenRuntimeGone(attempt: Int = 0) {
        val alive = runtimeAlive()
        if (alive && attempt < EXIT_RECORD_MAX_ATTEMPTS) {
            handler.postDelayed({ if (!isDestroyed && !isFinishing) launchWhenRuntimeGone(attempt + 1) }, EXIT_RECORD_DELAY_MS)
            return
        }
        if (alive) Log.w(TAG, "The runtime process outlived the game it was running; launching anyway")
        launch(waitedForRuntime = true)
    }

    private fun runtimeAlive(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.runningAppProcesses.orEmpty().any { it.processName == "$packageName:runtime" }
    }

    override fun onDestroy() {
        // Not set when onCreate found no path and finished at once.
        if (::gameFolder.isInitialized) RunningGame.ended(gameFolder)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "enginehost"
        private const val REQUEST_RUNTIME = 41
        private const val EXIT_RECORD_DELAY_MS = 400L
        /** With the delay above, about six seconds before giving up on a record. */
        private const val EXIT_RECORD_MAX_ATTEMPTS = 15
        private const val STATE_COVERED = "covered"
        private const val STATE_STARTED = "started"
        private const val STATE_PLUGIN = "plugin"
        const val EXTRA_PATH = "path"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_AUTOINSTALL = "autoinstallPlugin"

        /**
         * The one way a game is started, from inside this app or from
         * outside it ([LaunchEntryActivity]).
         *
         * A game is its own task: this screen at the root, the runtime above
         * it. Android treats two intents that differ only in their extras as
         * the same intent, and answers NEW_TASK for one that matches a live
         * task's root by bringing that task forward and dropping the request
         * -- so a frontend that launched a second game was shown the first
         * (rig, 2026-09-18). Hence the decision is made here and not left to
         * the task stack. The game that is already running is brought
         * forward exactly as it was; anything else starts the game task from
         * nothing, which finishes a game that was running the way leaving it
         * does, as launching content from a frontend replaces what an
         * emulator was running.
         */
        fun start(context: Context, gameFolder: File, inlineJson: String?, autoInstallPlugin: Boolean) {
            val intent = Intent(context, LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!RunningGame.isRunning(gameFolder)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.putExtra(EXTRA_PATH, gameFolder.absolutePath)
                inlineJson?.let { intent.putExtra(EXTRA_CONFIG, it) }
                if (autoInstallPlugin) intent.putExtra(EXTRA_AUTOINSTALL, true)
            }
            context.startActivity(intent)
        }
    }
}

/**
 * The game the runtime process is running now, if any. Launch screens and
 * the launch entry all live in the app's main process, so this is enough to
 * tell "the game that is already running" from any other launch.
 */
internal object RunningGame {
    var folder: File? = null

    fun isRunning(gameFolder: File): Boolean =
        folder?.canonicalFile == gameFolder.canonicalFile

    fun ended(gameFolder: File) {
        if (isRunning(gameFolder)) folder = null
    }
}
