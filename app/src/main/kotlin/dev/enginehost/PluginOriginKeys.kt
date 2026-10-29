package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** One repository signing key: the key a person pinned for a repository they added. */
data class PluginOriginKey(
    val origin: String,
    val algorithm: String,
    val publicKeySpki: ByteArray,
    val fingerprint: String,
)

/**
 * The keys a person accepted for repositories they added (by URL, or from the
 * third-party list), trusted on first use. A key changes only when the person
 * accepts a new one, as if adding the repository anew. A pinned key is the
 * anchor a v1 signature from that repository is verified to ([BundleTrust]).
 *
 * Enginehost's own repositories have no key here: their bundles are verified
 * to the Enginehost signing root ([EnginehostRoot]). The root-certified key
 * documents that used to stand for them, with the developer's key, belonged
 * to the legacy signature and went with its verifier (2026-10-29,
 * Droidtop/tracker#768).
 */
class PluginOriginKeyStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origin-keys-v1", Context.MODE_PRIVATE)

    /** The key pinned for a repository the person added themselves, if any. */
    fun custom(origin: String): PluginOriginKey? =
        preferences.getString(normalizeGithubOrigin(origin), null)?.let(OriginKeyDocuments::parse)

    /**
     * Pins [raw] for its origin. The caller refuses an official origin first
     * ([PluginOriginStore.add]); a key already pinned changes only through
     * removing the repository and adding it anew.
     */
    fun importCustom(raw: String, expectedOrigin: String? = null): PluginOriginKey {
        val key = OriginKeyDocuments.parse(raw)
        expectedOrigin?.let {
            require(key.origin == normalizeGithubOrigin(it)) { "Repository key declares a different origin" }
        }
        val existing = custom(key.origin)
        require(existing == null || existing.fingerprint == key.fingerprint) {
            "Repository signing key changed; a verified rotation is required"
        }
        preferences.edit().putString(key.origin, raw).apply()
        return key
    }

    fun removeCustom(origin: String) {
        preferences.edit().remove(normalizeGithubOrigin(origin)).apply()
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
