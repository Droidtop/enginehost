package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OriginIdentity(
    val origin: String,
    val engine: String,
    val implementationId: String,
    val implementationName: String,
    val upstream: String?,
    val engineContexts: List<String>,
    val description: String,
)

/**
 * What a plugin origin is, in the terms a user chooses between.
 *
 * An origin is a distribution repository, and a bare URL says nothing about
 * what is being trusted. It also cannot answer the question that actually
 * arises once more than one repository serves the same engine: RPG Maker alone
 * has three implementations here, covering different generations of the engine
 * between them. So an origin declares both halves of its identity -- the engine
 * it implements, and which implementation of that engine it is -- in its own
 * enginehost-origin.json, seeded into the build so the list reads properly
 * offline and on first run.
 *
 * This is display and selection metadata. Nothing here participates in a trust
 * decision: a repository is free to describe itself however it likes, and being
 * described well is not evidence of anything.
 */
class OriginDirectory(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origin-directory-v1", Context.MODE_PRIVATE)

    fun describe(origin: String): OriginIdentity? {
        val normalized = normalizeGithubOrigin(origin)
        preferences.getString(normalized, null)?.let { raw ->
            parse(raw)?.let { return it }
        }
        return seeded()[normalized]
    }

    /**
     * Origins that are genuine alternatives for the same content.
     *
     * Alternatives are per engine AND context, never per engine alone. Several
     * repositories can share an engine name while covering entirely different
     * generations of it -- the three RPG Maker repositories here implement
     * RGSS, 2000/2003 and MV/MZ respectively, which are different engines
     * wearing one brand. Exactly one of those can run any given game, so
     * offering them as a choice would be offering a choice that does not exist.
     */
    fun implementationsFor(engine: String, engineContext: String, origins: List<String>): List<OriginIdentity> =
        origins.mapNotNull { describe(it) }
            .filter { it.engine == engine && engineContext in it.engineContexts }

    /** Fetches the repository's own declaration. Failure is not fatal. */
    fun refresh(origin: String) {
        runCatching {
            val normalized = normalizeGithubOrigin(origin)
            val raw = GithubRepositoryFile.fetch(normalized, "enginehost-origin.json") ?: return
            parse(raw) ?: return
            preferences.edit().putString(normalized, raw).apply()
        }
    }

    private fun parse(raw: String): OriginIdentity? = runCatching {
        val json = JSONObject(raw)
        val implementation = json.getJSONObject("implementation")
        val contexts = json.optJSONArray("engineContexts")
        OriginIdentity(
            origin = normalizeGithubOrigin(json.getString("origin")),
            engine = json.getString("engine"),
            implementationId = implementation.getString("id"),
            implementationName = implementation.getString("name"),
            upstream = implementation.optString("upstream").takeIf { it.isNotBlank() },
            engineContexts = (0 until (contexts?.length() ?: 0)).map { contexts!!.getString(it) },
            description = json.optString("description"),
        )
    }.getOrNull()

    private fun seeded(): Map<String, OriginIdentity> = runCatching {
        val root = context.resources.openRawResource(R.raw.origin_directory)
            .bufferedReader().use { JSONObject(it.readText()) }
        val origins = root.getJSONObject("origins")
        origins.keys().asSequence().mapNotNull { key ->
            parse(origins.getJSONObject(key).toString())?.let { it.origin to it }
        }.toMap()
    }.getOrElse { emptyMap() }
}

/**
 * Reads one file from a plugin repository.
 *
 * These repositories are forks of the engines they wrap, so the default branch
 * is usually the upstream project's own (master, dev, yuri) and carries none of
 * the plugin's files. Look on the branch the plugin convention puts them on
 * first, and fall back to the default branch for repositories that do not
 * follow it.
 */
object GithubRepositoryFile {
    private val BRANCHES = listOf("plugin-core", "main", "master")

    fun fetch(origin: String, path: String): String? {
        val match = GITHUB_ORIGIN.matchEntire(normalizeGithubOrigin(origin)) ?: return null
        val base = "https://api.github.com/repos/${match.groupValues[1]}/${match.groupValues[2]}/contents/$path"
        for (branch in BRANCHES) {
            read("$base?ref=$branch")?.let { return it }
        }
        return read(base)
    }

    private fun read(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "enginehost/0.1")
        try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.getString("encoding") != "base64") return null
            java.util.Base64.getMimeDecoder().decode(json.getString("content")).toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
