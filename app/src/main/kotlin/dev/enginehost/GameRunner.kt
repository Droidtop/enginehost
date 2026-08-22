package dev.enginehost

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * The one real code path both [LaunchActivity] (Intent-driven, the
 * primary/intended way in) and [MainActivity] (the basic pick-and-launch
 * UI, for when there's no caller passing a path) go through -- reads the
 * folder's own enginehost.json, resolves it against whatever plugins are
 * actually installed right now (see [PluginRegistry]), and hands off to
 * the matched one via an explicit Intent.
 */
object GameRunner {
    private const val TAG = "enginehost"

    fun run(activity: Activity, gameFolder: File) {
        val config = try {
            EngineConfigReader.read(gameFolder)
        } catch (e: InvalidEngineConfigException) {
            fail(activity, e.message ?: "Invalid $CONFIG_FILE_NAME")
            return
        }
        val plugin = PluginRegistry.resolve(activity, config.engine, config.engineVersion, config.pluginVersionConstraint)
        if (plugin == null) {
            fail(activity, describeResolutionFailure(config))
            return
        }
        val intent = Intent(ACTION_RUN_PLUGIN).apply {
            component = ComponentName(plugin.packageName, plugin.activityName)
            putExtra("path", gameFolder.absolutePath)
            config.execFile?.let { putExtra("execFile", it) }
            // Passed through as-is, never inspected here -- see EngineConfig's own doc comment.
            config.options?.let { putExtra("options", it.toString()) }
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            fail(activity, "Failed to start ${plugin.packageName}: ${e.message}")
        }
    }

    /**
     * Distinguishes "no installed plugin implements that engine at all"
     * from "one does, but the game's own pluginVersion constraint
     * rejected every candidate" -- the second case is a real, deliberate
     * outcome (a game protecting itself from a known-bad plugin build),
     * worth a different message than a plain missing-plugin error.
     */
    private fun describeResolutionFailure(config: EngineConfig): String {
        val constraint = config.pluginVersionConstraint
        return if (constraint != null) {
            "No installed plugin for \"${config.engine}\" satisfies this game's pluginVersion requirement"
        } else {
            "No installed plugin for \"${config.engine}\""
        }
    }

    private fun fail(activity: Activity, message: String) {
        Log.e(TAG, message)
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
