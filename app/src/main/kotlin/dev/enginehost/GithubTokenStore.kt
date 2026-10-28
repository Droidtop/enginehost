package dev.enginehost

import android.content.Context

/**
 * Encrypted storage for a user-pasted GitHub token in Enginehost's own settings.
 * The token is never created or filled by the app; it is stored Keystore-backed,
 * masked in UI, removable by the user, and only attached to requests for plugin
 * sources (api.github.com, github.com, raw.githubusercontent.com,
 * objects.githubusercontent.com). No token = today's behaviour.
 */
class GithubTokenStore(context: Context) {

    companion object {
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

    /** Kept where the first release put it, so a token saved before this still reads. */
    private val secret = KeystoreSecret(context, KEY_ALIAS, "github-token-v1", PREFS_KEY)

    /** Whether the user has set a token. */
    val hasToken: Boolean get() = secret.isSet

    /** The stored token value, or null. */
    fun get(): String? = secret.get()

    /** Store [token] encrypted with the Keystore-backed key. */
    fun set(token: String) = secret.set(token)

    /** Remove the stored token. */
    fun remove() = secret.remove()

    /** A masked representation for display. */
    fun masked(token: String? = get()): String = secret.masked(token)
}
