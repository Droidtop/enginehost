package dev.enginehost

import dev.droidtop.shared.net.ReleaseListing
import dev.droidtop.shared.version.Version
import dev.droidtop.shared.version.VersionConstraint
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * How far from "proven on hardware" a release is. Every plugin repository
 * publishes all three: every green build of a line lands in [UNSTABLE]
 * automatically, a maintainer promotes a build to [TESTING] when it runs
 * games but has not been lived with, and to [STABLE] when it has. A person
 * picks the most adventurous stream they want in Settings; the catalog and
 * the update check then consider that stream and every steadier one, so
 * choosing "testing" still offers stable builds when those are newer.
 */
enum class PluginStream(val channel: String) {
    STABLE("stable"),
    TESTING("testing"),
    UNSTABLE("unstable");

    /** Whether a release on this stream is offered to someone who chose [chosen]. */
    fun offeredTo(chosen: PluginStream): Boolean = ordinal <= chosen.ordinal

    companion object {
        fun fromChannel(channel: String?, prerelease: Boolean): PluginStream =
            entries.firstOrNull { it.channel == channel }
                // Envelopes from before streams existed: GitHub's own flag is the
                // only signal, and a pre-release was the old "not yet proven".
                ?: if (prerelease) TESTING else STABLE
    }
}

/** One signed engine bundle advertised by a repository's GitHub release. */
data class AvailablePlugin(
    val manifest: EngineBundleManifest,
    val signatures: BundleSignatures,
    /** Who signed it, as verified when the catalog was read ([BundleVerifier], moment CATALOG). */
    val signer: BundleSigner,
    val releaseTag: String,
    val archiveUrl: String,
    val archiveSha256: String?,
    val stream: PluginStream,
    /** The archive's size in bytes as its release lists it, 0 when unknown (a catalog cached before sizes were kept). */
    val archiveSize: Long = 0,
) {
    val info: PluginInfo get() = manifest.info
    val origin: String get() = manifest.origin
    val bundleId: String get() = manifest.bundleId
    val apiVersion: Int get() = manifest.apiVersion
    val signerFingerprints: Set<String> get() = setOf(signer.keySha256)
}

/** One file of a release: where to download it, its digest when the host states one, and its size (0 when unknown). */
data class ReleaseAsset(val url: String, val sha256: String?, val size: Long)

object PluginReleaseReader {
    /**
     * A release catalog asset is a single JSON envelope containing copies of
     * each bundle's internally signed header. The bundle remains independently
     * verifiable; this envelope only avoids downloading every archive to browse.
     */
    fun parse(
        raw: String,
        expectedOrigin: String,
        releaseTag: String,
        prerelease: Boolean,
        releaseAssets: Map<String, ReleaseAsset>,
        trust: BundleTrust,
    ): List<AvailablePlugin> {
        val root = JSONObject(raw)
        require(root.getInt("formatVersion") == 1) { "Unsupported release catalog envelope" }
        val stream = PluginStream.fromChannel(root.optString("channel").takeIf(String::isNotBlank), prerelease)
        val bundles = root.getJSONArray("bundles")
        return (0 until bundles.length()).mapNotNull { index ->
            val envelope = bundles.getJSONObject(index)
            val manifestBytes = Base64.getDecoder().decode(envelope.requiredString("manifestBase64"))
            val signatures = envelopeSignatures(envelope)
            val manifest = EngineBundleManifestReader.parse(manifestBytes)
            require(manifest.origin == normalizeGithubOrigin(expectedOrigin)) { "Release origin mismatch" }
            // A release signed only the legacy way cannot be installed
            // (BundleVerifier). It is not offered, and it says nothing about
            // the repository's key: it simply predates format v1.
            if (signatures.jws == null) return@mapNotNull null
            val signer = try {
                trust.verify(manifest, signatures, VerificationMoment.CATALOG)
            } catch (refused: IllegalArgumentException) {
                throw SignerChangedException(manifest.origin, claimedSigner(signatures), refused)
            } catch (refused: java.security.GeneralSecurityException) {
                throw SignerChangedException(manifest.origin, claimedSigner(signatures), refused)
            }
            val asset = requireNotNull(releaseAssets[manifest.assetName]) {
                "Release is missing ${manifest.assetName}"
            }
            AvailablePlugin(
                manifest,
                signatures,
                signer,
                releaseTag,
                asset.url,
                asset.sha256?.let(::normalizeGithubDigest),
                stream,
                asset.size,
            )
        }
    }
}

