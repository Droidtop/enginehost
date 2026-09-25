package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class PluginOriginKey(
    val origin: String,
    val algorithm: String,
    val publicKeySpki: ByteArray,
    val fingerprint: String,
)

/**
 * Pinned repository keys, in three kinds that never mix:
 *
 * - **built in**: compiled into this APK and certified by the official root;
 * - **certified**: an official origin's key that arrived later, in the plugins
 *   index, and carries the official root's certificate for exactly that
 *   origin, verified here -- a new official repository reaches devices
 *   without an app release, and nothing but the offline root can make one;
 * - **custom**: a key a person accepted for a repository they added (by URL,
 *   or from the third-party list), trusted on first use.
 *
 * Built in beats certified beats custom: a certified key never replaces a
 * compiled-in one, and replaces a custom pin for the same origin, since the
 * root's certificate is a stronger statement than first use
 * (docs/security/2026-09-25-third-party-catalog.md, T9).
 */
class PluginOriginKeyStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origin-keys-v1", Context.MODE_PRIVATE)
    private val certifiedPreferences =
        context.getSharedPreferences("plugin-origin-keys-certified-v1", Context.MODE_PRIVATE)

    fun get(origin: String): PluginOriginKey? {
        val normalized = normalizeGithubOrigin(origin)
        return builtIns()[normalized] ?: certified()[normalized]
            ?: preferences.getString(normalized, null)?.let(OriginKeyDocuments::parse)
    }

    fun importCustom(raw: String, expectedOrigin: String? = null): PluginOriginKey {
        val key = OriginKeyDocuments.parse(raw)
        expectedOrigin?.let {
            require(key.origin == normalizeGithubOrigin(it)) { "Repository key declares a different origin" }
        }
        require(key.origin !in builtIns() && key.origin !in certified()) {
            "Official repository keys cannot be replaced"
        }
        val existing = get(key.origin)
        require(existing == null || existing.fingerprint == key.fingerprint) {
            "Repository signing key changed; a verified rotation is required"
        }
        preferences.edit().putString(key.origin, raw).apply()
        return key
    }

    /**
     * Pins an official origin's key from a key document that carries the
     * official root's certificate, or answers null when it does not verify
     * or the origin's key is compiled in. A custom pin for the same origin is
     * dropped: the certificate settles which key is the origin's.
     */
    fun importCertified(raw: String): PluginOriginKey? {
        val key = runCatching { parseCertified(raw) }.getOrNull() ?: return null
        if (key.origin in builtIns()) return null
        if (certified()[key.origin]?.fingerprint != key.fingerprint) {
            certifiedPreferences.edit().putString(key.origin, raw).apply()
            synchronized(Companion) { certifiedKeys = null }
        }
        preferences.edit().remove(key.origin).apply()
        return key
    }

    /** Official origins learned from the plugins index, beyond the compiled-in ones. */
    fun certifiedOrigins(): Set<String> = certified().keys

    /**
     * Whether [fingerprint] may sign bundles for [origin].
     *
     * The primary developer's key is not origin-scoped: it signs locally
     * rebuilt bundles for any repository, and every surface that reports
     * trust marks such a bundle as a developer build so it cannot pass for
     * an official one. It is accepted only where a bundle arrives as a file
     * a person put on the device ([allowDeveloper], the default): a
     * repository's published releases must carry that repository's own key,
     * so the one key that is good for every origin can never reach a device
     * through the catalog or an update, whoever manages to publish there.
     */
    fun matches(origin: String, fingerprint: String, allowDeveloper: Boolean = true): Boolean {
        val normalized = fingerprint.uppercase()
        if (get(origin)?.fingerprint == normalized) return true
        return allowDeveloper && isDeveloperDebug(normalized)
    }

    /** The primary developer's own signing key, certified by the official root. */
    fun developerDebug(): PluginOriginKey? = synchronized(Companion) {
        if (!developerKeyRead) {
            developerKey = runCatching {
                context.resources.openRawResource(R.raw.developer_debug_key)
                    .bufferedReader().use { parseCertified(it.readText()) }
            }.getOrNull()
            developerKeyRead = true
        }
        developerKey
    }

    fun isDeveloperDebug(fingerprint: String): Boolean =
        developerDebug()?.fingerprint == fingerprint.uppercase()

    /** Whether [fingerprint] is the root-certified key of [origin]: compiled in, or learned from the index. */
    fun isOfficial(origin: String, fingerprint: String): Boolean {
        val normalized = normalizeGithubOrigin(origin)
        val expected = fingerprint.uppercase()
        return builtIns()[normalized]?.fingerprint == expected || certified()[normalized]?.fingerprint == expected
    }

    fun removeCustom(origin: String) {
        val normalized = normalizeGithubOrigin(origin)
        if (normalized !in builtIns() && normalized !in certified()) preferences.edit().remove(normalized).apply()
    }

    private fun builtIns(): Map<String, PluginOriginKey> = builtInKeys ?: readBuiltIns().also { builtInKeys = it }

    private fun readBuiltIns(): Map<String, PluginOriginKey> = context.resources.openRawResource(R.raw.default_plugin_keys)
        .bufferedReader().use { reader ->
            val root = JSONObject(reader.readText())
            require(root.getInt("formatVersion") == 1)
            val keys = root.getJSONArray("keys")
            (0 until keys.length()).associate { index ->
                val key = parseCertified(keys.getJSONObject(index).toString())
                key.origin to key
            }
        }

    /**
     * Learned official keys, each certificate verified again when the process
     * first reads them: what is stored is the document, and the certificate is
     * what makes it official, not the fact that it was stored.
     */
    private fun certified(): Map<String, PluginOriginKey> = certifiedKeys ?: synchronized(Companion) {
        certifiedKeys ?: certifiedPreferences.all.values.filterIsInstance<String>()
            .mapNotNull { raw -> runCatching { parseCertified(raw) }.getOrNull() }
            .associateBy { it.origin }
            .also { certifiedKeys = it }
    }

    private fun parseCertified(raw: String): PluginOriginKey {
        val root = context.resources.openRawResource(R.raw.official_plugin_root_key)
            .bufferedReader().use { JSONObject(it.readText()) }
        return OriginKeyDocuments.parseCertified(raw, root)
    }

    companion object {
        /*
         * The built-in keys and the developer key come from this APK's own
         * raw resources and are certified by the official root, so nothing
         * can change them while the process lives. They are read and their
         * certificates verified once per process: every lookup used to do it
         * again, a dozen root verifications for each cached catalog manifest
         * on every redraw of the catalog. Learned certified keys are cached
         * the same way and dropped when one is imported.
         */
        @Volatile private var builtInKeys: Map<String, PluginOriginKey>? = null
        @Volatile private var certifiedKeys: Map<String, PluginOriginKey>? = null
        private var developerKey: PluginOriginKey? = null
        private var developerKeyRead = false
    }
}

