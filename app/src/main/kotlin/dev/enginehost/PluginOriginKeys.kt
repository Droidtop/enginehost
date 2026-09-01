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

/** Pinned repository keys: bundled for official origins, TOFU on explicit custom-origin addition. */
class PluginOriginKeyStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origin-keys-v1", Context.MODE_PRIVATE)

    fun get(origin: String): PluginOriginKey? {
        val normalized = normalizeGithubOrigin(origin)
        return builtIns()[normalized] ?: preferences.getString(normalized, null)?.let(::parseKeyDocument)
    }

    fun importCustom(raw: String, expectedOrigin: String? = null): PluginOriginKey {
        val key = parseKeyDocument(raw)
        expectedOrigin?.let {
            require(key.origin == normalizeGithubOrigin(it)) { "Repository key declares a different origin" }
        }
        require(key.origin !in builtIns()) { "Built-in repository keys cannot be replaced" }
        val existing = get(key.origin)
        require(existing == null || existing.fingerprint == key.fingerprint) {
            "Repository signing key changed; a verified rotation is required"
        }
        preferences.edit().putString(key.origin, raw).apply()
        return key
    }

    fun matches(origin: String, fingerprint: String): Boolean {
        val normalized = fingerprint.uppercase()
        if (get(origin)?.fingerprint == normalized) return true
        // The primary developer's key is not origin-scoped: it signs locally
        // rebuilt bundles for any repository. Accepting it here is the whole
        // point of having it, and every surface that reports trust marks such a
        // bundle as a developer build so it cannot pass for an official one.
        return isDeveloperDebug(normalized)
    }

    /** The primary developer's own signing key, certified by the official root. */
    fun developerDebug(): PluginOriginKey? = runCatching {
        context.resources.openRawResource(R.raw.developer_debug_key)
            .bufferedReader().use { parseKeyDocument(it.readText(), requireOfficialIssuer = true) }
    }.getOrNull()

    fun isDeveloperDebug(fingerprint: String): Boolean =
        developerDebug()?.fingerprint == fingerprint.uppercase()

    fun isBuiltIn(origin: String, fingerprint: String): Boolean =
        builtIns()[normalizeGithubOrigin(origin)]?.fingerprint == fingerprint.uppercase()

    fun removeCustom(origin: String) {
        val normalized = normalizeGithubOrigin(origin)
        if (normalized !in builtIns()) preferences.edit().remove(normalized).apply()
    }

    private fun builtIns(): Map<String, PluginOriginKey> = context.resources.openRawResource(R.raw.default_plugin_keys)
        .bufferedReader().use { reader ->
            val root = JSONObject(reader.readText())
            require(root.getInt("formatVersion") == 1)
            val keys = root.getJSONArray("keys")
            (0 until keys.length()).associate { index ->
                val raw = keys.getJSONObject(index).toString()
                val key = parseKeyDocument(raw, requireOfficialIssuer = true)
                key.origin to key
            }
        }

    private fun parseKeyDocument(raw: String, requireOfficialIssuer: Boolean = false): PluginOriginKey {
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
        if (requireOfficialIssuer) verifyOfficialIssuer(json)
        return PluginOriginKey(origin, algorithm, key, fingerprint)
    }

    private fun verifyOfficialIssuer(repositoryKey: JSONObject) {
        val root = context.resources.openRawResource(R.raw.official_plugin_root_key)
            .bufferedReader().use { JSONObject(it.readText()) }
        require(root.getInt("formatVersion") == 1) { "Unsupported official root-key format" }
        val issuer = repositoryKey.optJSONObject("issuer")
            ?: throw IllegalArgumentException("Built-in repository key lacks official certification")
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
