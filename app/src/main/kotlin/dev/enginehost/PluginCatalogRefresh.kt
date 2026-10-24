package dev.enginehost

import android.content.Context
import android.util.Log
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
 * A repository's releases are signed by another key than the one pinned for
 * it. For an official origin that means a key the root has not certified (or
 * not yet delivered); for one the person added, that the repository changed
 * its key, which the person may review and accept as a new source.
 */
class SignerChangedException(val origin: String, val fingerprint: String, cause: Throwable? = null) :
    IllegalArgumentException("Release signer does not match the repository's pinned key", cause)

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

    /** Its releases are signed by [fingerprint], not the key pinned for it. */
    data class KeyChanged(val fingerprint: String) : CatalogFailure

    /** Everything else, in the words the failure itself used. */
    data class Other(val message: String) : CatalogFailure
}

object CatalogFailures {
    /** The reason behind [error], looking through wrapper exceptions for the one that knows. */
    fun of(error: Throwable): CatalogFailure {
        val known = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).firstOrNull(::speaks)
        return when (known) {
            is SignerChangedException -> CatalogFailure.KeyChanged(known.fingerprint.uppercase())
            is GithubHttpException -> when {
                known.status in RATE_LIMIT_STATUSES && (known.rateLimitRemaining ?: 0) <= 0 ->
                    CatalogFailure.RateLimited(known.rateLimitResetEpochSeconds)
                else -> CatalogFailure.Http(known.status)
            }
            null -> CatalogFailure.Other(error.message ?: error.javaClass.simpleName)
            else -> CatalogFailure.Offline
        }
    }

    private fun speaks(error: Throwable): Boolean = error is GithubHttpException || error is SignerChangedException ||
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
    object Unchanged : OriginOutcome {
        override fun toString() = "Unchanged"
    }

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

/** Who an index entry says publishes it. Display only: trust is decided by keys, never by this. */
enum class IndexedTrust { OFFICIAL, THIRD_PARTY }

/** A third-party repository's maintainer, as droidtop-platforms' third-party list names them. */
data class IndexedMaintainer(val id: String, val name: String)

/**
 * One repository in the plugins index. [keyDocument] is its
 * `enginehost-public-key.json` as the index generator verified it: for an
 * official repository with the root's certificate, which this app checks
 * again itself before it believes it ([PluginOriginStore.learnOfficial]); for
 * a third party, the key the list names, which must agree with the key the
 * repository publishes before anything is pinned ([ThirdPartyListing]).
 */
data class IndexedOrigin(
    val origin: String,
    val repo: String,
    val releases: List<IndexedRelease>,
    val trust: IndexedTrust? = null,
    val keyDocument: String? = null,
    val maintainer: IndexedMaintainer? = null,
    /**
     * The origin's signing certificate chain as the index publishes it (x5c
     * style: standard base64 DER, leaf first, root excluded). When it chains
     * to the pinned Enginehost signing root and names this origin, the origin is
     * official ([PluginOriginStore.learnOfficial]); the index's own word is
     * never what decides.
     */
    val certificates: List<String> = emptyList(),
    /** Every envelope [releases] lists, in one file beside the index ([EnvelopePack]); null in an older index. */
    val envelopePack: EnvelopePack? = null,
)

/**
 * One repository's envelope pack: `plugins/envelopes/<owner>__<repo>.json`, which holds every
 * release envelope the index lists for it, as published, keyed by sha256. [path] is relative to
 * the index. A device with none of them cached (a fresh install, cleared data) reads this one file
 * instead of one request per release. On 2026-10-24 that was 509 requests in a row, and a reset
 * console never finished its core list (Droidtop/tracker#550).
 */
data class EnvelopePack(val path: String, val sha256: String, val size: Long)

data class PluginsIndex(
    val schemaVersion: Int,
    val generatedAt: Instant?,
    val origins: Map<String, IndexedOrigin>,
)

