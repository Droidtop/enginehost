package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust-carry-over rule (owner, 2026-09-27): approving a plugin approves
 * its whole line -- future builds from the same origin signed by the same
 * key need no new prompt, while a different key or a different origin still
 * asks. Exercised through [PluginTrustDecisions]'s pure map interface: this
 * app has no Robolectric, so the SharedPreferences half of the store cannot
 * be unit tested, which is why the rule lives outside it.
 */
class PluginTrustCarryOverTest {
    private val origin = "https://github.com/droidtop/enginehost-renpy-plugin"
    private val key = "AB".repeat(32)
    private val otherKey = "CD".repeat(32)

    private fun build(
        bundleId: String = "dev.enginehost.renpy.8_2.v1",
        origin: String = this.origin,
        signer: Set<String> = setOf(key),
        archiveSha256: String = "EF".repeat(32),
        pluginVersion: String = "1",
    ): InstalledPlugin = InstalledPlugin(
        PluginInfo("renpy", Version.parse(pluginVersion), emptyList()),
        bundleId,
        "dev.enginehost.renpy.Plugin",
        origin,
        signer,
        archiveSha256 = archiveSha256,
    )

    /** The decisions a store that approved [approved] holds afterwards. */
    private fun approvedLine(approved: InstalledPlugin): Map<String, String> =
        mapOf(PluginTrustDecisions.decisionKey(approved.bundleId, approved.origin, approved.signerIdentity) to "approved")

    @Test
    fun `an approval carries to a newer build of the same origin signed by the same key`() {
        val decisions = approvedLine(build(pluginVersion = "1"))
        val update = build(pluginVersion = "2", archiveSha256 = "11".repeat(32))
        assertEquals(PluginTrustState.APPROVED, PluginTrustDecisions.state(decisions, update))
    }

    @Test
    fun `the same archive replaced by nothing still counts as approved for its own build`() {
        val decisions = approvedLine(build())
        assertEquals(PluginTrustState.APPROVED, PluginTrustDecisions.state(decisions, build()))
    }

    @Test
    fun `an update signed by a different key prompts and says the key changed`() {
        val decisions = approvedLine(build(pluginVersion = "1"))
        val update = build(pluginVersion = "2", signer = setOf(otherKey))
        assertEquals(PluginTrustState.PENDING, PluginTrustDecisions.state(decisions, update))
        assertEquals(PluginTrustDecisions.Prior.DIFFERENT_KEY, PluginTrustDecisions.prior(decisions, update))
    }

    @Test
    fun `an update from a different origin prompts and says the origin changed`() {
        val decisions = approvedLine(build(pluginVersion = "1"))
        val update = build(
            pluginVersion = "2",
            origin = "https://github.com/somebody-else/enginehost-renpy-plugin",
        )
        assertEquals(PluginTrustState.PENDING, PluginTrustDecisions.state(decisions, update))
        assertEquals(PluginTrustDecisions.Prior.DIFFERENT_ORIGIN, PluginTrustDecisions.prior(decisions, update))
    }

    @Test
    fun `a bundle nobody decided on yet prompts with nothing to compare against`() {
        val update = build(pluginVersion = "2")
        assertEquals(PluginTrustState.PENDING, PluginTrustDecisions.state(emptyMap(), update))
        assertEquals(PluginTrustDecisions.Prior.NONE, PluginTrustDecisions.prior(emptyMap(), update))
    }

    @Test
    fun `a bundle with several fingerprints carries only while the whole verified set matches`() {
        val dualKey = setOf(key, otherKey)
        val decisions = approvedLine(build(signer = dualKey))
        assertEquals(
            PluginTrustState.APPROVED,
            PluginTrustDecisions.state(decisions, build(pluginVersion = "2", signer = dualKey)),
        )
        // A build signed by only one of the two keys is a different signer
        // identity: a dropped key is as much a change as a replaced one.
        assertEquals(
            PluginTrustState.PENDING,
            PluginTrustDecisions.state(decisions, build(pluginVersion = "2")),
        )
    }

    @Test
    fun `denial carries the same way approval does`() {
        val denied = mapOf(PluginTrustDecisions.decisionKey(build().bundleId, origin, key) to "denied")
        assertEquals(PluginTrustState.DENIED, PluginTrustDecisions.state(denied, build(pluginVersion = "2")))
    }

