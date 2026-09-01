package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.io.File

/** Resolves folder-authoritative configuration and enters the host-owned runtime process. */
object GameRunner {
    private const val TAG = "enginehost"

    fun run(
        activity: Activity,
        gameFolder: File,
        inlineJson: String? = null,
        autoInstallPlugin: Boolean = false,
    ) {
        val config = try {
            EngineConfigReader.resolve(gameFolder, inlineJson)
        } catch (e: InvalidEngineConfigException) {
            // A folder with no config at all is a setup gap, not a dead end:
            // route into the config editor with detection running, instead of
            // failing into a toast over a blank screen.
            if (gameFolder.isDirectory && !File(gameFolder, CONFIG_FILE_NAME).isFile) {
                Toast.makeText(activity, R.string.launch_needs_config, Toast.LENGTH_LONG).show()
                activity.startActivity(
                    Intent(activity, ConfigEditorActivity::class.java).apply {
                        putExtra(ConfigEditorActivity.EXTRA_PATH, gameFolder.absolutePath)
                        inlineJson?.let { putExtra(ConfigEditorActivity.EXTRA_CONFIG, it) }
                    },
                )
                return
            }
            fail(activity, e.message ?: "Invalid $CONFIG_FILE_NAME")
            return
        }
        val resolved = PluginRegistry.resolve(
            activity, config.engine, config.engineContext, config.engineVersion,
            config.runtimeRequirements, config.pluginVersionConstraint,
        )
        if (resolved == null) {
            PendingPluginLaunchStore(activity).save(gameFolder, inlineJson)
            activity.startActivity(
                Intent(activity, PluginCatalogActivity::class.java).apply {
                    putExtra(PluginCatalogActivity.EXTRA_GAME_PATH, gameFolder.absolutePath)
                    inlineJson?.let { putExtra(PluginCatalogActivity.EXTRA_CALLER_CONFIG, it) }
                    putExtra(PluginCatalogActivity.EXTRA_AUTOINSTALL, autoInstallPlugin)
                },
            )
            return
        }
        if (!PluginTrustStore(activity).isApproved(resolved.plugin)) {
            PendingPluginLaunchStore(activity).save(gameFolder, inlineJson, resolved.plugin.bundleId)
            activity.startActivity(
                Intent(activity, PluginTrustActivity::class.java)
                    .putExtra(PluginTrustActivity.EXTRA_BUNDLE, resolved.plugin.bundleId),
            )
            return
        }
        val runtimeClass = if (resolved.plugin.runtimeTransport == RUNTIME_TRANSPORT_ACTIVITY) {
            BundledActivityProxy::class.java
        } else RuntimeActivity::class.java
        val intent = Intent(activity, runtimeClass).apply {
            putExtra(RuntimeActivity.EXTRA_PATH, gameFolder.absolutePath)
            putExtra(RuntimeActivity.EXTRA_PLUGIN_BUNDLE, resolved.plugin.bundleId)
            putExtra(RuntimeActivity.EXTRA_SAVE_PATH, SaveLocationStore(activity).saveRoot().absolutePath)
            putExtra(RuntimeActivity.EXTRA_ENGINE, config.engine)
            putExtra(RuntimeActivity.EXTRA_ENGINE_CONTEXT, config.engineContext ?: DEFAULT_ENGINE_CONTEXT)
            putExtra(RuntimeActivity.EXTRA_ENGINE_VERSION, config.engineVersion.toString())
            putExtra(RuntimeActivity.EXTRA_RUNTIME_VERSION, resolved.capability.runtimeVersion.toString())
            putExtra(RuntimeActivity.EXTRA_CAPABILITY_ID, resolved.capability.id)
            putExtra(
                RuntimeActivity.EXTRA_RUNTIME_REQUIREMENTS,
                org.json.JSONObject(config.runtimeRequirements.mapValues { it.value.toString() }).toString(),
            )
            config.execFile?.let { putExtra(RuntimeActivity.EXTRA_EXEC_FILE, it) }
            config.options?.let { putExtra(RuntimeActivity.EXTRA_OPTIONS, it.toString()) }
            inlineJson?.let { putExtra(RuntimeActivity.EXTRA_CALLER_CONFIG, it) }
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { fail(activity, "Failed to enter the Enginehost runtime: ${it.message}") }
    }

    private fun fail(activity: Activity, message: String) {
        Log.e(TAG, message)
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
