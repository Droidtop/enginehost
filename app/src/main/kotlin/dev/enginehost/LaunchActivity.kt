package dev.enginehost

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * The primary, intended entry point and the launch screen in one. A caller
 * (droidtop or anything else) fires `ACTION dev.enginehost.LAUNCH` with
 * `path` (an absolute folder path, required) and optionally `config` (a
 * raw JSON string in the same shape as a real enginehost.json). `config`
 * may fill fields the folder config omitted, but cannot override values
 * already present in the folder -- see [EngineConfigReader.resolve].
 *
 * What the user sees between tapping a game and the engine's first frame
 * used to be Android's blank starting window for the `:runtime` process,
 * then black. Now it is this screen: the game's own icon and title over
 * the engine line and runtime it is starting on, with a way out. The
 * runtime activities declare `windowDisablePreview`, so nothing covers
 * this screen until the engine has actually drawn; at that point this
 * activity is stopped and finishes itself, so Back from the game goes to
 * the caller and never lands here.
 *
 * Every other in-app launch (library, config editor test run, trust
 * approval) also comes through here via [GameRunner.run], so there is one
 * launch path and one launch look.
 */
class LaunchActivity : AppCompatActivity() {
    private var runtimeStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val gameFolder = File(path)
        val inlineJson = intent.getStringExtra(EXTRA_CONFIG)
        when (val plan = GameRunner.plan(this, gameFolder, inlineJson, intent.getBooleanExtra(EXTRA_AUTOINSTALL, false))) {
            is GameRunner.Plan.Detour -> {
                plan.notice?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                startActivity(plan.intent)
                finish()
            }
            is GameRunner.Plan.Failure -> {
                Log.e(TAG, plan.message)
                Toast.makeText(this, plan.message, Toast.LENGTH_LONG).show()
                finish()
            }
            is GameRunner.Plan.Runtime -> {
                setContentView(R.layout.activity_launch)
                show(gameFolder, plan)
                if (savedInstanceState == null) {
                    runCatching { startActivityForResult(plan.intent, REQUEST_RUNTIME) }
                        .onFailure {
                            val message = "Failed to enter the Enginehost runtime: ${it.message}"
                            Log.e(TAG, message)
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            finish()
                            return
                        }
                }
                runtimeStarted = true
            }
        }
    }

    private fun show(gameFolder: File, plan: GameRunner.Plan.Runtime) {
        val config = plan.config
        findViewById<TextView>(R.id.gameTitle).text = config.title ?: gameFolder.name
        findViewById<TextView>(R.id.engineLine).text = getString(
            R.string.launch_engine_line,
            EngineNames.line(config.engine, config.engineContext),
            config.engineVersion.toString(),
        )
        findViewById<TextView>(R.id.runtimeLine).text = getString(
            R.string.launch_runtime_line,
            plan.resolved.plugin.bundleId,
            plan.resolved.capability.runtimeVersion.toString(),
        )
        findViewById<Button>(R.id.cancelButton).setOnClickListener { cancel() }

        val icon = findViewById<ImageView>(R.id.gameIcon)
        // The executable can be large and sits on removable storage; never
        // read it on the main thread while the runtime is starting.
        Thread {
            val bitmap = GameIcon.load(gameFolder, config)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (bitmap != null) icon.setImageBitmap(bitmap) else icon.visibility = android.view.View.GONE
            }
        }.start()
    }

    /**
     * The user changed their mind while the engine was still coming up.
     * The runtime activity is finished wherever it is, and the `:runtime`
     * process itself is killed: it exists only for this launch and may be
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
        // Stopped means something else now fills the screen: the engine has
        // drawn its first frame. This screen's job is over.
        if (runtimeStarted && !isFinishing) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // The runtime ended before it ever covered this screen: it failed to
        // start. Its own error reporting has already spoken; get out of the way.
        if (requestCode == REQUEST_RUNTIME) finish()
    }

    companion object {
        private const val TAG = "enginehost"
        private const val REQUEST_RUNTIME = 41
        const val EXTRA_PATH = "path"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_AUTOINSTALL = "autoinstallPlugin"

        fun intent(context: Context, gameFolder: File, inlineJson: String?, autoInstallPlugin: Boolean): Intent =
            Intent(context, LaunchActivity::class.java).apply {
                putExtra(EXTRA_PATH, gameFolder.absolutePath)
                inlineJson?.let { putExtra(EXTRA_CONFIG, it) }
                if (autoInstallPlugin) putExtra(EXTRA_AUTOINSTALL, true)
            }
    }
}
