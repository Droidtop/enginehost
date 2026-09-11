package dev.enginehost

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * A GitHub API response that was not a success, carrying what its headers
 * said about the allowance that produced it. The status alone cannot tell a
 * spent rate limit (403 with `X-RateLimit-Remaining: 0`) from a repository
 * the person is not allowed to read (403 with allowance left), and the two
 * need entirely different words on screen.
 */
class GithubHttpException(
    val status: Int,
    val rateLimitRemaining: Int?,
    val rateLimitResetEpochSeconds: Long?,
    message: String,
) : IOException(message)

/**
 * Why one origin's refresh produced no catalog.
 *
 * This exists because "Nothing published yet" was the only thing the catalog
 * screen could say about a failed refresh, and on a fresh device the usual
 * cause is the opposite of nothing published: GitHub's unauthenticated API
 * allows 60 requests an hour per address, a refresh across the eleven
 * default origins costs more than a tenth of that, and a few refreshes in
 * one sitting spend it. The person then saw an empty store with no reason.
 */
sealed interface CatalogFailure {
    /** The allowance is spent; [resetEpochSeconds] is when GitHub says it returns. */
    data class RateLimited(val resetEpochSeconds: Long?) : CatalogFailure

    /** Any other unsuccessful HTTP status. */
    data class Http(val status: Int) : CatalogFailure

    /** Nothing was reachable: no network, no route, or the connection timed out. */
    object Offline : CatalogFailure

    /** Everything else, in the words the failure itself used. */
    data class Other(val message: String) : CatalogFailure
}

object CatalogFailures {
    /** The reason behind [error], looking through wrapper exceptions for the one that knows. */
    fun of(error: Throwable): CatalogFailure {
        val known = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).firstOrNull(::speaks)
        return when (known) {
            is GithubHttpException -> when {
                known.status in RATE_LIMIT_STATUSES && (known.rateLimitRemaining ?: 0) <= 0 ->
                    CatalogFailure.RateLimited(known.rateLimitResetEpochSeconds)
                else -> CatalogFailure.Http(known.status)
            }
            null -> CatalogFailure.Other(error.message ?: error.javaClass.simpleName)
            else -> CatalogFailure.Offline
        }
    }

    private fun speaks(error: Throwable): Boolean = error is GithubHttpException ||
        error is UnknownHostException || error is ConnectException ||
        error is NoRouteToHostException || error is InterruptedIOException || error is SocketException

    /** 429 is the documented one; GitHub still answers 403 for the primary limit. */
    private val RATE_LIMIT_STATUSES = setOf(403, 429)
    private const val MAX_CAUSE_DEPTH = 8
}

/** What one origin's refresh did. */
sealed interface OriginOutcome {
    /** A catalog was fetched and stored; [plugins] is how many builds it describes. */
    data class Updated(val plugins: Int) : OriginOutcome

    /** The source said nothing changed (a 304, or an index entry we already hold). */
    object Unchanged : OriginOutcome

    /** Nothing was stored and the previous catalog stands. */
    data class Failed(val reason: CatalogFailure) : OriginOutcome
}

/** One release's asset as the plugins index describes it. */
data class IndexedAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** One release of one plugin repository as the plugins index describes it. */
data class IndexedRelease(
    val tag: String,
    val stream: PluginStream,
    val publishedAt: String?,
    val assets: List<IndexedAsset>,
)

data class IndexedOrigin(val origin: String, val repo: String, val releases: List<IndexedRelease>)

data class PluginsIndex(
    val schemaVersion: Int,
    val generatedAt: Instant?,
    val origins: Map<String, IndexedOrigin>,
)

/**
 * The plugins index: droidtop-platforms' `plugins/index.json`, one document
 * describing every default origin's releases, generated there by a workflow
 * that has a token and regenerated whenever a plugin repository publishes.
 *
 * Enginehost fetches it from raw.githubusercontent.com, which has no API
 * allowance, so the common refresh costs one request that cannot be rate
 * limited instead of one API request per origin. Only a release whose
 * envelope we do not already hold is downloaded afterwards, and that comes
 * from the release asset host, which is not the API either.
 *
 * It is a snapshot, so it can be behind: an index older than
 * [CatalogRefresh.INDEX_MAX_AGE_MS], or one that does not name an origin at
 * all (any custom repository), sends that origin down the API path it always
 * used. Same two paths, same validate-before-replace, as the engine registry
 * refresh ([PlatformIndex]).
 */