/** What one look at a repository's releases produced. */
sealed interface CatalogFetch {
    data class Fetched(val plugins: List<AvailablePlugin>, val etag: String?) : CatalogFetch

    /** GitHub answered 304: what we hold is still current, and it cost no allowance. */
    object Unchanged : CatalogFetch
}

class GithubPluginCatalogClient(private val context: Context) {
    /**
     * Every release of [origin], on every stream. Releases are read
     * whatever GitHub's pre-release flag says and the envelope's own
     * channel is kept on each one; which streams a person is actually
     * offered is decided where the catalog is read, not here, so the cache
     * this fills in can answer "what does Testing have" without a second
     * fetch.
     *
     * [knownEtag] is the ETag stored with the catalog we already hold. GitHub
     * answers 304 when the release list is unchanged, and a 304 is not
     * counted against the unauthenticated allowance of 60 requests an hour --
     * which the eleven default origins otherwise spend in a handful of
     * refreshes from one address.
     */
    fun fetch(origin: String, knownEtag: String? = null): CatalogFetch {
        val normalized = normalizeGithubOrigin(origin)
        val match = GITHUB_ORIGIN.matchEntire(normalized) ?: error("Not a GitHub repository origin")
        val client = GithubClients.withToken(context)
        // Only the first page carries the condition: it is the one whose ETag we stored, and an unchanged
        // first page means an unchanged list.
        val listing = when (val found = client.listReleases("${match.groupValues[1]}/${match.groupValues[2]}", knownEtag)) {
            ReleaseListing.Unchanged -> return CatalogFetch.Unchanged
            is ReleaseListing.Fetched -> found
        }
        val result = mutableListOf<AvailablePlugin>()
        for (release in listing.releases) {
            if (release.draft) continue
            val byName = linkedMapOf<String, ReleaseAsset>()
            var catalogUrl: String? = null
            for (asset in release.assets) {
                byName[asset.name] = ReleaseAsset(asset.downloadUrl, asset.digest, asset.size)
                if (asset.name in CatalogRefresh.RELEASE_ENVELOPES) catalogUrl = asset.downloadUrl
            }
            if (catalogUrl == null) continue
            result += PluginReleaseReader.parse(
                checkNotNull(client.get(catalogUrl, maxBytes = MAX_RESPONSE_BYTES)).text(),
                normalized,
                release.tag,
                release.prerelease,
                byName,
                BundleTrust(context),
            )
        }
        return CatalogFetch.Fetched(result, listing.etag)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    }
}

object AvailablePluginResolver {
    fun compatible(
        plugins: List<AvailablePlugin>,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
    ): List<Pair<AvailablePlugin, EngineCapability>> {
        val requestedContext = effectiveEngineContext(
            engineContext,
            plugins.filter { it.manifest.component == null }.flatMap { p -> p.info.capabilities.filter { p.info.engineOf(it) == engine }.map { it.engineContext } },
        )
        return plugins.asSequence()
            .filter { it.manifest.component == null }
            .filter { it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION }
            .filter { it.info.runs(engine) }
            .filter { pluginVersionAllowlist == null || pluginVersionAllowlist.matches(it.info.pluginVersion) }
            .flatMap { plugin -> plugin.info.capabilities.asSequence().map { plugin to it } }
            .filter { (plugin, capability) ->
                plugin.info.engineOf(capability) == engine && capability.engineContext == requestedContext &&
                    capability.supports(engineVersion) && capability.canServe(runtimeRequirements)
            }
            .sortedWith(
                compareByDescending<Pair<AvailablePlugin, EngineCapability>> { it.second.runtimeVersion == engineVersion }
                    .thenBy { it.second.specificityFor(engineVersion) }
                    .thenBy { it.second.unrequestedComponents(runtimeRequirements) }
                    .thenByDescending { it.first.info.pluginVersion }
                    .thenBy { it.first.bundleId },
            )
            .toList()
    }
}

