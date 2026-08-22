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
 *   "engine": "rpgmvxace",
 *   "engineVersion": "1.4.0",
 *   "pluginVersion": "1.0.0,1.2.0-1.4.0",
 *   "execFile": "Game.exe",
 *   "options": {
 *     "rubyVersion": "1.9",
 *     "encryptionKey": "..."
 *   }
 * }
 * ```
 *
 * `engine` and `engineVersion` are required. `engineVersion` is the real
 * underlying engine's own version this game needs -- [EngineRegistry]
 * tries an exact match first, then the nearest installed one. `pluginVersion`
 * is optional: a comma-separated list of exact versions and/or `lo-hi`
 * ranges constraining which *plugin builds* (not engine versions) are
 * trusted for this specific game -- see [VersionConstraint]. `execFile` is
 * optional, the specific file within the folder to run, for engines that
 * need one rather than just scanning the folder itself.
 *
 * `options` is a deliberately generic, opaque-to-enginehost bag of
 * engine-specific settings -- the real motivating case: an RGSS game
 * (RPG Maker XP/VX/VX Ace) can need a specific Ruby/Marshal-format
 * version to correctly deserialize its own .rxdata/.rvdata scripts, or a
 * specific decryption key, or RTP dependency info, none of which
 * enginehost itself has any business understanding. enginehost passes
 * this straight through to whichever plugin gets resolved (as a raw JSON
 * string extra) without inspecting it -- each plugin defines its own real
 * option keys.
 */
data class EngineConfig(
    val engine: String,
    val engineVersion: Version,
    val pluginVersionConstraint: VersionConstraint?,
    val execFile: String?,
    val options: JSONObject?,
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
        val engineVersionRaw = json.optString("engineVersion").takeIf { it.isNotBlank() }
            ?: throw InvalidEngineConfigException("$CONFIG_FILE_NAME missing required \"engineVersion\" field")
        return EngineConfig(
            engine = engine,
            engineVersion = Version.parse(engineVersionRaw),
            pluginVersionConstraint = json.optString("pluginVersion").takeIf { it.isNotBlank() }
                ?.let { VersionConstraint.parse(it) },
            execFile = json.optString("execFile").takeIf { it.isNotBlank() },
            options = json.optJSONObject("options"),
        )
    }
}
