package dev.enginehost

/**
 * A `debug.enginehost.*` system property, for rig checks only. Only adb (or
 * root) can set a `debug.` property, so nothing a person or an app does on
 * the device reaches one; unset reads as null.
 *
 * This copy is the debug build's. The release build's (src/release) reads
 * nothing, so no switch exists in it: not the forced lockdown, not the
 * learning run that lets a core run with its filter only recording, not the
 * stall, the audit or the probes (Droidtop/tracker#768). A rig check that
 * needs one runs on enginehost-latest-debug.apk.
 */
internal object DebugProperty {
    fun get(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.enginehost.$name") as String
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
