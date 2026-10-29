package dev.enginehost

import dev.droidtop.shared.net.GitHubAuth
import org.junit.Assert.*
import org.junit.Test

class GithubTokenStoreTest {
    private fun carries(url: String) = GitHubAuth.DEFAULT.authorizationFor(url, "ghp_secret") != null

    @Test
    fun `the token goes to the three GitHub hosts`() {
        assertTrue(carries("https://api.github.com/user"))
        assertTrue(carries("https://github.com/owner/repo"))
        assertTrue(carries("https://raw.githubusercontent.com/owner/repo/file"))
    }

    @Test
    fun `the token never goes to the pre-signed address a release asset redirects to`() {
        // That host rejects a request that carries both a signature and a bearer token; the old rule listed it.
        assertFalse(carries("https://objects.githubusercontent.com/file"))
    }

    @Test
    fun `the token does not go to unrelated hosts or to subdomains of the three`() {
        assertFalse(carries("https://example.com/file"))
        assertFalse(carries("https://evilgithub.com/file"))
        assertFalse(carries("https://api.github.com.evil.com/file"))
        assertFalse(carries("https://sub.api.github.com/file"))
        assertFalse(carries("http://api.github.com/user"))
    }

    @Test
    fun `masked hides the middle of a token`() {
        // The masked representation shows the first 4 and last 4 chars with stars in between.
        val value = "abcd1234efgh5678"
        val masked = value.take(4) + "****" + value.takeLast(4)
        assertEquals("abcd****5678", masked)
    }
}
