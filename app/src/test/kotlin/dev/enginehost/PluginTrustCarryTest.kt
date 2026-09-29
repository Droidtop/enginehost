package dev.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTrustCarryTest {
    private fun plugin(signer: String, origin: String = "https://github.com/a/b") = InstalledPlugin(
        info = PluginInfo("renpy", Version.parse("1.0.0-1"), emptyList()),
        bundleId = "a.b",
        entrypointClass = "X",
        origin = origin,
        signerFingerprints = setOf(signer),
    )

    @Test
    fun anApprovalCarriesToASameKeyUpdate() {
        assertTrue(PluginTrustStore.shouldCarry("approved", plugin("k1"), plugin("k1")))
    }

    @Test
    fun aDifferentKeyOrOriginStillAsks() {
        assertFalse(PluginTrustStore.shouldCarry("approved", plugin("k1"), plugin("k2")))
        assertFalse(PluginTrustStore.shouldCarry("approved", plugin("k1"), plugin("k1", "https://github.com/c/d")))
    }

    @Test
    fun aDenyOrNoDecisionDoesNotCarry() {
        assertFalse(PluginTrustStore.shouldCarry("denied", plugin("k1"), plugin("k1")))
        assertFalse(PluginTrustStore.shouldCarry(null, plugin("k1"), plugin("k1")))
    }
}
