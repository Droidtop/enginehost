package dev.enginehost

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * One secret the person pasted (a GitHub token, a translation service's key), kept encrypted with
 * a key that never leaves the Android Keystore. Never created or filled by the app, masked when
 * shown, removable, and never in reports or backups. Each secret has its own Keystore alias and
 * preferences file, so removing one leaves the others alone.
 */
class KeystoreSecret(context: Context, private val alias: String, prefsName: String, private val key: String) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    val isSet: Boolean get() = prefs.contains(key)

    fun get(): String? = prefs.getString(key, null)?.let(::decrypt)

    fun set(value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    fun remove() {
        prefs.edit().remove(key).apply()
    }

    /** For display: the first and last four characters only. */
    fun masked(value: String? = get()): String {
        val shown = value ?: return "(not set)"
        if (shown.length < 4) return "****"
        return shown.take(4) + "****" + shown.takeLast(4)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) return (store.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val combined = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)
    }

    private fun decrypt(base64: String): String? = runCatching {
        val combined = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
