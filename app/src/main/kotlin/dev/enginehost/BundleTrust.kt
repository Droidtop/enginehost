package dev.enginehost

import android.content.Context
import java.io.FileNotFoundException
import java.util.Date

/**
 * The Droidtop Core certificate this build pins, the one anchor of every
 * official chain (Droidtop/tracker#550): `assets/trust/droidtop-core.pem`.
 * It is public material the owner commits; until it is committed, a build
 * pins no Core, no official v1 signature can be verified, and
 * [BundleVerifier] keeps accepting the legacy format for new installs.
 */
object CoreCertificate {
    const val ASSET = "trust/droidtop-core.pem"

    @Volatile private var loaded = false
    @Volatile private var anchor: TrustAnchor.Core? = null

    fun get(context: Context): TrustAnchor.Core? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                anchor = try {
                    context.assets.open(ASSET).bufferedReader().use { reader ->
                        TrustAnchor.Core(CertificateSignatures.parsePemCertificate(reader.readText()))
                    }
                } catch (absent: FileNotFoundException) {
                    null
                }
                loaded = true
            }
        }
        return anchor
    }
}

/**
 * Revocation lists, one per catalog: Enginehost's own ("enginehost", beside
 * the plugins index, under Core) and each added repository's (`owner/repo`,
 * its own `revocations.jws`, under the key the person pinned for it).
 *
 * A list is taken only when its sequence is higher than the one held, so an
 * old list cannot be replayed to un-revoke anything, and its signer must not
 * be revoked by the list it replaces. What is held is verified again whenever
 * it is first read in a process; a list that no longer verifies (its anchor
 * gone) revokes nothing, the same as no list.
 */
class RevocationListStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("revocation-lists-v1", Context.MODE_PRIVATE)

    fun revoked(catalog: String): RevokedSet = held(catalog)?.revoked ?: RevokedSet.NONE

    /** Takes [compact] for [catalog] when it verifies and is newer than the list held. Answers whether it did. */
    fun accept(catalog: String, compact: String): Boolean {
        val key = catalog.lowercase()
        val anchor = anchorFor(key) ?: return false
        val current = held(key)
        val offered = runCatching {
            RevocationList.verify(compact, anchor, key, Date(), current?.revoked ?: RevokedSet.NONE)
        }.getOrNull() ?: return false
        if (current != null && offered.sequence <= current.sequence) return false
        preferences.edit().putString(key, offered.compact).apply()
        synchronized(Companion) { verified[key] = offered }
        return true
    }

    /**
     * Fetches the revocation list a repository a person added publishes
     * (`revocations.jws`, on its plugin branch like its key document) and
     * takes it as [accept] does. At most once a day per repository: each
     * look costs GitHub API requests from the 60 an hour an address gets.
     */
    fun refreshAdded(origin: String, now: Long = System.currentTimeMillis()) {
        val catalog = VerifiedJws.originId(origin)
        val checkedKey = "checked:$catalog"
        if (now - preferences.getLong(checkedKey, 0) < ADDED_CHECK_INTERVAL_MS) return
        preferences.edit().putLong(checkedKey, now).apply()
        GithubRepositoryFile.fetch(origin, CatalogRefresh.REVOCATIONS_FILE)?.let { accept(catalog, it) }
    }

    private fun held(catalog: String): RevocationList? {
        val key = catalog.lowercase()
        val compact = preferences.getString(key, null) ?: return null
        synchronized(Companion) { verified[key]?.takeIf { it.compact == compact }?.let { return it } }
        val anchor = anchorFor(key) ?: return null
        return runCatching { RevocationList.verify(compact, anchor, key, now = null) }.getOrNull()
            ?.also { list -> synchronized(Companion) { verified[key] = list } }
    }

    private fun anchorFor(catalog: String): TrustAnchor? =
        if (catalog == BundleVerifier.ENGINEHOST_CATALOG) {
            CoreCertificate.get(context)
        } else {
            PluginOriginKeyStore(context).custom("https://github.com/$catalog")
                ?.let { TrustAnchor.PinnedKey(it.publicKeySpki) }
        }

    private companion object {
        const val ADDED_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Lists verified in this process, by catalog, with the exact text they were verified from. */
        val verified = HashMap<String, RevocationList>()
    }
}

/** [BundleVerifier]'s sources on a device: the pinned Core, the person's pins, the held revocation lists. */
class BundleTrust(private val context: Context) : BundleTrustSources {
    private val revocations = RevocationListStore(context)

    override val core: TrustAnchor.Core? get() = CoreCertificate.get(context)

    override fun customKey(origin: String): ByteArray? = PluginOriginKeyStore(context).custom(origin)?.publicKeySpki

    override fun revoked(catalog: String): RevokedSet = revocations.revoked(catalog)

    override fun legacy(manifestBytes: ByteArray, origin: String, signature: ByteArray, moment: VerificationMoment) =
        LegacyBundleSignatures.signer(context, manifestBytes, origin, signature, moment, ::revoked)

    fun verify(manifest: EngineBundleManifest, signatures: BundleSignatures, moment: VerificationMoment): BundleSigner =
        BundleVerifier.verify(manifest.rawBytes, manifest.origin, manifest.bundleId, signatures, moment, this, Date())

    /**
     * Whether a signer verified earlier still holds without verifying again:
     * nothing it rests on is revoked, and its anchor is still the one pinned
     * (Core for official and developer certificates, the person's key for
     * their repository, the pinned key for a legacy signer). Cheap enough for
     * every read of a cached catalog.
     */
    fun stillTrusted(origin: String, signer: BundleSigner): Boolean {
        val catalog = if (signer.kind == SignerKind.CUSTOM) VerifiedJws.originId(origin) else BundleVerifier.ENGINEHOST_CATALOG
        if (signer.isRevokedBy(revoked(catalog))) return false
        return when {
            signer.format == 0 -> LegacyBundleSignatures.stillPinned(context, origin, signer)
            signer.kind == SignerKind.CUSTOM ->
                PluginOriginKeyStore(context).custom(origin)?.fingerprint == signer.anchorKeySha256
            else -> core?.keySha256 == signer.anchorKeySha256
        }
    }
}
