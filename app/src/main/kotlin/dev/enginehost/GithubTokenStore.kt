package dev.enginehost

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Encrypted storage for a user-pasted GitHub token in Enginehost's own settings.
 * The token is never created or filled by the app; it is stored Keystore-backed,
 * masked in UI, removable by the user, and only attached to requests for plugin
 * sources (api.github.com, github.com, raw.githubusercontent.com,
 * objects.githubusercontent.com). No token = today's behaviour.
 */
class GithubTokenStore(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "enginehost_github_token"
        private const val PREFS_KEY = "github_token"

        /** Hosts to which the token is scoped. */
        val TOKEN_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
        )

        /** Whether a URL's host is in scope for the token. */
        fun scopedHost(url: String): Boolean {
            val host = try {
                java.net.URL(url).host ?: return false
            } catch (_: Exception) {
                return false
            }
            return host in TOKEN_HOSTS || TOKEN_HOSTS.any { host.endsWith("." + it) || host == it }
        }
    }

    private val prefs = context.getSharedPreferences("github-token-v1", Context.MODE_PRIVATE)

    /** Whether the user has set a token. */
    val hasToken: Boolean get() = prefs.contains(PREFS_KEY)

    /** The stored token value, or null. */
    fun get(): String? {
        val encrypted = prefs.getString(PREFS_KEY, null) ?: return null
        return decrypt(encrypted)
    }

    /** Store [token] encrypted with the Keystore-backed key. */
    fun set(token: String) {
        prefs.edit().putString(PREFS_KEY, encrypt(token)).apply()
    }

    /** Remove the stored token. */
    fun remove() {
        prefs.edit().remove(PREFS_KEY).apply()
    }

    /** A masked representation for display. */
    fun masked(token: String? = get()): String {
        val value = token ?: return "(not set)"
        if (value.length < 4) return "****"
        return value.take(4) + "****" + value.takeLast(4)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return if (ks.containsAlias(KEY_ALIAS)) {
            (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(spec)
            }.generateKey()
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        return android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)
    }

    private fun decrypt(base64: String): String? {
        return runCatching {
            val combined = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }
}
