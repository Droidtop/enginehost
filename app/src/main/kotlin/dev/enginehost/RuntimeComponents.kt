package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject

/** What an installed or published bundle is (docs/engine-bundle-format.md, "Runtime components"). */
const val BUNDLE_KIND_ENGINE = "engine"
const val BUNDLE_KIND_COMPONENT = "component"

/**
 * What a runtime-component bundle provides: one component, at one version,
 * for the engine plugins of one engine, built for the engine versions in
 * [supportedSeries]. A component bundle carries no code Enginehost runs
 * itself; the engine plugin that loads it does, into its own runtime. It is
 * installed, verified and approved exactly like an engine bundle (same
 * format, signature, origin keys and trust screen), so it is a second
 * bundle, not a second trust mechanism. It is separate so that it can carry
 * its own licence, shown before it is approved: Spine's runtimes, for one,
 * cannot be distributed as part of a GPL-3.0 engine bundle.
 */
data class ComponentInfo(
    /** The engine whose plugins load it ("godot"). */
    val engine: String,
    /** Ecosystem-scoped id, as games require it in `runtimeRequirements` ("spine-godot"). */
    val name: String,
    /** The component's compatibility line, as its own ecosystem defines it ("4.2" for Spine 4.2 exports). */
    val version: Version,
    /** The engine versions a build of it is carried for: an engine runtime outside them cannot load it. */
    val supportedSeries: Set<VersionSeries>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("engine", engine)
        .put("name", name)
        .put("version", version.toString())
        .put("supportedSeries", JSONArray(supportedSeries.map(VersionSeries::toString).sorted()))
}

/**
 * The licence a bundle is distributed under, when it is not the one every
 * official bundle uses: its name and the payload file holding its text,
 * which the trust screen shows before the bundle can be approved.
 */
data class BundleLicense(val name: String, val file: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("file", file)
}

object ComponentManifestReader {
    /** Component names and versions become path segments in a plugin's payload layout; nothing else may. */
    private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    fun kind(json: JSONObject): String = json.optString("kind", BUNDLE_KIND_ENGINE).ifBlank { BUNDLE_KIND_ENGINE }.also {
        require(it == BUNDLE_KIND_ENGINE || it == BUNDLE_KIND_COMPONENT) { "Unsupported bundle kind $it" }
    }

    fun component(json: JSONObject): ComponentInfo {
        val engine = json.requiredString("engine")
        val name = json.requiredString("name")
        val version = json.requiredString("version")
        require(name.matches(SEGMENT) && ".." !in name) { "Invalid component name" }
        require(version.matches(SEGMENT) && ".." !in version) { "Invalid component version" }
        val series = json.optJSONArray("supportedSeries")?.let { array ->
            (0 until array.length()).map { VersionSeries.parse(array.getString(it)) }.toSet()
        }.orEmpty()
        require(series.isNotEmpty()) { "A component must name the engine versions it is built for" }
        return ComponentInfo(engine, name, Version.parse(version), series)
    }

    fun license(json: JSONObject?): BundleLicense? = json?.let {
        BundleLicense(it.requiredString("name"), validateBundlePath(it.requiredString("file")))
    }
}
