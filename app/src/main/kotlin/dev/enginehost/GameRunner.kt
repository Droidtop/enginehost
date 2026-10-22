package dev.enginehost

import android.content.Context
import android.content.Intent
import android.util.Log
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
            /** Where the game's saves are: the game folder for an engine that saves beside it, else its save folder. */
            val savesIn: java.io.File? = null,
            /** On a launch flagged [SaveRoot.SYNCED_KEY], why its `saveRoot` was not taken; null when it was. */
            val saveRootRefused: String? = null,
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
        testing: Boolean = false,
        caller: LaunchCaller = LaunchCaller.InApp,
    ) {
        if (caller !is LaunchCaller.InApp) {
            Log.i("enginehost", "LAUNCH from ${caller.storeKey}")
        }
        LaunchActivity.start(context, gameFolder, inlineJson, autoInstallPlugin, testing, caller)
    }

    /**
     * [testing]: this launch is Game setup's Test, and runs on the game's
     * pending testing configuration in place of its enginehost.json (see
     * [TestingConfigStore]); every other launch runs the working config.
     */
    fun plan(
        context: Context,
        gameFolder: File,
        inlineJson: String? = null,
        autoInstallPlugin: Boolean = false,
        testing: Boolean = false,
    ): Plan {
        val read = when (val step = readConfig(context, gameFolder, inlineJson, testing)) {
            is ConfigRead.Stop -> return step.plan
            is ConfigRead.Ready -> step
        }
        val config = read.config
        val launchJson = read.launchJson
        val testingJson = read.testingJson
        val overridesJson = read.overridesJson
        // The save root can be on a card that is not mounted, or a folder
        // chosen once and gone since; that is a sentence too, not an
        // exception out of the launch screen.
        val saves = SaveLocationStore(context)
        // A caller's own save folder for this launch, when it named one that
        // is taken (docs/contract.md "A caller's save folder"): the game's own
        // save folder, exactly as the caller keeps it. Enginehost is the
        // abstraction: the engine uses it as is and adds no folder inside it
        // (owner, 2026-10-18). An engine that saves beside the game keeps doing so.
        val besideTheGame = SaveFolders.placeOf(config.engine, config.engineContext) == SaveFolders.Place.BESIDE_THE_GAME
        val rootCheck = SaveRoot.check(inlineJson)
        // A caller that syncs this game's saves must never see them go to the
        // person's default location: when its folder is not taken, the game
        // gets a folder of its own in the person's location, used the same
        // way, and the game-ended report says why (owner, 2026-10-22,
        // Droidtop/tracker#580). The person is asked nothing.
        val synced = SaveRoot.synced(inlineJson) && !besideTheGame
        val saveRootRefused = (rootCheck as? SaveRoot.Check.Refused)?.reason?.takeIf { synced }
        val callerRoot = try {
            when {
                besideTheGame -> null
                rootCheck is SaveRoot.Check.Taken -> rootCheck.folder
                synced -> saves.syncedFolderFor(config.engine, gameFolder)
                else -> null
            }
        } catch (e: UnusableSaveFolderException) {
            return Plan.Failure(context.getString(R.string.launch_save_folder_unusable, e.folder.absolutePath), retry = true)
        }
        val saveFolder = try {
            callerRoot ?: saves.saveFolderFor(config)
        } catch (e: UnusableSaveFolderException) {
            return Plan.Failure(context.getString(R.string.launch_save_folder_unusable, e.folder.absolutePath), retry = true)
        } catch (e: IllegalArgumentException) {
            return Plan.Failure(ErrorText.of(context, e, R.string.launch_save_folder_invalid_name))
        }
        val resolved = PluginRegistry.resolve(
            context, config.engine, config.engineContext, config.engineVersion,
            config.runtimeRequirements, config.pluginVersionConstraint,
        )
        if (resolved == null) {
            // An installed engine that only lacks a component the game needs:
            // when that component is already installed and waiting for the
            // person to accept its licence, that is the one step left. Any
            // other case goes to the catalog, which offers the component
            // bundle (or an engine) for this game.
            val missing = PluginRegistry.missingComponents(
                context, config.engine, config.engineContext, config.engineVersion,
                config.runtimeRequirements, config.pluginVersionConstraint,
            )
            val trust = PluginTrustStore(context)
            val waiting = PluginRegistry.components(context).firstOrNull { installed ->
                val component = installed.component ?: return@firstOrNull false
                trust.state(installed) == PluginTrustState.PENDING && missing[component.name] == component.version
            }
            if (waiting != null) {
                PendingPluginLaunchStore(context).save(gameFolder, inlineJson, testing)
                return Plan.Detour(
                    Intent(context, PluginTrustActivity::class.java)
                        .putExtra(PluginTrustActivity.EXTRA_BUNDLE, waiting.bundleId)
                        .putExtra(PendingPluginLaunchStore.EXTRA_RESUMES, true),
                )
            }
            PendingPluginLaunchStore(context).save(gameFolder, inlineJson, testing)
            return Plan.Detour(
                Intent(context, PluginCatalogActivity::class.java).apply {
                    putExtra(PendingPluginLaunchStore.EXTRA_RESUMES, true)
                    putExtra(PluginCatalogActivity.EXTRA_GAME_PATH, gameFolder.absolutePath)
                    launchJson?.let { putExtra(PluginCatalogActivity.EXTRA_CALLER_CONFIG, it) }
                    putExtra(PluginCatalogActivity.EXTRA_AUTOINSTALL, autoInstallPlugin)
                },
            )
        }
        if (!PluginTrustStore(context).isApproved(resolved.plugin)) {
            PendingPluginLaunchStore(context).save(gameFolder, inlineJson, testing)
            return Plan.Detour(
                Intent(context, PluginTrustActivity::class.java)
                    .putExtra(PluginTrustActivity.EXTRA_BUNDLE, resolved.plugin.bundleId)
                    .putExtra(PendingPluginLaunchStore.EXTRA_RESUMES, true),
            )
        }
        // The config's execFile, when specified, must exist under the game folder.
        // Check upfront so a missing file produces a clear failure instead of a
        // later crash or silent black screen in the runtime.
        missingExecFile(gameFolder, config)?.let { execFile ->
            return Plan.Failure(context.getString(R.string.launch_exec_file_missing, execFile), retry = false)
        }
        val runtimeClass = if (resolved.plugin.runtimeTransport == RUNTIME_TRANSPORT_ACTIVITY) {
            BundledActivityProxy::class.java
        } else {
            RuntimeActivity::class.java
        }
        val intent = Intent(context, runtimeClass).apply {
            putExtra(RuntimeActivity.EXTRA_PATH, gameFolder.absolutePath)
            putExtra(RuntimeActivity.EXTRA_PLUGIN_BUNDLE, resolved.plugin.bundleId)
            // Which build: several builds of one line can be installed, and the
            // runtime must load the one planned and approved here.
            putExtra(RuntimeActivity.EXTRA_PLUGIN_ARCHIVE, resolved.plugin.archiveSha256)
            putExtra(RuntimeActivity.EXTRA_SAVE_PATH, saveFolder.absolutePath)
            callerRoot?.let { putExtra(RuntimeActivity.EXTRA_GAME_SAVE_PATH, it.absolutePath) }
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
            ControllerBindingStore(context, ControllerScope.of(config.engine, config.engineContext), gameFolder.absolutePath)
                .takeUnless { it.isBypassed() }
                ?.let { putExtra(RuntimeActivity.EXTRA_CONTROLLER_BINDINGS, it.exportJson().toString()) }
            config.execFile?.let { putExtra(RuntimeActivity.EXTRA_EXEC_FILE, it) }
            config.options?.let { putExtra(RuntimeActivity.EXTRA_OPTIONS, it.toString()) }
            launchJson?.let { putExtra(RuntimeActivity.EXTRA_CALLER_CONFIG, it) }
            testingJson?.let { putExtra(RuntimeActivity.EXTRA_TESTING_CONFIG, it) }
            overridesJson?.let { putExtra(RuntimeActivity.EXTRA_OVERRIDES, it) }
        }
        val savesIn = if (besideTheGame) gameFolder else saveFolder
        // Earlier saves are offered from the person's own save location only.
        return Plan.Runtime(
            intent, config, resolved, if (callerRoot == null) saves.earlierSavesFor(config, gameFolder) else null, savesIn,
            saveRootRefused,
        )
    }

    /** What [readConfig] found: the config a launch runs on, or the plan that stops it first. */
    private sealed class ConfigRead {
        class Ready(
            val config: EngineConfig,
            val launchJson: String?,
            val testingJson: String?,
            val overridesJson: String?,
        ) : ConfigRead()

        class Stop(val plan: Plan) : ConfigRead()
    }

    /**
     * The config a launch of [gameFolder] runs on: its enginehost.json, or
     * (Test) its testing configuration, or for a folder with neither what
     * its files imply, in memory; the caller's inline config and the
     * person's overrides on top. Reads only: nothing is written, no pending
     * launch is kept and no save folder is made, so the game page and the
     * pending-launch check share it with [plan].
     */
    private fun readConfig(context: Context, gameFolder: File, inlineJson: String?, testing: Boolean): ConfigRead {
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
            return ConfigRead.Stop(Plan.Failure(context.getString(R.string.launch_needs_all_files), retry = true))
        }
        if (!gameFolder.isDirectory) {
            return ConfigRead.Stop(Plan.Failure(context.getString(R.string.launch_folder_missing, gameFolder.absolutePath), retry = true))
        }
        // A folder with no config of its own runs on what its files imply,
        // in memory: detection fills the config, the caller's inline config
        // fills what detection leaves, and the result travels to the runtime
        // as this launch's inline config. Nothing is written into the game
        // folder; only Game setup does that, when the person saves. Only a
        // folder that leaves a question open goes to the editor.
        val testingJson = if (testing) {
            TestingConfigStore.of(context).get(gameFolder)?.config?.toString()
                ?: return ConfigRead.Stop(Plan.Failure(context.getString(R.string.testing_config_gone)))
        } else {
            null
        }
        val hasFolderConfig = testingJson != null || File(gameFolder, CONFIG_FILE_NAME).isFile
        // An engine nothing here runs (Unity above all) is said plainly,
        // with why, rather than sent to Game setup to be asked questions
        // no answer to which could make it start.
        val launchJson = if (hasFolderConfig) {
            inlineJson
        } else {
            when (val detected = DetectedConfig.forLaunch(context, gameFolder, inlineJson)) {
                is DetectedConfig.Launch.Document -> detected.json
                is DetectedConfig.Launch.Unhosted ->
                    return ConfigRead.Stop(Plan.Failure(UnhostedEngine.explain(context, detected.detection)))
                DetectedConfig.Launch.Open -> return ConfigRead.Stop(
                    Plan.Detour(
                        Intent(context, ConfigEditorActivity::class.java).apply {
                            putExtra(ConfigEditorActivity.EXTRA_PATH, gameFolder.absolutePath)
                            inlineJson?.let { putExtra(ConfigEditorActivity.EXTRA_CONFIG, it) }
                        },
                        notice = R.string.launch_needs_config,
                    ),
                )
            }
        }
        // The person's own choices for this game, from the library database; layered over the folder's file.
        val overridesJson = runCatching { GameLibraryStore(context).overridesFor(gameFolder) }.getOrNull()
        val config = try {
            EngineConfigReader.resolve(gameFolder, launchJson, testingJson, overridesJson)
        } catch (e: InvalidEngineConfigException) {
            return ConfigRead.Stop(Plan.Failure(e.message ?: "Invalid $CONFIG_FILE_NAME"))
        }
        return ConfigRead.Ready(config, launchJson, testingJson, overridesJson)
    }

    /** The config [plan] would run [gameFolder] on, or null where [plan] stops or detours before choosing a core. */
    fun launchConfig(context: Context, gameFolder: File, inlineJson: String? = null, testing: Boolean = false): EngineConfig? =
        (readConfig(context, gameFolder, inlineJson, testing) as? ConfigRead.Ready)?.config

    /**
     * Whether [plugin], now approved, is what a launch of [pending]'s game
     * resolves to: the core itself, or a component that launch loads.
     * Approving or installing an unrelated core must not start the game a
     * person left earlier (review, 2026-10-16).
     */
    fun resolvesWith(context: Context, pending: PendingPluginLaunch, plugin: InstalledPlugin): Boolean {
        val config = launchConfig(context, File(pending.gamePath), pending.callerConfig, pending.testing) ?: return false
        val resolved = PluginRegistry.resolve(
            context, config.engine, config.engineContext, config.engineVersion,
            config.runtimeRequirements, config.pluginVersionConstraint,
        ) ?: return false
        return (listOf(resolved.plugin) + resolved.components).any { it.archiveSha256 == plugin.archiveSha256 }
    }

    /**
     * After [plugin] was installed or approved: the pending launch it lets
     * start, taken from the store, or null when there is none, it is stale,
     * [plugin] is not approved, or the game does not resolve to it. Reads
     * bundles; call it off the main thread.
     */
    fun pendingLaunchFor(context: Context, plugin: InstalledPlugin): PendingPluginLaunch? {
        val store = PendingPluginLaunchStore(context)
        val pending = store.peek() ?: return null
        if (!PluginTrustStore(context).isApproved(plugin) || !resolvesWith(context, pending, plugin)) return null
        return store.take()
    }

    /** Starts [pending]'s game, as the launch that stopped for a core would have. */
    fun resume(context: Context, pending: PendingPluginLaunch) =
        run(context, File(pending.gamePath), pending.callerConfig, testing = pending.testing)

    /**
     * The entry file [config] names that [gameFolder] does not have, for an
     * engine that needs one file rather than the folder scanned: the reason
     * this launch cannot run, or null when it may proceed. Held apart from
     * [plan] and free of the Context so the preflight can be unit tested;
     * this app has no Robolectric, so [plan] itself cannot be. [plan] turns
     * a non-null answer into a Plan.Failure sentence, not a crash.
     */
    fun missingExecFile(gameFolder: File, config: EngineConfig): String? =
        config.execFile?.takeIf { !File(gameFolder, it).isFile }
}
