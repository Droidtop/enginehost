package dev.enginehost

import android.content.Context
import java.io.File

/**
 * One game's state in a line, as Home's card and the game's own screen
 * both show it: whether it can start, and if not, why. [engine] and [chip]
 * name the engine once the config has said which it is.
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
) {
    companion object {
        fun of(context: Context, folder: File): GameStatus {
            if (!folder.isDirectory) return GameStatus(false, context.getString(R.string.status_missing))
            val config = try {
                EngineConfigReader.resolve(folder, null)
            } catch (e: InvalidEngineConfigException) {
                return if (!File(folder, CONFIG_FILE_NAME).isFile) {
                    GameStatus(false, context.getString(R.string.status_no_config))
                } else {
                    GameStatus(false, context.getString(R.string.status_bad_config, e.message))
                }
            }
            val resolved = runCatching {
                PluginRegistry.resolve(
                    context, config.engine, config.engineContext, config.engineVersion,
                    config.runtimeRequirements, config.pluginVersionConstraint,
                )
            }.getOrNull()
            val chip = "${EngineNames.line(config.engine, config.engineContext)} ${config.engineVersion}"
            val text = context.getString(if (resolved == null) R.string.status_no_plugin_short else R.string.status_ready_short)
            return GameStatus(resolved != null, text, config.engine, chip, config.title)
        }
    }
}