/** Repository key documents (`enginehost-public-key.json`), free of Android so they can be tested. */
object OriginKeyDocuments {
    fun parse(raw: String): PluginOriginKey {
        val json = JSONObject(raw)
        require(json.optInt("formatVersion", 1) == 1) { "Unsupported repository key format" }
        val origin = normalizeGithubOrigin(json.requiredString("origin"))
        require(GITHUB_ORIGIN.matches(origin)) { "Key origin must be a GitHub repository" }
        val algorithm = json.requiredString("algorithm")
        require(algorithm == "SHA256withECDSA") { "Unsupported repository key algorithm" }
        val key = Base64.getDecoder().decode(json.requiredString("publicKeySpki"))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
        require(publicKey is ECPublicKey && publicKey.params.curve.field.fieldSize == 256) {
            "Repository keys must be ECDSA P-256 keys"
        }
        val fingerprint = sha256(key)
        require(fingerprint == json.requiredSha256("keySha256")) { "Repository key fingerprint mismatch" }
        return PluginOriginKey(origin, algorithm, key, fingerprint)
    }

    /** [parse], and the document must carry [root]'s certificate for its own origin. */
    fun parseCertified(raw: String, root: JSONObject): PluginOriginKey {
        val key = parse(raw)
        verifyOfficialIssuer(JSONObject(raw), root)
        return key
    }

    private fun verifyOfficialIssuer(repositoryKey: JSONObject, root: JSONObject) {
        require(root.getInt("formatVersion") == 1) { "Unsupported official root-key format" }
        val issuer = repositoryKey.optJSONObject("issuer")
            ?: throw IllegalArgumentException("Repository key lacks official certification")
        require(issuer.requiredString("id") == root.requiredString("id")) { "Unknown official key issuer" }
        require(issuer.requiredString("algorithm") == "SHA256withECDSA") { "Unsupported issuer algorithm" }
        val rootDer = Base64.getDecoder().decode(root.requiredString("publicKeySpki"))
        val rootFingerprint = sha256(rootDer)
        require(rootFingerprint == root.requiredSha256("keySha256") &&
            rootFingerprint == issuer.requiredSha256("keySha256")) { "Official root-key fingerprint mismatch" }
        val rootKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(rootDer))
        require(rootKey is ECPublicKey && rootKey.params.curve.field.fieldSize == 256) {
            "Official root key must be ECDSA P-256"
        }
        val signedIdentity = buildString {
            append(normalizeGithubOrigin(repositoryKey.requiredString("origin"))).append('\n')
            append(repositoryKey.requiredString("algorithm")).append('\n')
            append(repositoryKey.requiredString("publicKeySpki")).append('\n')
            append(repositoryKey.requiredSha256("keySha256")).append('\n')
        }.toByteArray(Charsets.UTF_8)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(rootKey)
        verifier.update(signedIdentity)
        require(verifier.verify(Base64.getDecoder().decode(issuer.requiredString("signature")))) {
            "Repository key is not certified by the official Enginehost root"
        }
    }
}

object PluginOriginKeyClient {
    /**
     * These repositories are forks of the engines they wrap, so the default
     * branch is usually the upstream project's own and carries none of the
     * plugin's files -- fetching a key document from it found nothing on ten of
     * our eleven origins, which made adding any of them as a custom origin
     * impossible. Look on the plugin branch first, default branch last.
     */
    fun fetch(origin: String): String =
        requireNotNull(GithubRepositoryFile.fetch(origin, "enginehost-public-key.json")) {
            "No enginehost-public-key.json published by this repository"
        }
}
