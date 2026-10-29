package dev.enginehost

/**
 * The release build's `debug.enginehost.*` switches: none. Every name reads
 * as unset, so a release build has no rig switch to turn on, whatever is set
 * on the device (Droidtop/tracker#768; the debug build's copy is in src/debug).
 */
internal object DebugProperty {
    @Suppress("UNUSED_PARAMETER")
    fun get(name: String): String? = null
}
