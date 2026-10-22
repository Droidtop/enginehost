package dev.enginehost

/**
 * A `debug.enginehost.*` system property, for rig checks only. Only adb (or
 * root) can set a `debug.` property, so nothing a person or an app does on
 * the device reaches one; unset reads as null.
 */
internal object DebugProperty {
    fun get(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.enginehost.$name") as String
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