    @Test
    fun `a build with no verified signer is never trusted`() {
        assertEquals(
            PluginTrustState.PENDING,
            PluginTrustDecisions.state(approvedLine(build()), build(signer = emptySet())),
        )
    }

    @Test
    fun `a decision recorded for another bundle id never carries, whatever it signed`() {
        val decisions = approvedLine(build())
        val otherBundle = build(bundleId = "dev.enginehost.renpy.8_3.v1", pluginVersion = "2")
        assertEquals(PluginTrustState.PENDING, PluginTrustDecisions.state(decisions, otherBundle))
        assertEquals(PluginTrustDecisions.Prior.NONE, PluginTrustDecisions.prior(decisions, otherBundle))
    }

    // -- Decisions recorded before carry-over existed --

    @Test
    fun `a legacy digest-bound approval carries onto the line once`() {
        val v1 = build()
        val legacy = mapOf(
            PluginTrustDecisions.legacyDecisionKey(v1.bundleId, v1.archiveSha256, v1.signerIdentity) to "approved",
        )
        val carried = PluginTrustDecisions.migrated(legacy, v1)
        assertNull(carried[PluginTrustDecisions.legacyDecisionKey(v1.bundleId, v1.archiveSha256, v1.signerIdentity)])
        assertEquals(
            "approved",
            carried[PluginTrustDecisions.decisionKey(v1.bundleId, v1.origin, v1.signerIdentity)],
        )
        // And the line it carried onto is what the next build inherits.
        assertEquals(PluginTrustState.APPROVED, PluginTrustDecisions.state(carried, build(pluginVersion = "2")))
    }

    @Test
    fun `a legacy approval of an older build covers the installed newer one`() {
        // A device that approved build 1 under the old rule and already
        // updated to build 2 before the rule changed: the approval must
        // cover build 2, or the change would strip an approval it was
        // meant to widen.
        val older = build(pluginVersion = "1", archiveSha256 = "EF".repeat(32))
        val installed = build(pluginVersion = "2", archiveSha256 = "12".repeat(32))
        val legacy = mapOf(
            PluginTrustDecisions.legacyDecisionKey(older.bundleId, older.archiveSha256, older.signerIdentity) to "approved",
        )
        val carried = PluginTrustDecisions.migrated(legacy, installed)
        assertEquals(PluginTrustState.APPROVED, PluginTrustDecisions.state(carried, installed))
    }

    @Test
    fun `the line's own decision wins over the legacy record being carried`() {
        val plugin = build()
        val decisions = mapOf(
            PluginTrustDecisions.legacyDecisionKey(plugin.bundleId, plugin.archiveSha256, plugin.signerIdentity) to "approved",
            PluginTrustDecisions.decisionKey(plugin.bundleId, plugin.origin, plugin.signerIdentity) to "denied",
        )
        assertEquals(PluginTrustState.DENIED, PluginTrustDecisions.state(PluginTrustDecisions.migrated(decisions, plugin), plugin))
    }

    @Test
    fun `a legacy decision signed by a different key does not carry and warns of the key change`() {
        val legacy = mapOf(
            PluginTrustDecisions.legacyDecisionKey(build().bundleId, build().archiveSha256, otherKey) to "approved",
        )
        val plugin = build(pluginVersion = "2")
        val carried = PluginTrustDecisions.migrated(legacy, plugin)
        assertTrue(carried.isEmpty())
        assertEquals(PluginTrustState.PENDING, PluginTrustDecisions.state(carried, plugin))
        assertEquals(PluginTrustDecisions.Prior.DIFFERENT_KEY, PluginTrustDecisions.prior(carried, plugin))
    }

    @Test
    fun `a line decision for another origin is not mistaken for a legacy record`() {
        val plugin = build()
        val otherOrigin = "https://github.com/somebody-else/enginehost-renpy-plugin"
        val decisions = mapOf(
            PluginTrustDecisions.decisionKey(plugin.bundleId, otherOrigin, plugin.signerIdentity) to "denied",
        )
        val carried = PluginTrustDecisions.migrated(decisions, plugin)
        // Unchanged: nothing was carried, the other origin's line stays.
        assertEquals(decisions, carried)
        assertEquals(PluginTrustState.PENDING, PluginTrustDecisions.state(carried, plugin))
        assertEquals(PluginTrustDecisions.Prior.DIFFERENT_ORIGIN, PluginTrustDecisions.prior(carried, plugin))
    }
}
