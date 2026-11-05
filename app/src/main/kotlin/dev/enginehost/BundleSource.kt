package dev.enginehost

import org.json.JSONObject

/**
 * Where a bundle was built from (docs/engine-bundle-format.md "source"): the line branch the
 * bundle's metadata names, the commit that was built, and the plugin-core commit that line pins.
 * Enginehost's signing workflow writes [commit] and [pluginCore], so every core carries them
 * without a change of its own.
 *
 * Every line of a core used to show the same build string, so a rig could not tell which line or
 * which recipe was installed (Droidtop/tracker#855, 2026-11-05). The launch screen and a core's
 * details show this beside the build number. It is informational: nothing trusts or decides on it.
 */
data class BundleSource(val revision: String?, val commit: String?, val pluginCore: String?) {
    fun toJson(): JSONObject = JSONObject()
        .putOpt("revision", revision)
        .putOpt("commit", commit)
        .putOpt("pluginCore", pluginCore)

    /** "plugin/8.5 23ef63f, plugin-core 0ae1ff8"; null when nothing is known. */
    fun describe(): String? {
        val line = listOfNotNull(revision, commit?.take(SHORT)).joinToString(" ").ifEmpty { null }
        val core = pluginCore?.let { "plugin-core ${it.take(SHORT)}" }
        return listOfNotNull(line, core).joinToString(", ").ifEmpty { null }
    }

    companion object {
        private const val SHORT = 7
        private val COMMIT = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
        private val REVISION = Regex("[A-Za-z0-9._/-]{1,100}")

        /** Fields that are not what they claim to be are dropped, never shown. */
        fun parse(json: JSONObject?): BundleSource? {
            if (json == null) return null
            fun field(name: String, pattern: Regex): String? =
                json.optString(name, "").trim().takeIf { it.matches(pattern) }
            val source = BundleSource(
                field("revision", REVISION),
                field("commit", COMMIT),
                field("pluginCore", COMMIT),
            )
            return source.takeIf { it.revision != null || it.commit != null || it.pluginCore != null }
        }
    }
}
