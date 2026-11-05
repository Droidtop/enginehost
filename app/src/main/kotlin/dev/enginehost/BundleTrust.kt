package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.io.FileNotFoundException
import java.time.Instant

/**
 * The Enginehost signing roots this build pins: `assets/trust/enginehost-roots.json` lists them, each naming a PEM
 * `PUBLIC KEY` file beside it, the current root first. The owner derives a root from the Droidtop CA, which Enginehost
 * never pins (Droidtop/tracker#550); its private key stays on the owner's machine. A build without the list pins no root:
 * no official v1 signature can be verified, and only a repository the person pinned a key for installs.
 *
 * Across the key rotation of 2026-11-04 the list holds two: the new root, with no end, and the old one with `notAfter`
 * (owner: "Pin the old key for 7 days, then expire it. That should give time for users to organically move off the old
 * cores"). That instant is the one constant of the rotation; `scripts/bundle_jws.py` reads the same file, so tooling and
 * app agree. After it a chain ending at the old root is refused ([TrustAnchor.Root.inForceAt]).
 */
object EnginehostRoots {
    const val ASSET_DIR = "trust"
    const val LIST = "$ASSET_DIR/enginehost-roots.json"

    @Volatile private var loaded: List<TrustAnchor.Root>? = null

    /** Every pinned root, retired ones included (a retired root is refused by its own term, not by being absent). */
    fun get(context: Context): List<TrustAnchor.Root> {
        loaded?.let { return it }
        return synchronized(this) {
            loaded ?: read(context).also { loaded = it }
        }
    }

    private fun read(context: Context): List<TrustAnchor.Root> = try {
        parse(context.assets.open(LIST).bufferedReader().use { it.readText() }) { name ->
            context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
        }
    } catch (absent: FileNotFoundException) {
        emptyList()
    }

    /** Reads the list [manifest]; [readKey] answers a listed key file's PEM text. Split out so a plain JVM test runs it. */
    fun parse(manifest: String, readKey: (String) -> String): List<TrustAnchor.Root> {
        val entries = JSONObject(manifest).getJSONArray("roots")
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val key = entry.getString("key")
            require(key.isNotEmpty() && '/' !in key && '\\' !in key) { "Not a key file name: $key" }
            val notAfter = entry.optString("notAfter").takeIf(String::isNotEmpty)?.let { Instant.parse(it).epochSecond }
            TrustAnchor.Root(CertificateSignatures.parsePemPublicKey(readKey(key)), notAfter)
        }
    }
}

/**
 * Revocation lists, one per catalog: Enginehost's own ("enginehost", beside
 * the plugins index, under the Enginehost root, or under either root while two are in force; it applies to chains
 * ending at either) and each added repository's (`owner/repo`,
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
        val anchors = anchorsFor(key)
        if (anchors.isEmpty()) return false
        val current = held(key)
        val offered = runCatching {
            RevocationList.verifyUnderAny(compact, anchors, key, current?.revoked ?: RevokedSet.NONE)
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
        val anchors = anchorsFor(key)
        if (anchors.isEmpty()) return null
        return runCatching { RevocationList.verifyUnderAny(compact, anchors, key) }.getOrNull()
            ?.also { list -> synchronized(Companion) { verified[key] = list } }
    }

    private fun anchorsFor(catalog: String): List<TrustAnchor> =
        if (catalog == BundleVerifier.ENGINEHOST_CATALOG) {
            EnginehostRoots.get(context)
        } else {
            listOfNotNull(
                PluginOriginKeyStore(context).custom("https://github.com/$catalog")?.let { TrustAnchor.PinnedKey(it.publicKeySpki) },
            )
        }

    private companion object {
        const val ADDED_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Lists verified in this process, by catalog, with the exact text they were verified from. */
        val verified = HashMap<String, RevocationList>()
    }
}

/** [BundleVerifier]'s sources on a device: the pinned root, the person's pins, the held revocation lists. */
class BundleTrust(private val context: Context) : BundleTrustSources {
    private val revocations = RevocationListStore(context)

    override val roots: List<TrustAnchor.Root> get() = EnginehostRoots.get(context)

    override fun customKey(origin: String): ByteArray? = PluginOriginKeyStore(context).custom(origin)?.publicKeySpki

    override fun revoked(catalog: String): RevokedSet = revocations.revoked(catalog)

    fun verify(manifest: EngineBundleManifest, signatures: BundleSignatures, moment: VerificationMoment): BundleSigner =
        BundleVerifier.verify(manifest.rawBytes, manifest.origin, manifest.bundleId, signatures, moment, this)

    /**
     * Whether a signer verified earlier still holds without verifying again:
     * nothing it rests on is revoked, and its anchor is still pinned and in force
     * (an Enginehost root for official and developer certificates, the person's key for
     * their repository). A legacy signer never holds, nor one under a retired root. Cheap enough for
     * every read of a cached catalog.
     */
    fun stillTrusted(origin: String, signer: BundleSigner): Boolean {
        val catalog = if (signer.kind == SignerKind.CUSTOM) VerifiedJws.originId(origin) else BundleVerifier.ENGINEHOST_CATALOG
        if (signer.isRevokedBy(revoked(catalog))) return false
        return when {
            signer.format == 0 -> false
            signer.kind == SignerKind.CUSTOM ->
                PluginOriginKeyStore(context).custom(origin)?.fingerprint == signer.anchorKeySha256
            else -> roots.any { it.keySha256 == signer.anchorKeySha256 && it.inForceAt(now()) }
        }
    }
}
