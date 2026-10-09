package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Where an option's value on the game's page comes from. */
enum class OptionSource { ENGINE_DEFAULT, GAME_FOLDER, THIS_GAME }

/** One option the game's core declares, with the value this game runs on and where that value comes from. */
class OptionRow(val option: DeclaredOption, val value: Any?, val source: OptionSource)

/** Where a game's saves are, and what is known about them without reading anything large. */
class SavesInfo(
    /** The engine writes next to the game, so the place is the game's own folder and nothing is measured. */
    val besideGame: Boolean,
    val folder: File?,
    val lastWrittenAt: Long,
    val bytes: Long,
)

/**
 * Everything the game's page shows under its status, read off the main thread
 * (Droidtop/tracker#235): the core that runs it and the other builds that
 * could, whether a newer build of that core is waiting, the core's declared
 * options with their sources, where the saves are, and the play history.
 */
class GameSections(
    val core: InstalledPlugin?,
    /** The `pluginVersion` the person fixed for this game, or null when the newest compatible build runs it. */
    val pinned: String?,
    val compatible: List<InstalledPlugin>,
    val update: AvailablePlugin?,
    val options: List<OptionRow>,
    val saves: SavesInfo?,
    val history: PlayHistory,
    /** The game's engine scope for the controller screen, and whether this game has buttons of its own. */
    val controlsScope: String? = null,
    val ownControls: Boolean = false,
)

object GameSectionsLoader {
    /** At most this many entries are looked at when measuring a save folder, so a huge one cannot stall the page. */
    private const val MEASURE_LIMIT = 5000

    fun load(context: Context, folder: File, status: GameStatus): GameSections {
        val library = GameLibraryStore(context.applicationContext)
        val history = runCatching { library.historyOf(folder) }.getOrDefault(PlayHistory())
        val config = status.config
            ?: return GameSections(null, null, emptyList(), null, emptyList(), null, history)
        val overrides = runCatching { library.overridesFor(folder) }.getOrNull()
        val pinned = GameOverrides.valueOf(overrides, "pluginVersion")?.toString()
        val core = status.core
        val compatible = runCatching { PluginRegistry.compatible(context, config) }.getOrDefault(emptyList())
        val controlsScope = ControllerScope.of(config.engine, config.engineContext)
        return GameSections(
            core = core,
            pinned = pinned,
            compatible = compatible,
            update = if (core != null && pinned == null) newerBuild(context, core) else null,
            options = optionRows(context, folder, config, core, overrides),
            saves = runCatching { savesOf(context, folder, config) }.getOrNull(),
            history = history,
            controlsScope = controlsScope,
            ownControls = runCatching {
                ControllerBindingStore(context, controlsScope, folder.absolutePath).hasGameBindings()
            }.getOrDefault(false),
        )
    }

    /** A newer build of [core] in the catalogs already on the device; reads them, so not for the main thread. */
    private fun newerBuild(context: Context, core: InstalledPlugin): AvailablePlugin? =
        runCatching { PluginUpdateCheck(context).pending() }.getOrDefault(emptyList())
            .filter { it.bundleId == core.bundleId && PluginUpdates.isNewerBuildOf(core, it.manifest) }
            .maxByOrNull { it.info.pluginVersion }

    private fun optionRows(
        context: Context,
        folder: File,
        config: EngineConfig,
        core: InstalledPlugin?,
        overrides: String?,
    ): List<OptionRow> {
        val folderOptions = runCatching {
            JSONObject(File(folder, CONFIG_FILE_NAME).readText()).optJSONObject("options")
        }.getOrNull()
        return DeclaredOptionsReader.forResolvedBundle(context, core, config.engine).map { option ->
            val source = when {
                GameOverrides.valueOf(overrides, "options.${option.key}") != null -> OptionSource.THIS_GAME
                folderOptions?.has(option.key) == true -> OptionSource.GAME_FOLDER
                else -> OptionSource.ENGINE_DEFAULT
            }
            OptionRow(option, config.options?.opt(option.key), source)
        }
    }

    private fun savesOf(context: Context, folder: File, config: EngineConfig): SavesInfo {
        val place = SaveFolders.placeOf(config.engine, config.engineContext)
        if (place == SaveFolders.Place.BESIDE_THE_GAME) return SavesInfo(true, folder, 0L, -1L)
        val saves = SaveLocationStore(context).saveFolderFor(config)
        var latest = 0L
        var bytes = 0L
        var seen = 0
        for (file in saves.walkTopDown()) {
            if (++seen > MEASURE_LIMIT) break
            if (!file.isFile) continue
            latest = maxOf(latest, file.lastModified())
            bytes += file.length()
        }
        return SavesInfo(false, saves, latest, bytes)
    }
}
