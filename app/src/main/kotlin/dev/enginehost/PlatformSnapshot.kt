package dev.enginehost

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which droidtop-platforms commit this build's bundled engine registry IS
 * (droidtop's docs/SPEC.md 7e2). The seed is copied from a pinned submodule
 * at build time and the commit written beside it, so the app can name the
 * snapshot it shipped with instead of leaving a hand-copied JSON of unknown
 * age in the source tree -- which is exactly what had drifted between this
 * repo and the platform repo it claims to mirror.
 */
object PlatformSnapshot {
    private const val ASSET_NAME = "platform-database-snapshot.json"

    /** The full commit hash, or null in a build whose seed was copied without git. */
    fun commit(context: Context): String? = runCatching {
        JSONObject(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
            .optString("commit", "")
            .takeIf { it.isNotEmpty() && it != "unknown" }
    }.getOrNull()

    fun shortCommit(context: Context): String? = commit(context)?.take(7)
}

/**
 * The index-driven half of the registry refresh (droidtop's docs/SPEC.md
 * 7e2). droidtop-platforms is a tree -- one file per engine -- with an
 * `index.json` naming every file and its sha256. Enginehost fetches that one
 * small document, downloads only the engine files whose hash changed, and
 * composes them back into the `engines-database.json` document
 * [EngineRegistryParser] reads, so a one-engine detection fix costs a few
 * hundred bytes on the update pass instead of the whole registry.
 *
 * Composition order is the index's file order, because file order IS
 * detection precedence in this format; the index is the authority on it for
 * the same reason the monolithic file used to be.
 *
 * Returns null when the source publishes no index, so the caller falls back
 * to the whole file. droidtop implements the same two paths over the same
 * documents (`PlatformDatabaseIndex`); the format, not a shared module, is
 * what the two apps have in common, per the v5 decision.
 */
object PlatformIndex {
    private const val INDEX_FILE_NAME = "index.json"
    private const val CACHE_DIR = "platform-db"
    private const val ENGINES = "engines"

    private data class Entry(val path: String, val sha256: String)

    /** The composed `engines-database.json`, or null when there is no index to compose from. */
    fun composeEngines(context: Context, baseUrl: String): String? {
        val indexText = Transport.getOrNull(baseUrl + "/" + INDEX_FILE_NAME) ?: return null
        val index = JSONObject(indexText)
        val collection = index.optJSONObject("collections")?.optJSONObject(ENGINES) ?: return null
        val entries = ArrayList<Entry>()
        val files: JSONArray = index.getJSONArray("files")
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            if (file.optString("collection") != ENGINES) continue
            entries += Entry(file.getString("path"), file.getString("sha256").lowercase())
        }
        if (entries.isEmpty()) return null

        val cache = File(context.filesDir, CACHE_DIR)
        val texts = ArrayList<Pair<Entry, String>>(entries.size)
        for (entry in entries) {
            val cached = File(cache, entry.path).takeIf { it.isFile }?.readText()
            val text = if (cached != null && Transport.sha256(cached) == entry.sha256) {
                cached
            } else {
                val fetched = Transport.get(baseUrl + "/" + entry.path)
                check(Transport.sha256(fetched) == entry.sha256) {
                    entry.path + " does not match the hash the index published"
                }
                fetched
            }
            texts += entry to text
        }

        val root = JSONObject()
        root.put("version", collection.optInt("version", 1))
        collection.optJSONObject("meta")?.let { meta ->
            meta.keys().forEach { root.put(it, meta.getString(it)) }
        }
        val body = JSONArray()
        for ((_, text) in texts) body.put(JSONObject(text))
        root.put(collection.optString("key", ENGINES), body)
        val composed = root.toString(1)

        // Only cached once the whole set is in hand and composed; the caller
        // still validates before anything replaces the live registry.
        for ((entry, text) in texts) Transport.write(File(cache, entry.path), text)
        Transport.write(File(cache, INDEX_FILE_NAME), indexText)
        return composed
    }
}

/** The one HTTP + atomic-write helper the registry refresh uses. */
internal object Transport {
    fun get(url: String): String = getOrNull(url) ?: error("HTTP 404 from $url")

    fun getOrNull(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        return try {
            when (val code = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                404, 410 -> null
                else -> error("HTTP $code from $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun write(dest: File, text: String) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".downloading")
        temp.writeText(text)
        check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
            "Couldn't move the downloaded " + dest.name + " into place"
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            HEX[value shr 4].toString() + HEX[value and 0x0f]
        }

    private const val HEX = "0123456789abcdef"
}