class PluginCatalogCache(private val context: Context) {
    private val directory = File(context.filesDir, "plugin-catalogs-v2").apply { mkdirs() }
    private val trust = BundleTrust(context)

    /**
     * Stores a catalog and the validator that produced it: an HTTP ETag from
     * the GitHub API, or the plugins index's fingerprint for this origin.
     * Both answer the same question on the next refresh -- is what we hold
     * still what the source has -- so they share one field.
     */
    fun save(origin: String, plugins: List<AvailablePlugin>, etag: String? = null) {
        val root = JSONObject().put("origin", normalizeGithubOrigin(origin)).put(
            "plugins",
            JSONArray().apply { plugins.forEach { put(it.toJson()) } },
        )
        etag?.let { root.put("etag", it) }
        file(origin).writeText(root.toString())
        synchronized(decoded) { decoded.remove(file(origin).path) }
        // A stored catalog can change what droidtop may offer to install (EngineCores).
        EngineCores.reportChanged(context)
    }

    /** When a catalog was last stored, in ms since the epoch, 0 when none is. */
    fun storedAtMs(): Long = directory.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L

    /** The validator stored with this origin's catalog, if it has one. */
    fun etag(origin: String): String? = runCatching {
        JSONObject(file(origin).readText()).optString("etag").takeIf(String::isNotBlank)
    }.getOrNull()

    /**
     * The stored catalog of [origin], every entry's signature verified and its
     * signer still pinned. Parsing and verifying a catalog is the expensive
     * part and depends only on the file, so it is done once per version of
     * the file for the life of the process; the pin is asked every time,
     * since a custom origin's key can be removed.
     */
    fun load(origin: String): List<AvailablePlugin> {
        val file = file(origin)
        val version = file.lastModified() to file.length()
        val plugins = synchronized(decoded) { decoded[file.path]?.takeIf { it.first == version }?.second }
            ?: runCatching {
                val array = JSONObject(file.readText()).getJSONArray("plugins")
                // Entry by entry: one a newer rule refuses (a legacy-only build
                // cached by an older Enginehost) leaves the others listed.
                (0 until array.length()).mapNotNull { runCatching { availablePluginFromJson(array.getJSONObject(it), trust) }.getOrNull() }
            }.getOrDefault(emptyList()).also { synchronized(decoded) { decoded[file.path] = version to it } }
        return plugins.takeIf { all ->
            all.all { trust.stillTrusted(it.origin, it.signer) }
        }.orEmpty()
    }

    fun loadAll(origins: Collection<String>): List<AvailablePlugin> = origins.flatMap(::load)

    /** Forget every fetched catalog, so the next refresh starts from nothing. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
        synchronized(decoded) { decoded.clear() }
    }

    /** Whether a release refresh has ever stored a catalog for this origin, even an empty one. */
    fun hasFetched(origin: String): Boolean = file(origin).isFile

    /**
     * Whether the stored catalog for this origin is older than [maxAgeMs].
     * A catalog describes builds by checksum, and a build published after
     * the catalog was stored is invisible until it is fetched again; a stale
     * catalog quietly installs the previous build. An origin never fetched
     * counts as stale.
     */
    fun isStale(origin: String, maxAgeMs: Long): Boolean {
        val f = file(origin)
        return !f.isFile || System.currentTimeMillis() - f.lastModified() > maxAgeMs
    }

