package dev.enginehost

import org.json.JSONObject
import java.io.File

/** The filename enginehost looks for at a game folder's own root. */
const val CONFIG_FILE_NAME = "enginehost.json"

/**
 * A game folder's own self-description of how to run it -- read directly
 * from `<folder>/enginehost.json`, not supplied by whatever caller launched
 * enginehost. This is the whole point of the design: a caller only ever
 * needs to hand enginehost a folder path, never engine-specific metadata of
 * its own to keep in sync. Real schema:
 *
 * ```json
 * {
 *   "engine": "kirikiri2",
 *   "execFile": "startup.tjs"
 * }
 * ```
 *
 * `engine` is required and must match a key [EngineRegistry] knows about.
 * `execFile` is optional -- the specific file within the folder to run,
 * for engines that need one rather than just scanning the folder itself
 * (KiriKiri doesn't strictly need it; RPG Maker/Ren'Py engines might).
 */
data class EngineConfig(
    val engine: String,
    val execFile: String?,
)

class InvalidEngineConfigException(message: String) : Exception(message)

object EngineConfigReader {
    fun read(gameFolder: File): EngineConfig {
        val configFile = File(gameFolder, CONFIG_FILE_NAME)
        if (!configFile.isFile) {
            throw InvalidEngineConfigException("No $CONFIG_FILE_NAME in ${gameFolder.absolutePath}")
        }
        val json = JSONObject(configFile.readText())
        val engine = json.optString("engine").takeIf { it.isNotBlank() }
            ?: throw InvalidEngineConfigException("$CONFIG_FILE_NAME missing required \"engine\" field")
        return EngineConfig(
            engine = engine,
            execFile = json.optString("execFile").takeIf { it.isNotBlank() },
        )
    }
}
