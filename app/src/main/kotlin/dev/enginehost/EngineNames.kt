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