    private fun file(origin: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(normalizeGithubOrigin(origin).toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        return File(directory, "$name.json")
    }

    private companion object {
        /** Verified catalogs by file path, with the file's (modified, length) they were read at. */
        val decoded = HashMap<String, Pair<Pair<Long, Long>, List<AvailablePlugin>>>()
    }
}

private fun AvailablePlugin.toJson() = JSONObject()
    .put("manifestBase64", Base64.getEncoder().encodeToString(manifest.rawBytes))
    .putOpt("jws", signatures.jws)
    .put("releaseTag", releaseTag)
    .put("archiveUrl", archiveUrl)
    .put("archiveSha256", archiveSha256)
    .put("stream", stream.channel)
    .put("archiveSize", archiveSize)

/**
 * A release envelope entry's signature: its `jws` (format v1). An entry's
 * legacy `signatureBase64`, which older envelopes and cached catalogs carry
 * beside it or alone, is not read: nothing verifies it any more.
 */
internal fun envelopeSignatures(json: JSONObject) = BundleSignatures(json.optString("jws").takeIf(String::isNotBlank))

/** The key a refused entry claims to be signed by, for the key-changed prompt: its v1 leaf's. */
private fun claimedSigner(signatures: BundleSignatures): String =
    signatures.jws?.let { runCatching { CertificateSignatures.claimedLeafKeySha256(it) }.getOrNull() }
        ?: "0".repeat(64)

private fun availablePluginFromJson(json: JSONObject, trust: BundleTrust): AvailablePlugin {
    val manifestBytes = Base64.getDecoder().decode(json.requiredString("manifestBase64"))
    val signatures = envelopeSignatures(json)
    val manifest = EngineBundleManifestReader.parse(manifestBytes)
    return AvailablePlugin(
        manifest,
        signatures,
        trust.verify(manifest, signatures, VerificationMoment.CATALOG),
        json.requiredString("releaseTag"),
        json.requiredString("archiveUrl"),
        json.optString("archiveSha256").takeIf(String::isNotBlank)?.let(::normalizeGithubDigest),
        PluginStream.fromChannel(json.optString("stream").takeIf(String::isNotBlank), json.optBoolean("prerelease")),
        json.optLong("archiveSize", 0),
    )
}

/**
 * A repository a person added from the third-party list (Quick add), with
 * who the list says maintains it and the key both the list and the repository
 * named. Never official: its key is a custom pin like any repository added by
 * URL, and this record only says where the person found it.
 */
data class ThirdPartyOrigin(
    val origin: String,
    val maintainerId: String,
    val maintainerName: String,
    val keySha256: String,
)

/**
 * The one check between "the list offers this repository" and "pin its key":
 * the key document the plugins index lists for it and the one the repository
 * itself publishes must be the same key for the same origin. Either source
 * alone could be rewritten by whoever controls it; both at once is what a
 * person is shown and accepts (docs/security/2026-09-25-third-party-catalog.md,
 * T2).
 */
object ThirdPartyListing {
    fun accept(listed: IndexedOrigin, publishedKeyDocument: String): ThirdPartyOrigin {
        require(listed.trust == IndexedTrust.THIRD_PARTY) { "Not a third-party repository" }
        val maintainer = requireNotNull(listed.maintainer) { "The list names no maintainer for this repository" }
        val listedKey = OriginKeyDocuments.parse(requireNotNull(listed.keyDocument) { "The list names no key for this repository" })
        val publishedKey = OriginKeyDocuments.parse(publishedKeyDocument)
        require(listedKey.origin == listed.origin && publishedKey.origin == listed.origin) {
            "The repository's key names a different repository"
        }
        require(listedKey.fingerprint == publishedKey.fingerprint) {
            "The repository publishes a different key than the list names for it"
        }
        return ThirdPartyOrigin(listed.origin, maintainer.id, maintainer.name, listedKey.fingerprint)
    }
}

class PluginOriginStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origins-v2", Context.MODE_PRIVATE)
    private val thirdPartyPreferences = context.getSharedPreferences("plugin-origins-third-party-v1", Context.MODE_PRIVATE)
    private val keys get() = PluginOriginKeyStore(context)

