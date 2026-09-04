package dev.enginehost

import android.content.Context
import android.content.Intent
import java.io.File

/** Resolves folder-authoritative configuration and enters the host-owned runtime process. */
object GameRunner {
    /** What launching a folder amounts to, decided before anything is shown or started. */
    sealed class Plan {
        /**
         * Another enginehost screen has to come first: the config editor
         * for a folder with no config, the catalog when no installed plugin
         * fits, the trust screen when the fitting one is not yet approved.
         * [notice] is an optional string resource to show on the way.
         */
        class Detour(val intent: Intent, val notice: Int? = null) : Plan()

        /** The runtime can start now; [intent] enters the `:runtime` process. */
        class Runtime(val intent: Intent, val config: EngineConfig, val resolved: ResolvedPlugin) : Plan()

        class Failure(val message: String) : Plan()
    }

    /**
     * Launch [gameFolder]. Every caller goes through [LaunchActivity] so
     * every launch looks the same: the game's icon and title over the
     * engine it is about to run on, until the engine draws its first frame.
     */
    fun run(
        context: Context,
        gameFolder: File,
        inlineJson: String? = null,
        autoInstallPlugin: Boolean = false,
    ) {
        context.startActivity(LaunchActivity.intent(context, gameFolder, inlineJson, autoInstallPlugin))
    }

    fun plan(
        context: Context,
        gameFolder: File,
        inlineJson: String? = null,
        autoInstallPlugin: Boolean = false,
    ): Plan {
        val config = try {
            EngineConfigReader.resolve(gameFolder, inlineJson)
        } catch (e: InvalidEngineConfigException) {
            // A folder with no config at all is a setup gap, not a dead end:
            // route into the config editor with detection running, instead of
            // failing into a toast over a blank screen.
            if (gameFolder.isDirectory && !File(gameFolder, CONFIG_FILE_NAME).isFile) {
                return Plan.Detour(
                    Intent(context, ConfigEditorActivity::class.java).apply {
                        putExtra(ConfigEditorActivity.EXTRA_PATH, gameFolder.absolutePath)
                        inlineJson?.let { putExtra(ConfigEditorActivity.EXTRA_CONFIG, it) }
                    },
                    notice = R.string.launch_needs_config,
                )
            }
            return Plan.Failure(e.message ?: "Invalid $CONFIG_FILE_NAME")
        }
        val resolved = PluginRegistry.resolve(
            context, config.engine, config.engineContext, config.engineVersion,
            config.runtimeRequirements, config.pluginVersionConstraint,
        )
        if (resolved == null) {
            PendingPluginLaunchStore(context).save(gameFolder, inlineJson)
            return Plan.Detour(
                Intent(context, PluginCatalogActivity::class.java).apply {
                    putExtra(PluginCatalogActivity.EXTRA_GAME_PATH, gameFolder.absolutePath)
                    inlineJson?.let { putExtra(PluginCatalogActivity.EXTRA_CALLER_CONFIG, it) }
                    putExtra(PluginCatalogActivity.EXTRA_AUTOINSTALL, autoInstallPlugin)
                },
            )
        }
        if (!PluginTrustStore(context).isApproved(resolved.plugin)) {
            PendingPluginLaunchStore(context).save(gameFolder, inlineJson, resolved.plugin.bundleId)
            return Plan.Detour(
                Intent(context, PluginTrustActivity::class.java)
                    .putExtra(PluginTrustActivity.EXTRA_BUNDLE, resolved.plugin.bundleId),
            )
        }
        val runtimeClass = if (resolved.plugin.runtimeTransport == RUNTIME_TRANSPORT_ACTIVITY) {
            BundledActivityProxy::class.java
        } else {
            RuntimeActivity::class.java
        }
        val intent = Intent(context, runtimeClass).apply {
            putExtra(RuntimeActivity.EXTRA_PATH, gameFolder.absolutePath)
            putExtra(RuntimeActivity.EXTRA_PLUGIN_BUNDLE, resolved.plugin.bundleId)
            putExtra(RuntimeActivity.EXTRA_SAVE_PATH, SaveLocationStore(context).saveRootFor(config.engine).absolutePath)
            putExtra(RuntimeActivity.EXTRA_ENGINE, config.engine)
            putExtra(RuntimeActivity.EXTRA_ENGINE_CONTEXT, config.engineContext ?: DEFAULT_ENGINE_CONTEXT)
            putExtra(RuntimeActivity.EXTRA_ENGINE_VERSION, config.engineVersion.toString())
            putExtra(RuntimeActivity.EXTRA_RUNTIME_VERSION, resolved.capability.runtimeVersion.toString())
            putExtra(RuntimeActivity.EXTRA_CAPABILITY_ID, resolved.capability.id)
            putExtra(
                RuntimeActivity.EXTRA_RUNTIME_REQUIREMENTS,
                org.json.JSONObject(config.runtimeRequirements.mapValues { it.value.toString() }).toString(),
            )
            putExtra(
                RuntimeActivity.EXTRA_CONTROLLER_BINDINGS,
                ControllerBindingStore(context, config.engine).exportJson().toString(),
            )
            config.execFile?.let { putExtra(RuntimeActivity.EXTRA_EXEC_FILE, it) }
            config.options?.let { putExtra(RuntimeActivity.EXTRA_OPTIONS, it.toString()) }
            inlineJson?.let { putExtra(RuntimeActivity.EXTRA_CALLER_CONFIG, it) }
        }
        return Plan.Runtime(intent, config, resolved)
    }
}
