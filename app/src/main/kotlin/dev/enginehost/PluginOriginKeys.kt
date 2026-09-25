package dev.enginehost

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * One repository signing key. [serial] is the generation the official root
 * certified it as (0 for an origin's first key, and for every key that is not
 * official); a replacement key for an official origin carries a higher one.
 */
data class PluginOriginKey(
    val origin: String,
    val algorithm: String,
    val publicKeySpki: ByteArray,
    val fingerprint: String,
    val serial: Int = 0,
)

/**
 * Whether an official key offered for an origin (by the plugins index)
 * becomes that origin's key. Only the root's certificate and its serial
 * decide: a new origin is added, the key already held changes nothing, a
 * higher serial is a rotation, and anything else -- the same serial with a
 * different key, or a lower one, which is what replaying an old or leaked
 * key looks like -- is refused (docs/security/2026-09-25-third-party-catalog.md,
 * T7, T10).
 */
object OfficialKeyRotation {
    enum class Decision { NEW_ORIGIN, UNCHANGED, ROTATE, REFUSE }

    fun decide(current: PluginOriginKey?, offered: PluginOriginKey): Decision = when {
        current == null -> Decision.NEW_ORIGIN
        current.fingerprint == offered.fingerprint -> Decision.UNCHANGED
        offered.serial > current.serial -> Decision.ROTATE
        else -> Decision.REFUSE
    }
}

/**
 * Pinned repository keys, in kinds that never mix:
 *
 * - **official**: certified by the official root for exactly one origin,
 *   verified here. Compiled into this APK, or learned later from the plugins
 *   index -- a new official repository, or a new key for one, reaches devices
 *   without an app release, and nothing but the offline root can make one.
 *   An origin's official key is the one with the highest certified serial;
 *   the keys it replaced are kept as **superseded**, so plugins installed and
 *   approved under them keep running (and stay Official) while everything new
 *   must carry the current key.
 * - **custom**: a key a person accepted for a repository they added (by URL,
 *   or from the third-party list), trusted on first use. It changes only when
 *   the person accepts a new one, as if adding the repository anew.
 *
 * Official beats custom: a certified key replaces a custom pin for the same
 * origin, since the root's certificate is a stronger statement than first use
 * (docs/security/2026-09-25-third-party-catalog.md, T9).
 */
class PluginOriginKeyStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origin-keys-v1", Context.MODE_PRIVATE)
    private val certifiedPreferences =
        context.getSharedPreferences("plugin-origin-keys-certified-v1", Context.MODE_PRIVATE)
    private val supersededPreferences =
        context.getSharedPreferences("plugin-origin-keys-superseded-v1", Context.MODE_PRIVATE)

    fun get(origin: String): PluginOriginKey? {
        val normalized = normalizeGithubOrigin(origin)
        return official(normalized) ?: preferences.getString(normalized, null)?.let(OriginKeyDocuments::parse)
    }

    /** The key pinned for a repository the person added themselves, if any. */
    fun custom(origin: String): PluginOriginKey? =
        preferences.getString(normalizeGithubOrigin(origin), null)?.let(OriginKeyDocuments::parse)

    fun importCustom(raw: String, expectedOrigin: String? = null): PluginOriginKey {
        val key = OriginKeyDocuments.parse(raw)
        expectedOrigin?.let {
            require(key.origin == normalizeGithubOrigin(it)) { "Repository key declares a different origin" }
        }
        require(official(key.origin) == null) { "Official repository keys cannot be replaced" }
        val existing = get(key.origin)
        require(existing == null || existing.fingerprint == key.fingerprint) {
            "Repository signing key changed; a verified rotation is required"
        }
        preferences.edit().putString(key.origin, raw).apply()
        return key
    }

    /**
     * Takes an official key from a key document carrying the official root's
     * certificate, as [OfficialKeyRotation] decides. Answers the key when it is
     * now the origin's key (new, unchanged or rotated to), or null when the
     * certificate does not verify or the key is refused. On a rotation the key
     * it replaces is kept as superseded. A custom pin for the same origin is
     * dropped: the certificate settles which key is the origin's.
     */
    fun importCertified(raw: String): PluginOriginKey? {
        val key = runCatching { parseCertified(raw) }.getOrNull() ?: return null
        val current = official(key.origin)
        when (OfficialKeyRotation.decide(current, key)) {
            OfficialKeyRotation.Decision.REFUSE -> return null
            OfficialKeyRotation.Decision.UNCHANGED -> Unit
            OfficialKeyRotation.Decision.NEW_ORIGIN -> storeCertified(key.origin, raw)
            OfficialKeyRotation.Decision.ROTATE -> {
                officialDocument(key.origin)?.let { previous -> supersede(key.origin, previous) }
                storeCertified(key.origin, raw)
            }
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
     *
     * [allowSuperseded] is for bundles already installed: an official key the
     * root has since replaced still vouches for what was installed and
     * approved under it. Nothing new is accepted under a superseded key.
     */
    fun matches(
        origin: String,
        fingerprint: String,
        allowDeveloper: Boolean = true,
        allowSuperseded: Boolean = false,
    ): Boolean {
        val normalized = fingerprint.uppercase()
        if (get(origin)?.fingerprint == normalized) return true
        if (allowSuperseded && superseded(origin).any { it.fingerprint == normalized }) return true
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

    /**
     * Whether [fingerprint] is a root-certified key of [origin]: its current
     * official key, or one a rotation superseded (a bundle installed under it
     * stays Official).
     */
    fun isOfficial(origin: String, fingerprint: String): Boolean {
        val expected = fingerprint.uppercase()
        return official(origin)?.fingerprint == expected || superseded(origin).any { it.fingerprint == expected }
    }

    fun removeCustom(origin: String) {
        val normalized = normalizeGithubOrigin(origin)
        if (official(normalized) == null) preferences.edit().remove(normalized).apply()
    }

    /** The origin's official key: the highest certified serial, compiled in or learned. */
    private fun official(origin: String): PluginOriginKey? {
        val normalized = normalizeGithubOrigin(origin)
        return listOfNotNull(builtIns()[normalized]?.first, certified()[normalized]?.first).maxByOrNull { it.serial }
    }

    private fun officialDocument(origin: String): String? {
        val current = official(origin) ?: return null
        return listOfNotNull(builtIns()[origin], certified()[origin])
            .firstOrNull { it.first.fingerprint == current.fingerprint && it.first.serial == current.serial }?.second
    }

    private fun superseded(origin: String): List<PluginOriginKey> {
        val raw = supersededPreferences.getString(normalizeGithubOrigin(origin), null) ?: return emptyList()
        val documents = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until documents.length()).mapNotNull { index ->
            runCatching { parseCertified(documents.getString(index)) }.getOrNull()
        }
    }

    private fun supersede(origin: String, document: String) {
        val existing = supersededPreferences.getString(origin, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: JSONArray()
        existing.put(document)
        supersededPreferences.edit().putString(origin, existing.toString()).apply()
    }

    private fun storeCertified(origin: String, raw: String) {
        certifiedPreferences.edit().putString(origin, raw).apply()
        synchronized(Companion) { certifiedKeys = null }
    }

    private fun builtIns(): Map<String, Pair<PluginOriginKey, String>> =
        builtInKeys ?: readBuiltIns().also { builtInKeys = it }

    private fun readBuiltIns(): Map<String, Pair<PluginOriginKey, String>> =
        context.resources.openRawResource(R.raw.default_plugin_keys)
            .bufferedReader().use { reader ->
                val root = JSONObject(reader.readText())
                require(root.getInt("formatVersion") == 1)
                val keys = root.getJSONArray("keys")
                (0 until keys.length()).associate { index ->
                    val raw = keys.getJSONObject(index).toString()
                    val key = parseCertified(raw)
                    key.origin to (key to raw)
                }
            }

    /**
     * Learned official keys, each certificate verified again when the process
     * first reads them: what is stored is the document, and the certificate is
     * what makes it official, not the fact that it was stored.
     */
    private fun certified(): Map<String, Pair<PluginOriginKey, String>> = certifiedKeys ?: synchronized(Companion) {
        certifiedKeys ?: certifiedPreferences.all.values.filterIsInstance<String>()
            .mapNotNull { raw -> runCatching { parseCertified(raw) to raw }.getOrNull() }
            .associateBy { it.first.origin }
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
        @Volatile private var builtInKeys: Map<String, Pair<PluginOriginKey, String>>? = null
        @Volatile private var certifiedKeys: Map<String, Pair<PluginOriginKey, String>>? = null
        private var developerKey: PluginOriginKey? = null
        private var developerKeyRead = false
    }
}

/** Repository key documents (`enginehost-public-key.json`), free of Android so they can be tested. */
object OriginKeyDocuments {
    /** What a serial certificate's signed identity starts with (scripts/certify-repository-key.py). */
    private const val V2_PREFIX = "enginehost-origin-key-v2"

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

    /**
     * [parse], and the document must carry [root]'s certificate for its own
     * origin. The key's serial is the certificate's: 0 for the original
     * format, the signed `serial` for a replacement key.
     */
    fun parseCertified(raw: String, root: JSONObject): PluginOriginKey {
        val key = parse(raw)
        val serial = verifyOfficialIssuer(JSONObject(raw), root)
        return key.copy(serial = serial)
    }

    /** The identity the root signs; serial 0 is the original format, byte for byte. */
    internal fun signedIdentity(origin: String, algorithm: String, spki: String, sha256: String, serial: Int): ByteArray =
        buildString {
            if (serial > 0) append(V2_PREFIX).append('\n')
            append(normalizeGithubOrigin(origin)).append('\n')
            append(algorithm).append('\n')
            append(spki).append('\n')
            append(sha256).append('\n')
            if (serial > 0) append(serial).append('\n')
        }.toByteArray(Charsets.UTF_8)

    private fun verifyOfficialIssuer(repositoryKey: JSONObject, root: JSONObject): Int {
        require(root.getInt("formatVersion") == 1) { "Unsupported official root-key format" }
        val issuer = repositoryKey.optJSONObject("issuer")
            ?: throw IllegalArgumentException("Repository key lacks official certification")
        require(issuer.requiredString("id") == root.requiredString("id")) { "Unknown official key issuer" }
        require(issuer.requiredString("algorithm") == "SHA256withECDSA") { "Unsupported issuer algorithm" }
        val serial = issuer.optInt("serial", 0)
        require(serial >= 0) { "Certificate serial must not be negative" }
        val rootDer = Base64.getDecoder().decode(root.requiredString("publicKeySpki"))
        val rootFingerprint = sha256(rootDer)
        require(rootFingerprint == root.requiredSha256("keySha256") &&
            rootFingerprint == issuer.requiredSha256("keySha256")) { "Official root-key fingerprint mismatch" }
        val rootKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(rootDer))
        require(rootKey is ECPublicKey && rootKey.params.curve.field.fieldSize == 256) {
            "Official root key must be ECDSA P-256"
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(rootKey)
        verifier.update(
            signedIdentity(
                repositoryKey.requiredString("origin"),
                repositoryKey.requiredString("algorithm"),
                repositoryKey.requiredString("publicKeySpki"),
                repositoryKey.requiredSha256("keySha256"),
                serial,
            ),
        )
        require(verifier.verify(Base64.getDecoder().decode(issuer.requiredString("signature")))) {
            "Repository key is not certified by the official Enginehost root"
        }
        return serial
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
