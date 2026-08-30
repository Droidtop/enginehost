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
 *   "engine": "rpgmaker",
 *   "engineContext": "vxace",
 *   "engineVersion": "1.4.0",
 *   "runtimeRequirements": { "ruby": "1.9.2" },
 *   "pluginVersion": "1.0.0,1.2.0-1.4.0",
 *   "execFile": "Game.exe",
 *   "options": {
 *     "rtpPaths": ["/storage/emulated/0/RTP/RPGVXAce"]
 *   }
 * }
 * ```
 *
 * `engine` and `engineVersion` are required. `engine` selects a plugin
 * family; optional `engineContext` selects a compatibility line within
 * that family. `engineVersion` is the game's real runtime target.
 * Compatibility must be explicitly advertised by a plugin capability;
 * numerical proximity alone is never enough. `pluginVersion` is an
 * optional comma-separated allowlist of exact versions and/or `lo-hi`
 * ranges constraining plugin *builds* (not engine versions) -- see
 * [VersionConstraint]. `execFile` is optional, the specific file within
 * the folder to run, for engines that need one rather than scanning it.
 * `runtimeRequirements` is an optional map of embedded component names to
 * exact dotted-numeric versions. It participates in plugin resolution: a
 * capability must advertise the same component/version before it can be
 * selected. This is how an RGSS game requests Ruby 1.9.2 rather than a
 * plugin compiled against Ruby 3.1, without teaching the host about Ruby.
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
    val engineContext: String?,
    val engineVersion: Version,
    val runtimeRequirements: Map<String, Version>,
    val pluginVersionConstraint: VersionConstraint?,
    val execFile: String?,
    val options: JSONObject?,
)

class InvalidEngineConfigException(message: String) : Exception(message)

object EngineConfigReader {
    /** Parse and validate one complete config document without resolving a folder. */
    fun parseDocument(raw: String): EngineConfig = parse(parseObject(raw, CONFIG_FILE_NAME))

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
            engineContext = json.optString("engineContext").takeIf { it.isNotBlank() },
            engineVersion = try {
                Version.parse(engineVersionRaw)
            } catch (_: IllegalArgumentException) {
                throw InvalidEngineConfigException(
                    "Config field \"engineVersion\" must be a dotted numeric version",
                )
            },
            runtimeRequirements = parseRuntimeRequirements(json),
            pluginVersionConstraint = if (json.has("pluginVersion")) {
                val rawPluginVersion = json.optString("pluginVersion")
                try {
                    VersionConstraint.parse(rawPluginVersion)
                } catch (_: IllegalArgumentException) {
                    throw InvalidEngineConfigException(
                        "Config field \"pluginVersion\" must be a non-empty comma-separated allowlist of dotted versions or ranges",
                    )
                }
            } else {
                null
            },
            execFile = json.optString("execFile").takeIf { it.isNotBlank() },
            options = json.optJSONObject("options"),
        )
    }

    private fun parseRuntimeRequirements(json: JSONObject): Map<String, Version> {
        if (!json.has("runtimeRequirements")) return emptyMap()
        val requirements = json.optJSONObject("runtimeRequirements")
            ?: throw InvalidEngineConfigException(
                "Config field \"runtimeRequirements\" must be an object of component names to dotted versions",
            )
        return buildMap {
            for (name in requirements.keys()) {
                if (name.isBlank()) {
                    throw InvalidEngineConfigException("Runtime requirement names must not be blank")
                }
                val rawVersion = requirements.optString(name).takeIf { it.isNotBlank() }
                    ?: throw InvalidEngineConfigException(
                        "Runtime requirement \"$name\" must be a dotted numeric version",
                    )
                val version = try {
                    Version.parse(rawVersion)
                } catch (_: IllegalArgumentException) {
                    throw InvalidEngineConfigException(
                        "Runtime requirement \"$name\" must be a dotted numeric version",
                    )
                }
                put(name, version)
            }
        }
    }
}
