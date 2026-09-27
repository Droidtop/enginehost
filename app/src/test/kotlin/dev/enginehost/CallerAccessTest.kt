package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classification (CallerDefaults) and resolution (EffectiveAccess)
 * logic behind the LAUNCH allow/ask/block filter (owner, 2026-09-27:
 * docs/security/2026-09-27-launch-entry-consent.md). Both are exercised
 * through their pure, PackageManager-free overloads, which is the whole
 * point of splitting them out: this app has no Robolectric, so anything
 * that took a real PackageManager could not be unit tested at all.
 */
class CallerAccessTest {
    private val neverResolvesHttp: (String) -> Boolean = { false }

    // -- CallerDefaults --

    @Test
    fun `a known browser package is a browser even if it does not resolve http itself`() {
        assertTrue(CallerDefaults.isBrowser("com.android.chrome", neverResolvesHttp))
    }

    @Test
    fun `an unknown package that resolves http is still a browser`() {
        assertTrue(CallerDefaults.isBrowser("com.example.customBrowser") { it == "com.example.customBrowser" })
    }

    @Test
    fun `an ordinary package is not a browser`() {
        assertFalse(CallerDefaults.isBrowser("com.example.notepad", neverResolvesHttp))
    }

    @Test
    fun `known remote-access and automation packages are recognised`() {
        assertTrue(CallerDefaults.isRemoteOrAutomationTool("com.termux"))
        assertTrue(CallerDefaults.isRemoteOrAutomationTool("net.dinglisch.android.taskerm"))
        assertFalse(CallerDefaults.isRemoteOrAutomationTool("com.example.notepad"))
    }

    @Test
    fun `blockReason names a browser`() {
        assertEquals(CallerDefaults.BlockReason.BROWSER, CallerDefaults.blockReason("com.android.chrome", neverResolvesHttp))
    }

    @Test
    fun `blockReason names a remote-access or automation tool`() {
        assertEquals(
            CallerDefaults.BlockReason.REMOTE_OR_AUTOMATION,
            CallerDefaults.blockReason("com.termux", neverResolvesHttp),
        )
    }

    @Test
    fun `blockReason is null for an ordinary package`() {
        assertNull(CallerDefaults.blockReason("com.example.notepad", neverResolvesHttp))
    }

    @Test
    fun `blockReason is never applied to the unrecognized-caller bucket`() {
        // Not a real package: even a resolver that says yes to everything must not tag it a browser.
        assertNull(CallerDefaults.blockReason(LaunchCaller.UNKNOWN_KEY) { true })
    }

    // -- EffectiveAccess --

    @Test
    fun `an explicit Allow wins even over a known browser package`() {
        val access = EffectiveAccess.forCaller("com.android.chrome", CallerDecision.ALLOW, neverResolvesHttp)
        assertEquals(EffectiveAccess.Allow, access)
    }

    @Test
    fun `an explicit Block wins over an otherwise-ordinary package`() {
        val access = EffectiveAccess.forCaller("com.example.notepad", CallerDecision.BLOCK, neverResolvesHttp)
        assertTrue(access is EffectiveAccess.Block)
    }

    @Test
    fun `no decision and a browser defaults to Block with the browser reason`() {
        val access = EffectiveAccess.forCaller("com.android.chrome", stored = null, resolvesHttpHandler = neverResolvesHttp)
        assertEquals(EffectiveAccess.Block(CallerDefaults.BlockReason.BROWSER), access)
    }

    @Test
    fun `no decision and a remote-automation tool defaults to Block with that reason`() {
        val access = EffectiveAccess.forCaller("com.termux", stored = null, resolvesHttpHandler = neverResolvesHttp)
        assertEquals(EffectiveAccess.Block(CallerDefaults.BlockReason.REMOTE_OR_AUTOMATION), access)
    }

    @Test
    fun `no decision and an ordinary package defaults to Ask`() {
        val access = EffectiveAccess.forCaller("com.example.notepad", stored = null, resolvesHttpHandler = neverResolvesHttp)
        assertEquals(EffectiveAccess.Ask, access)
    }

    @Test
    fun `no decision and the unrecognized-caller bucket defaults to Ask, never Block`() {
        val access = EffectiveAccess.forCaller(LaunchCaller.UNKNOWN_KEY, stored = null, resolvesHttpHandler = neverResolvesHttp)
        assertEquals(EffectiveAccess.Ask, access)
    }

    // -- LaunchCaller.storeKey --

    @Test
    fun `storeKey identifies droidtop, an app, and the unrecognized bucket distinctly`() {
        assertEquals(TrustedCallers.DROIDTOP_PACKAGE, LaunchCaller.Droidtop.storeKey)
        assertEquals(LaunchCaller.UNKNOWN_KEY, LaunchCaller.Unknown.storeKey)
        assertEquals("com.example.app", LaunchCaller.App("com.example.app").storeKey)
    }
}
