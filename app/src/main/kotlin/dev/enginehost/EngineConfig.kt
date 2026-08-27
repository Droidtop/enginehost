package dev.enginehost

import org.json.JSONObject
import org.json.JSONException
import java.io.File

/** The filename enginehost looks for at a game folder's own root. */
const val CONFIG_FILE_NAME = "enginehost.json"

/**
 * A game folder's own self-description of how to run it -- normally read
 * directly from `<folder>/enginehost.json`, so a caller only ever needs to
 * hand enginehost a folder path, never engine-specific metadata of its own
 * to keep in sync. A caller can supplement missing values with the same
 * JSON shape through [LaunchActivity]'s "config" extra, but cannot
 * override anything already specified by the folder -- see
 * [EngineConfigReader.resolve]. Real schema:
 *
 * ```json
 * {
 *   "engine": "rpgmvxace",
 *   "engineVersion": "1.4.0",
 *   "pluginVersion": "1.0.0,1.2.0-1.4.0",
 *   "execFile": "Game.exe",
 *   "options": {
 *     "rubyVersion": "1.9"
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
    /**
     * The real resolution order: a game folder's own `enginehost.json`
     * is authoritative, since it travels with the game and is the durable
     * source of truth. `inlineJson` (typically [LaunchActivity]'s
     * "config" extra) may append fields the folder omitted, including
     * missing nested `options` keys, but can never replace a value the
     * folder already contains. If there is no folder config, the inline
     * config is used by itself.
     */
    fun resolve(gameFolder: File, inlineJson: String?): EngineConfig {
        val configFile = File(gameFolder, CONFIG_FILE_NAME)
        val folderJson = configFile.takeIf { it.isFile }?.let { parseObject(it.readText(), CONFIG_FILE_NAME) }
        val callerJson = inlineJson?.let { parseObject(it, "inline config") }
        if (folderJson != null) return parse(mergeAuthoritative(folderJson, callerJson))
        if (callerJson != null) return parse(callerJson)
        throw InvalidEngineConfigException(
            "No $CONFIG_FILE_NAME in ${gameFolder.absolutePath} and no inline config was passed",
        )
    }

    private fun parseObject(raw: String, source: String): JSONObject = try {
        JSONObject(raw)
    } catch (e: JSONException) {
        throw InvalidEngineConfigException("Invalid $source JSON: ${e.message}")
    }

    /** Deep non-overriding merge: [authoritative] wins at every key. */
    private fun mergeAuthoritative(authoritative: JSONObject, fallback: JSONObject?): JSONObject {
        if (fallback == null) return authoritative
        val merged = JSONObject(fallback.toString())
        for (key in authoritative.keys()) {
            val authoritativeValue = authoritative.get(key)
            val fallbackValue = merged.opt(key)
            if (authoritativeValue is JSONObject && fallbackValue is JSONObject) {
                merged.put(key, mergeAuthoritative(authoritativeValue, fallbackValue))
            } else {
                merged.put(key, authoritativeValue)
            }
        }
        return merged
    }

    private fun parse(json: JSONObject): EngineConfig {
        val engine = json.optString("engine").takeIf { it.isNotBlank() }
            ?: throw InvalidEngineConfigException("Config missing required \"engine\" field")
        val engineVersionRaw = json.optString("engineVersion").takeIf { it.isNotBlank() }
            ?: throw InvalidEngineConfigException("Config missing required \"engineVersion\" field")
        return EngineConfig(
            engine = engine,
            engineVersion = try {
                Version.parse(engineVersionRaw)
            } catch (_: IllegalArgumentException) {
                throw InvalidEngineConfigException(
                    "Config field \"engineVersion\" must be a dotted numeric version",
                )
            },
            pluginVersionConstraint = json.optString("pluginVersion").takeIf { it.isNotBlank() }
                ?.let {
                    try {
                        VersionConstraint.parse(it)
                    } catch (_: IllegalArgumentException) {
                        throw InvalidEngineConfigException(
                            "Config field \"pluginVersion\" must be a comma-separated allowlist of dotted versions or ranges",
                        )
                    }
                },
            execFile = json.optString("execFile").takeIf { it.isNotBlank() },
            options = json.optJSONObject("options"),
        )
    }
}
