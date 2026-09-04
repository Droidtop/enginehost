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
        "kirikiri", "kirikiri2" -> "KiriKiri"
        "buriko" -> "Buriko General Interpreter"
        "cmvs" -> "CMVS"
        "catsystem2" -> "CatSystem2"
        "html" -> "HTML game"
        "flash_air" -> "Flash / AIR"
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
        capabilities.groupBy { line(it.engine ?: engine, it.engineContext) }.map { (lineName, group) ->
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

    /** The compatibility lines a bundle serves, for its card title, each named under the engine it belongs to. */
    fun engines(manifest: EngineBundleManifest): List<String> =
        manifest.info.capabilities.map { line(manifest.info.engineOf(it), it.engineContext) }.distinct()

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

    /**
     * The compatibility line a game targets, e.g. "RPG Maker VX Ace", from
     * the engine and context vocabulary the bundles actually publish. A
     * context that is only an implementation detail (Ren'Py "standard",
     * KiriKiri "default", Buriko's compiled-script variants) adds nothing a
     * person would say, so it is left off; an unknown context is shown as
     * written rather than guessed at.
     */
    fun line(engine: String, engineContext: String?): String {
        val family = family(engine)
        val context = engineContext?.takeIf { it.isNotBlank() && it != DEFAULT_ENGINE_CONTEXT } ?: return family
        return when (engine) {
            "rpgmaker" -> when (context) {
                "xp" -> "RPG Maker XP"
                "vx" -> "RPG Maker VX"
                "vxace" -> "RPG Maker VX Ace"
                "mv" -> "RPG Maker MV"
                "mz" -> "RPG Maker MZ"
                "2000" -> "RPG Maker 2000"
                "2003" -> "RPG Maker 2003"
                else -> "$family $context"
            }
            "renpy" -> if (context == "standard" || context == "python3" || context == "python2") family else "$family $context"
            "godot" -> if (context == "standard") family else "$family $context"
            "kirikiri", "kirikiri2" -> if (context == "default") "KiriKiri 2" else "$family $context"
            "buriko" -> family
            "cmvs" -> when (context) {
                "ps2" -> "CMVS PS2"
                "ps3" -> "CMVS PS3"
                else -> "$family $context"
            }
            "catsystem2" -> if (context == "cst") family else "$family $context"
            "html" -> if (context == "compiled-html") family else "$family $context"
            "flash_air" -> when (context) {
                "swf" -> "Flash (SWF)"
                "air" -> "Adobe AIR"
                else -> "$family $context"
            }
            else -> "$family $context"
        }
    }
}
