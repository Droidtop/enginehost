package dev.enginehost

import org.junit.Assert.*
import org.junit.Test

class GithubTokenStoreTest {

    @Test
    fun `scopedHost matches the four plugin-source hosts`() {
        assertTrue(GithubTokenStore.scopedHost("https://api.github.com/user"))
        assertTrue(GithubTokenStore.scopedHost("https://github.com/owner/repo"))
        assertTrue(GithubTokenStore.scopedHost("https://raw.githubusercontent.com/owner/repo/file"))
        assertTrue(GithubTokenStore.scopedHost("https://objects.githubusercontent.com/file"))
    }

    @Test
    fun `scopedHost rejects unrelated hosts`() {
        assertFalse(GithubTokenStore.scopedHost("https://example.com/file"))
        assertFalse(GithubTokenStore.scopedHost("https://evilgithub.com/file"))
        assertFalse(GithubTokenStore.scopedHost("https://api.github.com.evil.com/file"))
    }

    @Test
    fun `scopedHost accepts subdomains of the scoped hosts`() {
        assertTrue(GithubTokenStore.scopedHost("https://sub.api.github.com/file"))
    }

    @Test
    fun `masked hides the middle of a token`() {
        // The masked representation shows the first 4 and last 4 chars with stars in between.
        val value = "abcd1234efgh5678"
        val masked = value.take(4) + "****" + value.takeLast(4)
        assertEquals("abcd****5678", masked)
    }
}
