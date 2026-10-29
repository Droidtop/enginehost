package dev.enginehost

import org.junit.Assert.assertThrows
import org.junit.Test

/** Which addresses the one HTTP helper asks at all (Droidtop/tracker#768); no network is used. */
class HttpTest {
    private fun check(url: String, allowPlainHttp: Boolean = false, key: Boolean = false) =
        Http.checkScheme(
            url,
            Http.Request(url, headers = if (key) mapOf("Authorization" to "Bearer k") else emptyMap(), allowPlainHttp = allowPlainHttp),
        )

    @Test
    fun `https is always asked`() {
        check("https://example.invalid/a", key = true)
    }

    @Test
    fun `plain http is refused unless the caller allows it`() {
        assertThrows(Http.Failure::class.java) { check("http://example.invalid/a") }
        check("http://192.168.1.20:11434/v1/chat/completions", allowPlainHttp = true)
    }

    @Test
    fun `a key is never sent over plain http, even where http is allowed`() {
        assertThrows(Http.Failure::class.java) { check("http://192.168.1.20:11434/v1", allowPlainHttp = true, key = true) }
    }

    @Test
    fun `other schemes are refused`() {
        assertThrows(Http.Failure::class.java) { check("ftp://example.invalid/a", allowPlainHttp = true) }
        assertThrows(Http.Failure::class.java) { check("file:///etc/hosts", allowPlainHttp = true) }
    }
}
