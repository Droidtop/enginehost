package dev.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carry-over rule (PluginTrustStore.approvalCarries) behind "trust
 * carries to an update signed with the same key" (owner, 2026-09-27;
 * docs/ui-v2.md section 3, docs/plugin-catalog.md "Updates"): a
 * replacement build inherits the approved build's decision only when it
 * is provably the same line -- same bundle ID, same origin, same verified
 * signing key. The rule is split out of the Context-bound store so a
 * plain JVM test can exercise it the way PluginTrustDefaultTest exercises
 * effectiveState; whether the previous build was actually APPROVED, and
 * the storing of the carried decision, stay in the store.
 */
class PluginTrustStoreTest {
    private fun plugin(
        signer: String,
        origin: String = "https://github.com/droidtop/enginehost-renpy-plugin",
        bundleId: String = "dev.enginehost.renpy.8_2.v1",
    ): InstalledPlugin = InstalledPlugin(
        PluginInfo("renpy", Version.parse("1"), emptyList()),
        bundleId,
        "dev.enginehost.renpy.Plugin",
        origin,
        setOf(signer),
    )

    @Test
    fun `an update signed with the approved build's key carries its approval`() {
        val approved = plugin(signer = "AB".repeat(32))
        val replacement = plugin(signer = "AB".repeat(32))
        assertTrue(PluginTrustStore.approvalCarries(approved, replacement))
    }

    @Test
    fun `an update signed with a different key prompts for a fresh approval as today`() {
        val approved = plugin(signer = "AB".repeat(32))
        val replacement = plugin(signer = "CD".repeat(32))
        assertFalse(PluginTrustStore.approvalCarries(approved, replacement))
    }

    @Test
    fun `an update from a different origin carrying the same key prompts as today`() {
        val approved = plugin(signer = "AB".repeat(32))
        val replacement = plugin(
            signer = "AB".repeat(32),
            origin = "https://github.com/somebody-else/enginehost-renpy-plugin",
        )
        assertFalse(PluginTrustStore.approvalCarries(approved, replacement))
    }

    @Test
    fun `a different bundle id inherits nothing, even under the same key`() {
        val approved = plugin(signer = "AB".repeat(32))
        val otherLine = plugin(signer = "AB".repeat(32), bundleId = "dev.enginehost.renpy.8_3.v1")
        assertFalse(PluginTrustStore.approvalCarries(approved, otherLine))
    }

    @Test
    fun `an unsigned build inherits nothing and passes nothing on`() {
        val approved = plugin(signer = "AB".repeat(32))
        assertFalse(PluginTrustStore.approvalCarries(approved, plugin(signer = "")))
        assertFalse(PluginTrustStore.approvalCarries(plugin(signer = ""), approved))
    }
}
