package dev.enginehost

import android.content.Context
import androidx.annotation.StringRes
import java.io.File

/**
 * What a game's one primary button does right now (Droidtop/tracker#235): the
 * state of the game decides the button, so the next step is never a sentence
 * that says "press Play and it will offer one". A button that cannot act is
 * disabled and says why in its label.
 */
enum class GamePrimary(@StringRes val label: Int, val enabled: Boolean = true) {
    /** Start the game. */
    PLAY(R.string.action_launch),

    /** The engine's core is not installed; the launch plan sends the person to install it. */
    GET_CORE(R.string.action_get_core),

    /** The core is installed but not approved yet; the launch plan sends the person to approve it. */
    APPROVE_CORE(R.string.action_approve_core),

    /** Detection left a question open, or the game's own config is unusable: Game setup comes first. */
    SET_UP(R.string.action_set_up),

    /** The game's folder is not there. */
    FOLDER_MISSING(R.string.action_folder_missing, enabled = false),

    /** An engine nothing here runs. */
    NOT_SUPPORTED(R.string.action_not_supported, enabled = false),
    ;

    companion object {
        /** The button for a game whose config resolved: [resolved] is whether a core fits, [approved] whether it may run. */
        fun forResolved(resolved: Boolean, approved: Boolean): GamePrimary = when {
            !resolved -> GET_CORE
            !approved -> APPROVE_CORE
            else -> PLAY
        }
    }
}

/**
 * One game's state in a line, as the game's own screen shows it: whether it
 * can start, and if not, why. [engine] and [chip]
 * name the engine once the config has said which it is, and [config] is
 * that resolved config, so a caller that also needs the folder's own
 * answers (the game's art, say) does not resolve the same file twice.
 * [primary] is the state the screen's one primary button takes, and
 * [core] is the core build that will run it.
 *
 * Resolution reads the disk and the plugin registry, so callers run
 * [of] off the UI thread.
 */
class GameStatus(
    val ok: Boolean,
    val text: String,
    val engine: String? = null,
    val chip: String = "",
    val title: String? = null,
    val config: EngineConfig? = null,
    val primary: GamePrimary = GamePrimary.PLAY,
    val core: InstalledPlugin? = null,
) {
    companion object {
        fun of(context: Context, folder: File): GameStatus {
            if (!folder.isDirectory) {
                return GameStatus(false, context.getString(R.string.status_missing), primary = GamePrimary.FOLDER_MISSING)
            }
            val overrides = runCatching { GameLibraryStore(context).overridesFor(folder) }.getOrNull()
            val config = try {
                EngineConfigReader.resolve(folder, null, null, overrides)
            } catch (e: InvalidEngineConfigException) {
                if (File(folder, CONFIG_FILE_NAME).isFile) {
                    return GameStatus(false, context.getString(R.string.status_bad_config, e.message), primary = GamePrimary.SET_UP)
                }
                // No config of its own: Play runs on what detection implies, unless detection
                // cannot say (Game setup asks) or the engine is one nothing here runs.
                return when (val detected = DetectedConfig.forLaunch(context, folder, null)) {
                    is DetectedConfig.Launch.Document ->
                        GameStatus(false, context.getString(R.string.status_no_config))
                    is DetectedConfig.Launch.Unhosted ->
                        GameStatus(false, UnhostedEngine.explain(context, detected.detection), primary = GamePrimary.NOT_SUPPORTED)
                    DetectedConfig.Launch.Open ->
                        GameStatus(false, context.getString(R.string.status_needs_setup), primary = GamePrimary.SET_UP)
                }
            }
            val resolved = runCatching {
                PluginRegistry.resolve(
                    context, config.engine, config.engineContext, config.engineVersion,
                    config.runtimeRequirements, config.pluginVersionConstraint,
                )
            }.getOrNull()
            val approved = resolved != null && PluginTrustStore(context).isApproved(resolved.plugin)
            val primary = GamePrimary.forResolved(resolved != null, approved)
            val chip = "${EngineNames.line(config.engine, config.engineContext)} ${config.engineVersion}"
            val text = context.getString(
                when (primary) {
                    GamePrimary.GET_CORE -> R.string.status_no_plugin_short
                    GamePrimary.APPROVE_CORE -> R.string.status_needs_approval
                    else -> R.string.status_ready_short
                },
            )
            return GameStatus(resolved != null && approved, text, config.engine, chip, config.title, config, primary, resolved?.plugin)
        }
    }
}
