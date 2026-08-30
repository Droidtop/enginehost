package dev.enginehost

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
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

    fun run(activity: Activity, gameFolder: File, inlineJson: String? = null) {
        val config = try {
            EngineConfigReader.resolve(gameFolder, inlineJson)
        } catch (e: InvalidEngineConfigException) {
            fail(activity, e.message ?: "Invalid $CONFIG_FILE_NAME")
            return
        }
        val resolved = PluginRegistry.resolve(
            activity,
            config.engine,
            config.engineContext,
            config.engineVersion,
            config.runtimeRequirements,
            config.pluginVersionConstraint,
        )
        if (resolved == null) {
            fail(activity, describeResolutionFailure(config))
            return
        }
        val intent = Intent(ACTION_RUN_PLUGIN).apply {
            component = ComponentName(resolved.plugin.packageName, resolved.plugin.activityName)
            putExtra("path", gameFolder.absolutePath)
            putExtra("engineContext", config.engineContext ?: DEFAULT_ENGINE_CONTEXT)
            putExtra("engineVersion", config.engineVersion.toString())
            putExtra("runtimeVersion", resolved.capability.runtimeVersion.toString())
            putExtra("capabilityId", resolved.capability.id)
            if (config.runtimeRequirements.isNotEmpty()) {
                putExtra(
                    "runtimeRequirements",
                    JSONObject().apply {
                        config.runtimeRequirements.forEach { (name, version) -> put(name, version.toString()) }
                    }.toString(),
                )
            }
            config.execFile?.let { putExtra("execFile", it) }
            // Passed through as-is, never inspected here -- see EngineConfig's own doc comment.
            config.options?.let { putExtra("options", it.toString()) }
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            fail(activity, "Failed to start ${resolved.plugin.packageName}: ${e.message}")
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
            "No installed plugin capability for ${describeEngine(config)} satisfies this game's pluginVersion allowlist"
        } else {
            "No installed plugin capability for ${describeEngine(config)}"
        }
    }

    private fun describeEngine(config: EngineConfig): String = buildString {
        append('"').append(config.engine).append('"')
        config.engineContext?.let { append(" context \"").append(it).append('"') }
        append(" version ").append(config.engineVersion)
        if (config.runtimeRequirements.isNotEmpty()) {
            append(" with ")
            append(config.runtimeRequirements.entries.joinToString { "${it.key} ${it.value}" })
        }
    }

    private fun fail(activity: Activity, message: String) {
        Log.e(TAG, message)
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