    /** Compiled-in origins, official ones learned from the plugins index, and the person's own. */
    fun all(): List<String> = (DEFAULT_ORIGINS + rootCertified() + custom()).distinct().sorted()

    fun add(origin: String, rawKeyDocument: String) {
        val normalized = normalizeGithubOrigin(origin)
        require(GITHUB_ORIGIN.matches(normalized)) { "Use a GitHub repository URL" }
        // Enginehost's own repositories are verified to the signing root; a person's key never stands in for it.
        require(!isOfficial(normalized)) { "Official repository keys cannot be replaced" }
        keys.importCustom(rawKeyDocument, normalized)
        preferences.edit().putStringSet(CUSTOM, custom() + normalized).apply()
    }

    fun remove(origin: String) {
        val normalized = normalizeGithubOrigin(origin)
        preferences.edit().putStringSet(CUSTOM, custom() - normalized).apply()
        thirdPartyPreferences.edit().remove(normalized).apply()
        keys.removeCustom(normalized)
    }

    /** Part of Enginehost's own catalog (compiled in, or root-certified): not the person's to remove. */
    fun isOfficial(origin: String): Boolean {
        val normalized = normalizeGithubOrigin(origin)
        return normalized in DEFAULT_ORIGINS || normalized in rootCertified()
    }

    /**
     * Adds every origin the index proves official: a certificate chain that
     * ends at the Enginehost signing root key this APK pins and names the
     * origin (format v1). It is verified here; what the index says about
     * trust is not consulted, so nothing but the signing root can make an
     * origin official. A person's own entry for the same repository is folded
     * into the official one. Answers the origins that were not known before.
     */
    fun learnOfficial(index: PluginsIndex): List<String> {
        val before = all().toSet()
        val root = EnginehostRoot.get(context)
        val revoked = RevocationListStore(context).revoked(BundleVerifier.ENGINEHOST_CATALOG)
        index.origins.values.forEach { indexed ->
            // Format v1: a bundle-signing certificate the signing root issued, naming the origin.
            val certified = root != null && indexed.certificates.isNotEmpty() && runCatching {
                val chain = CertificateSignatures.verifyChain(
                    CertificateSignatures.parseChain(indexed.certificates), SignaturePurpose.PLUGIN, root, revoked,
                )
                VerifiedJws(chain, ByteArray(0)).namesOrigin(indexed.origin)
            }.getOrDefault(false)
            if (!certified) return@forEach
            preferences.edit().putStringSet(ROOT_CERTIFIED, rootCertified() + indexed.origin).apply()
            if (indexed.origin in custom()) {
                preferences.edit().putStringSet(CUSTOM, custom() - indexed.origin).apply()
                keys.removeCustom(indexed.origin)
            }
            thirdPartyPreferences.edit().remove(indexed.origin).apply()
        }
        return all() - before
    }

    /**
     * Pins the key a person-added repository now publishes, after they
     * accepted it: the same as removing the repository and adding it anew.
     * Plugins installed under the old key no longer verify and must be
     * installed again under the new one and approved. A third-party
     * repository moves only to a key the third-party list names ([listed]);
     * an official origin never moves this way (its bundles are verified to the
     * signing root, [learnOfficial]).
     */
    fun replaceKey(origin: String, publishedKeyDocument: String, listed: IndexedOrigin?) {
        val normalized = normalizeGithubOrigin(origin)
        require(!isOfficial(normalized)) { "An official repository's bundles are verified to the signing root" }
        val wasThirdParty = thirdParty(normalized) != null
        if (wasThirdParty) {
            // Checked before anything is removed, so a refusal changes nothing.
            ThirdPartyListing.accept(
                requireNotNull(listed) { "The third-party list does not name this repository any more" },
                publishedKeyDocument,
            )
        } else {
            OriginKeyDocuments.parse(publishedKeyDocument).also {
                require(it.origin == normalized) { "Repository key declares a different origin" }
            }
        }
        remove(normalized)
        if (wasThirdParty) addThirdParty(listed!!, publishedKeyDocument) else add(normalized, publishedKeyDocument)
    }

