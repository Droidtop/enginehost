package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OriginDescription(val name: String, val description: String)

/**
 * Human-readable identity for a plugin origin.
 *
 * An origin is a distribution repository, and a bare URL tells a user nothing
 * about what they are about to trust. This carries the name and description
 * GitHub already holds for the repository -- real, live metadata, refreshed
 * with the release catalogs and seeded so the list reads properly offline and
 * on first run. It is display only: nothing here participates in a trust
 * decision, and a repository is free to describe itself however it likes.
 */
class OriginDirectory(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origin-directory-v1", Context.MODE_PRIVATE)

    fun describe(origin: String): OriginDescription? {
        val normalized = normalizeGithubOrigin(origin)
        preferences.getString(normalized, null)?.let { return parse(it) }
        return seeded()[normalized]
    }

    /** Fetches the repository's own name and description. Failure is not fatal. */
    fun refresh(origin: String) {
        runCatching {
            val normalized = normalizeGithubOrigin(origin)
            val match = GITHUB_ORIGIN.matchEntire(normalized) ?: return
            val api = "https://api.github.com/repos/${match.groupValues[1]}/${match.groupValues[2]}"
            val connection = URL(api).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "enginehost/0.1")
            try {
                if (connection.responseCode !in 200..299) return
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val entry = JSONObject()
                    .put("name", json.optString("name"))
                    .put("description", json.optString("description"))
                preferences.edit().putString(normalized, entry.toString()).apply()
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parse(raw: String): OriginDescription? = runCatching {
        val json = JSONObject(raw)
        OriginDescription(json.optString("name"), json.optString("description"))
    }.getOrNull()

    private fun seeded(): Map<String, OriginDescription> = runCatching {
        val root = context.resources.openRawResource(R.raw.origin_directory)
            .bufferedReader().use { JSONObject(it.readText()) }
        val origins = root.getJSONObject("origins")
        origins.keys().asSequence().associate { key ->
            val entry = origins.getJSONObject(key)
            normalizeGithubOrigin(key) to
                OriginDescription(entry.optString("name"), entry.optString("description"))
        }
    }.getOrElse { emptyMap() }
}
