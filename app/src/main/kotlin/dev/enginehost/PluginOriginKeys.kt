package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
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

    fun matches(origin: String, fingerprint: String): Boolean =
        get(origin)?.fingerprint == fingerprint.uppercase()

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
                val key = parseKeyDocument(raw)
                key.origin to key
            }
        }

    private fun parseKeyDocument(raw: String): PluginOriginKey {
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
    fun fetch(origin: String): String {
        val normalized = normalizeGithubOrigin(origin)
        val match = requireNotNull(GITHUB_ORIGIN.matchEntire(normalized)) { "Not a GitHub repository" }
        val api = "https://api.github.com/repos/${match.groupValues[1]}/${match.groupValues[2]}/contents/enginehost-public-key.json"
        val connection = URL(api).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "enginehost/0.1")
        try {
            require(connection.responseCode in 200..299) { "GitHub returned HTTP ${connection.responseCode}" }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            require(json.getString("encoding") == "base64") { "Unexpected GitHub key encoding" }
            return Base64.getMimeDecoder().decode(json.getString("content")).toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
