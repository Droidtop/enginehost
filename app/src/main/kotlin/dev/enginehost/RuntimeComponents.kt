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
    /** Whether this component meets the requirement [name] [version] for an engine running [runtimeVersion]. */
    fun serves(engine: String, name: String, version: Version, runtimeVersion: Version): Boolean =
        this.engine == engine && this.name == name && this.version == version &&
            supportedSeries.any(runtimeVersion::belongsTo)

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

/**
 * Which installed component bundles a launch of [capability] loads for a
 * game's [requirements]. A requirement is met either by the capability
 * itself (`runtimeComponents`: compiled into the engine, as the first Spine
 * bundles did) or, for a name the capability lists in `loadsComponents`, by
 * an installed component bundle for the same engine that serves the
 * capability's runtime version. Only what the game asked for is loaded: a
 * game that needs nothing gets no component.
 */
object ComponentSelection {
    /** The component bundles to load, or null when a requirement cannot be met. */
    fun select(
        engine: String,
        capability: EngineCapability,
        requirements: Map<String, Version>,
        installed: List<InstalledPlugin>,
    ): List<InstalledPlugin>? {
        val chosen = mutableListOf<InstalledPlugin>()
        for ((name, version) in requirements) {
            if (capability.runtimeComponents[name] == version) continue
            if (name !in capability.loadableComponents) return null
            chosen += best(engine, capability, name, version, installed) ?: return null
        }
        return chosen
    }

    /**
     * The requirements an engine capability could meet once a component
     * bundle is installed: loadable by the capability, carried neither by it
     * nor by any of [installed]. Empty when nothing is missing, or when the
     * capability could not serve the game whatever was installed.
     */
    fun missing(
        engine: String,
        capability: EngineCapability,
        requirements: Map<String, Version>,
        installed: List<InstalledPlugin>,
    ): Map<String, Version> {
        if (!capability.canServe(requirements)) return emptyMap()
        return requirements.filter { (name, version) ->
            capability.runtimeComponents[name] != version && best(engine, capability, name, version, installed) == null
        }
    }

    private fun best(
        engine: String,
        capability: EngineCapability,
        name: String,
        version: Version,
        installed: List<InstalledPlugin>,
    ): InstalledPlugin? = installed
        .filter { it.component?.serves(engine, name, version, capability.runtimeVersion) == true }
        .maxWithOrNull(compareBy<InstalledPlugin> { it.info.pluginVersion }.thenBy { it.bundleId })
}
