package dev.enginehost

import android.content.Context

/**
 * What Enginehost says about a game it recognised but cannot run: which
 * engine it is, and why nothing here runs it. Play, the scan results and
 * Game setup all say the same sentence, so a Unity game is never left to
 * fail as "a few details could not be read".
 *
 * Unity gets the specific answer, because it is the largest family in a
 * typical PC library and the reason differs by build: an IL2CPP game is
 * machine code for a PC processor, and a Mono game's portable scripts
 * still need Unity's closed engine, which ships only as the PC player.
 */
object UnhostedEngine {
    fun explain(context: Context, detection: EngineDetection): String {
        if (detection.engine != "unity") {
            return context.getString(R.string.unhosted_engine, EngineNames.family(detection.engine))
        }
        val version = detection.engineVersion?.let { context.getString(R.string.unhosted_unity_version, it) } ?: ""
        val machine = when (detection.architecture) {
            "x86_64" -> context.getString(R.string.unhosted_machine_x86_64)
            "x86" -> context.getString(R.string.unhosted_machine_x86)
            "arm64" -> context.getString(R.string.unhosted_machine_arm64)
            else -> context.getString(R.string.unhosted_machine_pc)
        }
        return when (detection.engineContext) {
            "il2cpp" -> context.getString(R.string.unhosted_unity_il2cpp, version, machine)
            "mono" -> context.getString(R.string.unhosted_unity_mono, version, machine)
            else -> context.getString(R.string.unhosted_unity, version)
        }
    }
}
