package dev.enginehost

import org.json.JSONObject

/**
 * The person's per-game choices as one JSON document (Droidtop/tracker#235):
 * top-level config keys (`pluginVersion`) and one level of `options.<name>`.
 * Free of Android so the editing rules are testable on the JVM. The document
 * lives in the library database ([GameLibraryStore.setOverride]); it is layered
 * over the game folder's file by [EngineConfigReader.resolve] and is never
 * written into the folder.
 */
object GameOverrides {
    private const val OPTIONS = "options."

    /** [current] with [key] set to [value], or removed when [value] is null; null when nothing is left. */
    fun with(current: String?, key: String, value: Any?): String? {
        val doc = current?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (key.startsWith(OPTIONS)) {
            val name = key.removePrefix(OPTIONS)
            val options = doc.optJSONObject("options") ?: JSONObject()
            if (value == null) options.remove(name) else options.put(name, value)
            if (options.length() == 0) doc.remove("options") else doc.put("options", options)
        } else {
            if (value == null) doc.remove(key) else doc.put(key, value)
        }
        return if (doc.length() == 0) null else doc.toString()
    }

    /** The value set for [key], or null when the person has not set it. */
    fun valueOf(current: String?, key: String): Any? {
        val doc = current?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return if (key.startsWith(OPTIONS)) doc.optJSONObject("options")?.opt(key.removePrefix(OPTIONS)) else doc.opt(key)
    }
}
