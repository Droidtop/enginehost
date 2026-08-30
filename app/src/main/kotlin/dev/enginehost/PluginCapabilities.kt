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
data class EngineCapability(
    val id: String,
    val engineContext: String,
    val runtimeVersion: Version,
    val supportedVersions: Set<Version>,
    val supportedRanges: List<VersionRange>,
    val runtimeComponents: Map<String, Version> = emptyMap(),
) {
    fun supports(version: Version): Boolean =
        version == runtimeVersion || version in supportedVersions || supportedRanges.any { it.contains(version) }

    fun specificityFor(version: Version): Long = when {
        version == runtimeVersion || version in supportedVersions -> 0L
        else -> supportedRanges.filter { it.contains(version) }.minOfOrNull { it.width() } ?: Long.MAX_VALUE
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
                add(EngineCapability(id, context, runtimeVersion, versions, ranges, components))
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