object PluginCatalogIndex {
    const val SCHEMA_VERSION = 1

    fun parse(text: String): PluginsIndex {
        val root = JSONObject(text)
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported plugins index schemaVersion $schemaVersion" }
        val generatedAt = root.optString("generatedAt").takeIf(String::isNotBlank)
            ?.let { stamp -> runCatching { Instant.parse(stamp) }.getOrNull() }
        val origins = root.getJSONArray("origins")
        val parsed = LinkedHashMap<String, IndexedOrigin>(origins.length())
        for (index in 0 until origins.length()) {
            val entry = origins.getJSONObject(index)
            val repo = entry.getString("repo")
            val origin = normalizeGithubOrigin(
                entry.optString("origin").takeIf(String::isNotBlank) ?: "https://github.com/$repo",
            )
            parsed[origin] = IndexedOrigin(origin, repo, releases(entry.optJSONArray("releases")))
        }
        return PluginsIndex(schemaVersion, generatedAt, parsed)
    }

    private fun releases(array: JSONArray?): List<IndexedRelease> =
        (0 until (array?.length() ?: 0)).map { index ->
            val release = array!!.getJSONObject(index)
            IndexedRelease(
                tag = release.getString("tag"),
                // The stream is the release envelope's own `channel`, read
                // where the index was generated; the pre-release flag is the
                // same fallback the envelope reader uses.
                stream = PluginStream.fromChannel(
                    release.optString("stream").takeIf(String::isNotBlank),
                    release.optBoolean("prerelease"),
                ),
                publishedAt = release.optString("published_at").takeIf(String::isNotBlank),
                assets = assets(release.optJSONArray("assets")),
            )
        }

    private fun assets(array: JSONArray?): List<IndexedAsset> =
        (0 until (array?.length() ?: 0)).map { index ->
            val asset = array!!.getJSONObject(index)
            IndexedAsset(
                name = asset.getString("name"),
                url = asset.getString("url"),
                size = asset.optLong("size", 0),
                sha256 = asset.optString("sha256").takeIf(String::isNotBlank)
                    ?.removePrefix("sha256:")?.lowercase(),
            )
        }

    /** The index at [url], or null when there is none or it is older than [maxAgeMs]. */
    fun fetch(url: String, maxAgeMs: Long, now: Long = System.currentTimeMillis()): PluginsIndex? {
        val text = Transport.getOrNull(url) ?: return null
        val index = parse(text)
        // No timestamp, or one too old to trust, is the same answer as no
        // index: ask GitHub directly rather than serve a stale catalog.
        val generatedAt = index.generatedAt ?: return null
        return index.takeIf { now - generatedAt.toEpochMilli() <= maxAgeMs }
    }
}

/**
 * The one catalog refresh. The Refresh button and the scheduled update pass
 * both call this, so what a person sees on the catalog screen and what the
 * background pass stores can never come from two different mechanisms.
 *
 * Per origin: the plugins index if it covers it, otherwise the GitHub API
 * with the stored ETag, and in both cases a catalog is only stored once it
 * has parsed and verified -- a failure leaves the previous one in place.
 * Every stream a repository publishes is fetched and cached, not only the
 * stream currently chosen; [PluginStream.offeredTo] is applied by whoever
 * reads the cache, so switching streams in Settings or on the catalog
 * screen is a local choice, never a reason to refetch.
 */
class CatalogRefresh(private val context: Context) {
    fun run(
        origins: List<String>,
        indexUrl: String = DEFAULT_INDEX_URL,
    ): Map<String, OriginOutcome> {
        val cache = PluginCatalogCache(context)
        val index = runCatching { PluginCatalogIndex.fetch(indexUrl, INDEX_MAX_AGE_MS) }.getOrNull()
        return origins.associateWith { origin ->
            runCatching {
                val indexed = index?.origins?.get(normalizeGithubOrigin(origin))
                if (indexed == null) fromApi(cache, origin) else fromIndex(cache, indexed, origin)
            }.getOrElse { error -> OriginOutcome.Failed(CatalogFailures.of(error)) }
        }
    }

