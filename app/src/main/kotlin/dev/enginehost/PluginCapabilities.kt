package dev.enginehost

import org.json.JSONObject

const val DEFAULT_ENGINE_CONTEXT = "default"

data class VersionRange(val min: Version, val max: Version) {
    init {
        require(min <= max) { "Version range minimum must not exceed maximum" }
    }

    fun contains(version: Version): Boolean = version in min..max
    fun width(): Long = min.distanceTo(max)
}

/** One runtime bundled by a plugin build, with explicit game compatibility. */
/**
 * Where controller input is handled for a capability.
 *
 * [NATIVE] engines have first-class controller support of their own
 * (Ren'Py, Godot). The host must NOT consume their events: returning
 * false from onControllerEvent lets Android's normal dispatch reach the
 * engine's own view, which is what makes them work. Host remapping does
 * not apply, and the engine's own controller settings do.
 *
 * [HOST] engines have no controller handling worth the name and consume
 * the host's normalised, remapped actions instead -- KiriKiri's Cocos
 * runtime being the motivating case. These are the ones a wrapper has to
 * translate into the engine's native input system.
 */
enum class ControllerInput { NATIVE, HOST }

data class EngineCapability(
    val id: String,
    val engineContext: String,
    val runtimeVersion: Version,
    val supportedVersions: Set<Version>,
    val supportedSeries: Set<VersionSeries>,
    val supportedRanges: List<VersionRange>,
    val runtimeComponents: Map<String, Version> = emptyMap(),
    val acceptsAnyEngineVersion: Boolean = false,
    /**
     * Defaults to NATIVE, which is honest about every bundle shipped so
     * far: none implements onControllerEvent, so all of them currently
     * fall through to their engine's own handling.
     */
    val controllerInput: ControllerInput = ControllerInput.NATIVE,
) {
    fun supports(version: Version): Boolean =
        acceptsAnyEngineVersion || version == runtimeVersion || version in supportedVersions ||
            supportedSeries.any(version::belongsTo) || supportedRanges.any { it.contains(version) }

    fun specificityFor(version: Version): Long = when {
        acceptsAnyEngineVersion -> Long.MAX_VALUE
        version == runtimeVersion || version in supportedVersions -> 0L
        supportedSeries.any(version::belongsTo) ->
            1_000_000L - supportedSeries.filter(version::belongsTo).maxOf { it.parts.size }
        else -> 2_000_000L + (supportedRanges.filter { it.contains(version) }.minOfOrNull { it.width() }
            ?: Long.MAX_VALUE - 2_000_000L)
    }

    fun satisfies(requirements: Map<String, Version>): Boolean =
        requirements.all { (name, version) -> runtimeComponents[name] == version }
}

object PluginCapabilitiesReader {
    fun parse(raw: String): List<EngineCapability> {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion", 1) == 1) { "Unsupported capability schema version" }
        val entries = root.optJSONArray("capabilities")
            ?: throw IllegalArgumentException("Capability document missing capabilities array")
        require(entries.length() > 0) { "Capability document must not be empty" }

        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.requiredString("id")
                val context = entry.optString("engineContext")
                    .takeIf { it.isNotBlank() } ?: DEFAULT_ENGINE_CONTEXT
                val runtimeVersion = Version.parse(entry.requiredString("runtimeVersion"))
                val versions = buildSet {
                    val array = entry.optJSONArray("supportedVersions")
                    if (array != null) {
                        for (versionIndex in 0 until array.length()) add(Version.parse(array.getString(versionIndex)))
                    }
                }
                val series = buildSet {
                    val array = entry.optJSONArray("supportedSeries")
                    if (array != null) {
                        for (seriesIndex in 0 until array.length()) add(VersionSeries.parse(array.getString(seriesIndex)))
                    }
                }
                val ranges = buildList {
                    val array = entry.optJSONArray("supportedRanges")
                    if (array != null) {
                        for (rangeIndex in 0 until array.length()) {
                            val range = array.getJSONObject(rangeIndex)
                            add(
                                VersionRange(
                                    Version.parse(range.requiredString("min")),
                                    Version.parse(range.requiredString("max")),
                                ),
                            )
                        }
                    }
                }
                val components = buildMap {
                    val objectValue = entry.optJSONObject("runtimeComponents")
                    if (objectValue != null) {
                        for (name in objectValue.keys()) {
                            require(name.isNotBlank()) { "Runtime component names must not be blank" }
                            put(name, Version.parse(objectValue.requiredString(name)))
                        }
                    }
                }
                add(
                    EngineCapability(
                        id, context, runtimeVersion, versions, series, ranges, components,
                        entry.optBoolean("acceptsAnyEngineVersion", false),
                        if (entry.optString("controllerInput").equals("host", ignoreCase = true)) {
                            ControllerInput.HOST
                        } else {
                            ControllerInput.NATIVE
                        },
                    ),
                )
            }
        }.also { capabilities ->
            require(capabilities.map { it.id }.distinct().size == capabilities.size) {
                "Capability IDs must be unique within a plugin"
            }
        }
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Capability missing $name")
}
