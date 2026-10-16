package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Which failures reach a person in their own words (review, 2026-10-16). */
class ErrorTextTest {
    @Test
    fun `Enginehost's own checks are shown as written`() {
        val shown = ErrorText.classify(IllegalArgumentException("The selected folder is not writable"))
        assertTrue(shown is ErrorText.Shown.Own)
        assertEquals("The selected folder is not writable", (shown as ErrorText.Shown.Own).text)
        assertTrue(ErrorText.classify(IllegalStateException("Nothing to install")) is ErrorText.Shown.Own)
    }

    @Test
    fun `a network failure is one plain sentence`() {
        assertSame(ErrorText.Shown.Network, ErrorText.classify(java.net.UnknownHostException("github.com")))
        assertSame(ErrorText.Shown.Network, ErrorText.classify(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun `system and parser text is replaced by the caller's sentence`() {
        assertSame(ErrorText.Shown.Fallback, ErrorText.classify(IOException("open failed: EACCES (Permission denied)")))
        assertSame(ErrorText.Shown.Fallback, ErrorText.classify(NumberFormatException("For input string: \"x\"")))
        assertSame(ErrorText.Shown.Fallback, ErrorText.classify(IllegalArgumentException()))
        assertSame(ErrorText.Shown.Fallback, ErrorText.classify(org.json.JSONException("Unterminated object")))
    }
}