    private fun fromIndex(
        cache: PluginCatalogCache,
        indexed: IndexedOrigin,
        origin: String,
    ): OriginOutcome {
        // What the index says about this origin, reduced to one value. Equal
        // to what we stored means nothing has been published since, so the
        // refresh costs nothing beyond the index itself. Every stream is
        // fetched and cached; which one a person is offered is decided when
        // the catalog is read, so the fingerprint does not depend on that
        // choice -- switching streams never needs a re-fetch.
        val fingerprint = INDEX_ETAG_PREFIX + Transport.sha256(
            indexed.releases.joinToString("\n") { release ->
                release.tag + "|" + release.stream.channel + "|" +
                    release.assets.joinToString(",") { asset -> asset.name + "@" + (asset.sha256 ?: asset.size.toString()) }
            },
        )
        if (cache.hasFetched(origin) && cache.etag(origin) == fingerprint) return OriginOutcome.Unchanged
        val keys = PluginOriginKeyStore(context)
        val plugins = mutableListOf<AvailablePlugin>()
        for (release in indexed.releases) {
            val envelope = release.assets.firstOrNull { it.name == RELEASE_CATALOG } ?: continue
            plugins += PluginReleaseReader.parse(
                envelopeText(envelope),
                origin,
                release.tag,
                release.stream != PluginStream.STABLE,
                release.assets.associate { it.name to (it.url to it.sha256) },
                keys,
            )
        }
        cache.save(origin, plugins, fingerprint)
        return OriginOutcome.Updated(plugins.size)
    }

    private fun fromApi(cache: PluginCatalogCache, origin: String): OriginOutcome {
        // An index fingerprint is not an ETag and GitHub would reject it.
        val etag = cache.etag(origin)
            ?.takeUnless { it.startsWith(INDEX_ETAG_PREFIX) }
            ?.takeIf { cache.hasFetched(origin) }
        return when (val fetched = GithubPluginCatalogClient(context).fetch(origin, etag)) {
            is CatalogFetch.Unchanged -> OriginOutcome.Unchanged
            is CatalogFetch.Fetched -> {
                cache.save(origin, fetched.plugins, fetched.etag)
                OriginOutcome.Updated(fetched.plugins.size)
            }
        }
    }

    /**
     * The release envelope, by its published hash. Envelopes never change
     * under a hash, so an index that names one we already hold needs no
     * download at all, and one we do not is checked against the hash the
     * index published before it is believed or kept.
     */
    private fun envelopeText(asset: IndexedAsset): String {
        val sha256 = asset.sha256
        val cached = sha256?.let { File(context.filesDir, "$ENVELOPE_DIR/$it.json") }
        cached?.takeIf { it.isFile }?.let { file ->
            val text = runCatching { file.readText() }.getOrNull()
            if (text != null && Transport.sha256(text) == sha256) return text
        }
        val text = Transport.get(asset.url)
        if (sha256 != null) {
            check(Transport.sha256(text) == sha256) {
                asset.name + " does not match the hash the plugins index published"
            }
            Transport.write(File(context.filesDir, "$ENVELOPE_DIR/$sha256.json"), text)
        }
        return text
    }

    companion object {
        /** The index lives beside the engine registry, in the same platform repository. */
        const val DEFAULT_INDEX_URL = EngineRegistryStore.DEFAULT_BASE_URL + "/plugins/index.json"

        /**
         * How old the index may be before an origin asks GitHub directly.
         * The workflow that writes it runs several times a day and on every
         * publish, so three days means several missed runs, not one.
         */
        const val INDEX_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        internal const val INDEX_ETAG_PREFIX = "index:"
        internal const val RELEASE_CATALOG = "enginehost-release.json"
        private const val ENVELOPE_DIR = "plugin-release-envelopes"
    }
}