/**
 * The plugins index: droidtop-platforms' `plugins/index.json`, one document
 * describing every registered repository's releases, generated there by a
 * workflow that has a token and regenerated whenever a plugin repository
 * publishes. The generator verifies every manifest it lists; this app
 * verifies them all again regardless.
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
            parsed[origin] = IndexedOrigin(
                origin,
                repo,
                releases(entry.optJSONArray("releases")),
                trust = when (entry.optString("trust")) {
                    "official" -> IndexedTrust.OFFICIAL
                    "third-party" -> IndexedTrust.THIRD_PARTY
                    else -> null
                },
                keyDocument = entry.optJSONObject("key")?.toString(),
                maintainer = entry.optJSONObject("maintainer")?.let { maintainer ->
                    runCatching { IndexedMaintainer(maintainer.getString("id"), maintainer.getString("name")) }.getOrNull()
                },
                certificates = entry.optJSONArray("certificates")?.let { array ->
                    (0 until array.length()).map(array::getString)
                }.orEmpty(),
                envelopePack = entry.optJSONObject("envelopePack")?.let { pack ->
                    runCatching {
                        EnvelopePack(pack.getString("path"), pack.getString("sha256").lowercase(), pack.optLong("size"))
                    }.getOrNull()
                },
            )
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

    /**
     * Whether [index] may answer for releases. No timestamp, or one too old
     * to trust, is the same answer as no index: ask GitHub directly rather
     * than serve a stale catalog.
     */
    fun isFresh(index: PluginsIndex, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val generatedAt = index.generatedAt ?: return false
        return now - generatedAt.toEpochMilli() <= maxAgeMs
    }

    /**
     * The last index this device fetched, whatever its age. The Plugins
     * screen reads the third-party list from it, so the list is there offline
     * and before the first refresh of a visit; release data is never read
     * from it, only from a fresh fetch.
     */
    fun cached(context: Context): PluginsIndex? = runCatching {
        parse(File(context.filesDir, CACHED_INDEX).readText())
    }.getOrNull()

    internal fun keep(context: Context, text: String) = Transport.write(File(context.filesDir, CACHED_INDEX), text)

    private const val CACHED_INDEX = "plugins-index.json"
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
    /** Refreshes [origins]. */
    fun run(
        origins: List<String>,
        indexUrl: String = DEFAULT_INDEX_URL,
    ): Map<String, OriginOutcome> = synchronized(RUNNING) { refresh(origins, fetchIndex(indexUrl), indexUrl) }

    /**
     * Refreshes every origin this device knows, including official ones the
     * index has just introduced: the Plugins screen's refresh, where a newly
     * registered official repository should appear without an app release.
     */
    fun runAll(indexUrl: String = DEFAULT_INDEX_URL): Map<String, OriginOutcome> = synchronized(RUNNING) {
        val index = fetchIndex(indexUrl)
        refresh(PluginOriginStore(context).all(), index, indexUrl)
    }

    /**
     * The index, kept for the Plugins screen and used to learn root-certified
     * official origins whatever its age (a certificate does not go stale);
     * answered for releases only while it is fresh.
     */
    private fun fetchIndex(indexUrl: String): PluginsIndex? {
        // Enginehost's revocation list sits beside the index and is taken
        // first, so nothing it revokes is learned or listed in this refresh.
        runCatching {
            Transport.getOrNull(indexUrl.substringBeforeLast('/') + "/" + REVOCATIONS_FILE, context)
                ?.let { RevocationListStore(context).accept(BundleVerifier.ENGINEHOST_CATALOG, it) }
        }
        val text = runCatching { Transport.getOrNull(indexUrl, context) }.getOrNull() ?: return null
        val index = runCatching { PluginCatalogIndex.parse(text) }.getOrNull() ?: return null
        runCatching { PluginCatalogIndex.keep(context, text) }
        runCatching { PluginOriginStore(context).learnOfficial(index) }
        return index.takeIf { PluginCatalogIndex.isFresh(it, INDEX_MAX_AGE_MS) }
    }

    private fun refresh(origins: List<String>, index: PluginsIndex?, indexUrl: String): Map<String, OriginOutcome> {
        val cache = PluginCatalogCache(context)
        val revocations = RevocationListStore(context)
        return origins.associateWith { origin ->
            runCatching {
                // A repository a person added publishes its own list, under the key they pinned.
                if (PluginOriginKeyStore(context).custom(origin) != null) runCatching { revocations.refreshAdded(origin) }
                val indexed = index?.origins?.get(normalizeGithubOrigin(origin))
                if (indexed == null) fromApi(cache, origin) else fromIndex(cache, indexed, origin, indexUrl)
            }.getOrElse { error -> OriginOutcome.Failed(CatalogFailures.of(error)) }
                .also { outcome -> Log.i(TAG, "Catalog of " + origin + ": " + outcome) }
        }
    }

    private fun fromIndex(
        cache: PluginCatalogCache,
        indexed: IndexedOrigin,
        origin: String,
        indexUrl: String,
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
        fillFromPack(indexed, indexUrl)
        val trust = BundleTrust(context)
        val plugins = mutableListOf<AvailablePlugin>()
        for (release in indexed.releases) {
            val envelope = release.assets.firstOrNull { it.name in RELEASE_ENVELOPES } ?: continue
            plugins += PluginReleaseReader.parse(
                envelopeText(envelope),
                origin,
                release.tag,
                release.stream != PluginStream.STABLE,
                release.assets.associate { it.name to (it.url to it.sha256) },
                trust,
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
     * When more than one listed envelope is missing here, takes them all from the repository's
     * pack ([EnvelopePack]) in one request and caches each under its hash. A pack that is absent,
     * fails its hash or cannot be read changes nothing: [envelopeText] then fetches each envelope
     * alone, as before. One or two new builds on a device that has the rest are cheaper alone
     * than a whole pack.
     */
    private fun fillFromPack(indexed: IndexedOrigin, indexUrl: String) {
        val pack = indexed.envelopePack ?: return
        val missing = indexed.releases.mapNotNull { release ->
            release.assets.firstOrNull { it.name in RELEASE_ENVELOPES }?.sha256?.lowercase()
        }.filterNot { sha256 -> File(context.filesDir, "$ENVELOPE_DIR/$sha256.json").isFile }.toSet()
        if (missing.size < PACK_THRESHOLD) return
        runCatching {
            val text = Transport.get(indexUrl.substringBeforeLast('/') + "/" + pack.path, context)
            check(Transport.sha256(text) == pack.sha256) { pack.path + " does not match the hash the plugins index published" }
            val envelopes = JSONObject(text).getJSONObject("envelopes")
            var kept = 0
            for (sha256 in missing) {
                val envelope = envelopes.optString(sha256).takeIf(String::isNotEmpty) ?: continue
                // Each one is held to its own published hash, exactly as if fetched alone.
                if (Transport.sha256(envelope) != sha256) continue
                Transport.write(File(context.filesDir, "$ENVELOPE_DIR/$sha256.json"), envelope)
                kept++
            }
            Log.i(TAG, "Envelope pack " + pack.path + ": " + kept + " of " + missing.size + " missing envelopes")
        }.onFailure { error -> Log.w(TAG, "Envelope pack " + pack.path + " not used; fetching envelopes one by one", error) }
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
        val text = Transport.get(asset.url, context)
        if (sha256 != null) {
            check(Transport.sha256(text) == sha256) {
                asset.name + " does not match the hash the plugins index published"
            }
            Transport.write(File(context.filesDir, "$ENVELOPE_DIR/$sha256.json"), text)
        }
        return text
    }

    companion object {
        private const val TAG = "EnginehostCatalog"

        /** One refresh at a time in this process: a second caller waits, then finds the cache current. */
        private val RUNNING = Any()

        /** How many missing envelopes make the pack worth one request. */
        private const val PACK_THRESHOLD = 2

        /** The index lives beside the engine registry, in the same platform repository. */
        const val DEFAULT_INDEX_URL = EngineRegistryStore.DEFAULT_BASE_URL + "/plugins/index.json"

        /**
         * How old the index may be before an origin asks GitHub directly.
         * The workflow that writes it runs several times a day and on every
         * publish, so three days means several missed runs, not one.
         */
        const val INDEX_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        internal const val INDEX_ETAG_PREFIX = "index:"

        /** Enginehost's own revocation list, beside the plugins index (docs/engine-bundle-format.md "Revocation"). */
        internal const val REVOCATIONS_FILE = "revocations.jws"
        internal const val RELEASE_CATALOG = "enginehost-release.json"

        /**
         * A runtime-component release's envelope has a name of its own, so
         * that an Enginehost from before component bundles skips the release
         * (it reads [RELEASE_CATALOG] only) instead of failing to parse a
         * manifest with no entry point, which would fail the whole origin's
         * catalog. Same format, same verification.
         */
        internal const val COMPONENT_CATALOG = "enginehost-component.json"
        internal val RELEASE_ENVELOPES = setOf(RELEASE_CATALOG, COMPONENT_CATALOG)
        private const val ENVELOPE_DIR = "plugin-release-envelopes"
    }
}
