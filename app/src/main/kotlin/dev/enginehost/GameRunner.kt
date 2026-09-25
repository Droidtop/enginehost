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

        /**
         * The runtime can start now; [intent] enters the `:runtime` process.
         * [earlierSaves] are this game's saves an earlier Enginehost kept
         * elsewhere, for the person to bring over first or leave.
         */
        class Runtime(
            val intent: Intent,
            val config: EngineConfig,
            val resolved: ResolvedPlugin,
            val earlierSaves: EarlierSaves? = null,
        ) : Plan()

        /** The game cannot start; [retry] when trying again can help once the person has fixed what [message] names. */
        class Failure(val message: String, val retry: Boolean = false) : Plan()
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
        LaunchActivity.start(context, gameFolder, inlineJson, autoInstallPlugin)
    }

    fun plan(
        context: Context,
        gameFolder: File,
        inlineJson: String? = null,
        autoInstallPlugin: Boolean = false,
    ): Plan {
        // Before anything reads the folder or starts a runtime: a game folder
        // that is not there (a card that is not mounted, a folder moved or
        // deleted since it was added) is a sentence, not a crash. Checked
        // nowhere else, the Ren'Py runtime found it itself on its engine
        // thread and aborted the process through a JNI pending exception
        // (rig, 2026-09-24), and every other engine had its own outcome.
        // Without the all-files grant (a fresh install started straight from
        // a frontend) Enginehost can neither read a game on shared storage nor
        // make its save folder, and the folder check below would wrongly call
        // a present folder missing. Asked first, for that reason.
        if (!StorageFolder.hasNativePathAccess()) {
            return Plan.Failure(context.getString(R.string.launch_needs_all_files), retry = true)
        }
        if (!gameFolder.isDirectory) {
            return Plan.Failure(context.getString(R.string.launch_folder_missing, gameFolder.absolutePath), retry = true)
        }
        // A folder with no config of its own runs on what its files imply,
        // in memory: detection fills the config, the caller's inline config
        // fills what detection leaves, and the result travels to the runtime
        // as this launch's inline config. Nothing is written into the game
        // folder; only Game setup does that, when the person saves. Only a
        // folder that leaves a question open goes to the editor.
        val hasFolderConfig = File(gameFolder, CONFIG_FILE_NAME).isFile
        val launchJson = if (hasFolderConfig) {
            inlineJson
        } else {
            DetectedConfig.forLaunch(context, gameFolder, inlineJson)
                ?: return Plan.Detour(
                    Intent(context, ConfigEditorActivity::class.java).apply {
                        putExtra(ConfigEditorActivity.EXTRA_PATH, gameFolder.absolutePath)
                        inlineJson?.let { putExtra(ConfigEditorActivity.EXTRA_CONFIG, it) }
                    },
                    notice = R.string.launch_needs_config,
                )
        }
        val config = try {
            EngineConfigReader.resolve(gameFolder, launchJson)
        } catch (e: InvalidEngineConfigException) {
            return Plan.Failure(e.message ?: "Invalid $CONFIG_FILE_NAME")
        }
        // The save root can be on a card that is not mounted, or a folder
        // chosen once and gone since; that is a sentence too, not an
        // exception out of the launch screen.
        val saves = SaveLocationStore(context)
        val saveFolder = try {
            saves.saveFolderFor(config)
        } catch (e: UnusableSaveFolderException) {
            return Plan.Failure(context.getString(R.string.launch_save_folder_unusable, e.folder.absolutePath), retry = true)
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
                    launchJson?.let { putExtra(PluginCatalogActivity.EXTRA_CALLER_CONFIG, it) }
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
            putExtra(RuntimeActivity.EXTRA_SAVE_PATH, saveFolder.absolutePath)
            putExtra(RuntimeActivity.EXTRA_ENGINE, config.engine)
            putExtra(RuntimeActivity.EXTRA_ENGINE_CONTEXT, config.engineContext ?: DEFAULT_ENGINE_CONTEXT)
            putExtra(RuntimeActivity.EXTRA_ENGINE_VERSION, config.engineVersion.toString())
            putExtra(RuntimeActivity.EXTRA_RUNTIME_VERSION, resolved.capability.runtimeVersion.toString())
            putExtra(RuntimeActivity.EXTRA_CAPABILITY_ID, resolved.capability.id)
            putExtra(
                RuntimeActivity.EXTRA_RUNTIME_REQUIREMENTS,
                org.json.JSONObject(config.runtimeRequirements.mapValues { it.value.toString() }).toString(),
            )
            // A bypassed engine reads the pad itself, and the absence of
            // this extra is how it is told so: no map, no host bindings,
            // the engine's own handling and nothing beside it.
            ControllerBindingStore(context, ControllerScope.of(config.engine, config.engineContext))
                .takeUnless { it.isBypassed() }
                ?.let { putExtra(RuntimeActivity.EXTRA_CONTROLLER_BINDINGS, it.exportJson().toString()) }
            config.execFile?.let { putExtra(RuntimeActivity.EXTRA_EXEC_FILE, it) }
            config.options?.let { putExtra(RuntimeActivity.EXTRA_OPTIONS, it.toString()) }
            launchJson?.let { putExtra(RuntimeActivity.EXTRA_CALLER_CONFIG, it) }
        }
        return Plan.Runtime(intent, config, resolved, saves.earlierSavesFor(config, gameFolder))
    }
}