    /** Adds a listed third-party repository, once its own key agrees with the list's; see [ThirdPartyListing]. */
    fun addThirdParty(listed: IndexedOrigin, publishedKeyDocument: String): ThirdPartyOrigin {
        val accepted = ThirdPartyListing.accept(listed, publishedKeyDocument)
        add(accepted.origin, publishedKeyDocument)
        thirdPartyPreferences.edit().putString(
            accepted.origin,
            JSONObject()
                .put("maintainerId", accepted.maintainerId)
                .put("maintainerName", accepted.maintainerName)
                .put("keySha256", accepted.keySha256)
                .toString(),
        ).apply()
        return accepted
    }

    /** How [origin] was added from the third-party list, or null when it was not. */
    fun thirdParty(origin: String): ThirdPartyOrigin? {
        val normalized = normalizeGithubOrigin(origin)
        if (normalized !in custom()) return null
        return thirdPartyPreferences.getString(normalized, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                ThirdPartyOrigin(
                    normalized,
                    json.getString("maintainerId"),
                    json.getString("maintainerName"),
                    json.getString("keySha256"),
                )
            }.getOrNull()
        }
    }

    fun thirdParties(): List<ThirdPartyOrigin> = custom().mapNotNull(::thirdParty)

    private fun custom(): Set<String> = preferences.getStringSet(CUSTOM, emptySet())?.toSet().orEmpty()

    /** Origins the index showed a certificate from the signing root for ([learnOfficial]). */
    private fun rootCertified(): Set<String> = preferences.getStringSet(ROOT_CERTIFIED, emptySet())?.toSet().orEmpty()

    companion object {
        private const val CUSTOM = "custom"
        private const val ROOT_CERTIFIED = "root-certified"
        val DEFAULT_ORIGINS = setOf(
            "https://github.com/droidtop/enginehost-renpy-plugin",
            "https://github.com/droidtop/enginehost-rpgmaker-mkxp-z-plugin",
            "https://github.com/droidtop/enginehost-rpgmaker-easyrpg-plugin",
            "https://github.com/droidtop/enginehost-rpgmaker-mv-mz-plugin",
            "https://github.com/droidtop/enginehost-kirikiri-plugin",
            "https://github.com/droidtop/enginehost-buriko-plugin",
            "https://github.com/droidtop/enginehost-catsystem2-plugin",
            "https://github.com/droidtop/enginehost-cmvs-plugin",
            "https://github.com/droidtop/enginehost-flash-air-plugin",
            "https://github.com/droidtop/enginehost-html-plugin",
            "https://github.com/droidtop/enginehost-godot-plugin",
        )
    }
}

internal val GITHUB_ORIGIN = Regex("https://github\\.com/([^/]+)/([^/]+)", RegexOption.IGNORE_CASE)
internal fun normalizeGithubOrigin(value: String): String {
    val candidate = value.trim().trimEnd('/').removeSuffix(".git")
    val match = GITHUB_ORIGIN.matchEntire(candidate) ?: return candidate
    return "https://github.com/${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
}
private fun normalizeGithubDigest(value: String): String = value.removePrefix("sha256:").uppercase().also {
    require(it.matches(Regex("[A-F0-9]{64}"))) { "Invalid GitHub asset digest" }
}
