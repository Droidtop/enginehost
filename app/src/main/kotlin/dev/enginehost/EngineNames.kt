package dev.enginehost

/**
 * Product names for the engine vocabulary enginehost.json uses, for the
 * one place a person reads them: the launch screen. Unknown ids fall back
 * to the id itself rather than to a guess.
 */
object EngineNames {
    fun family(engine: String): String = when (engine) {
        "renpy" -> "Ren'Py"
        "godot" -> "Godot"
        "rpgmaker" -> "RPG Maker"
        "kirikiri" -> "KiriKiri"
        "buriko" -> "Buriko General Interpreter"
        else -> engine
    }

    /**
     * What a bundle runs, said the way a person asks the question: which
     * engine, which versions of it. One line per compatibility line the
     * bundle declares, e.g. "Ren'Py 8.2.x" or "RPG Maker VX Ace 1.x · Ruby
     * 1.9.2". Built only from what the capability declares; nothing is
     * inferred from the runtime version, which is the bundle's own build and
     * not the games it accepts.
     */
    fun compatibility(engine: String, capabilities: List<EngineCapability>): List<String> =
        capabilities.groupBy { line(engine, it.engineContext) }.map { (lineName, group) ->
            val versions = group.flatMap { capability ->
                capability.supportedSeries.map { "$it.x" } +
                    capability.supportedVersions.map(Version::toString) +
                    capability.supportedRanges.map { "${it.min} to ${it.max}" } +
                    (if (capability.acceptsAnyEngineVersion) listOf("any version") else emptyList())
            }.distinct()
            val components = group.flatMap { it.runtimeComponents.entries }
                .groupBy({ it.key }, { it.value })
                .map { (name, values) -> "${componentName(name)} ${values.distinct().sorted().joinToString(" / ")}" }
            buildString {
                append(lineName)
                if (versions.isNotEmpty()) append(' ').append(versions.joinToString(", "))
                if (components.isNotEmpty()) append(" · ").append(components.joinToString(" · "))
            }
        }

    /** The engines a bundle supports, for its card title: the plugin's own list, else derived. */
    fun engines(manifest: EngineBundleManifest): List<String> =
        manifest.engines.ifEmpty {
            manifest.info.capabilities.map { line(manifest.info.engine, it.engineContext) }.distinct()
        }

    /** "Includes Ruby 1.9.2 / 3.1.3 · Spine 4.2", or null when a bundle carries no extra runtime components. */
    fun includes(capabilities: List<EngineCapability>): String? =
        capabilities.flatMap { it.runtimeComponents.entries }
            .groupBy({ it.key }, { it.value })
            .map { (name, values) -> "${componentName(name)} ${values.distinct().sorted().joinToString(" / ")}" }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")

    /** The engine versions a bundle ships, for the right-hand side of its card. */
    fun shippedVersions(capabilities: List<EngineCapability>): String =
        capabilities.map { it.runtimeVersion }.distinct().sorted().joinToString(" / ")

    /** Runtime component names as a person knows them; unknown ones pass through. */
    fun componentName(component: String): String = when (component) {
        "ruby" -> "Ruby"
        "python" -> "Python"
        "spine-godot" -> "Spine"
        "liblcf" -> "liblcf"
        else -> component
    }

    /** The compatibility line a game targets, e.g. "RPG Maker VX Ace". */
    fun line(engine: String, engineContext: String?): String {
        val family = family(engine)
        val context = engineContext?.takeIf { it.isNotBlank() && it != DEFAULT_ENGINE_CONTEXT } ?: return family
        if (engine == "rpgmaker") {
            return when (context) {
                "xp" -> "RPG Maker XP"
                "vx" -> "RPG Maker VX"
                "vxace" -> "RPG Maker VX Ace"
                "mv" -> "RPG Maker MV"
                "mz" -> "RPG Maker MZ"
                "2000" -> "RPG Maker 2000"
                "2003" -> "RPG Maker 2003"
                else -> "$family $context"
            }
        }
        return "$family $context"
    }
}
