package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginTrustDefaultTest {
    @Test
    fun officialBundleWithNoDecisionIsApproved() {
        assertEquals(PluginTrustState.APPROVED, PluginTrustStore.effectiveState(null, official = true))
    }

    @Test
    fun aDenyOverridesOfficial() {
        assertEquals(PluginTrustState.DENIED, PluginTrustStore.effectiveState("denied", official = true))
    }

    @Test
    fun nonOfficialStaysPendingUntilApproved() {
        assertEquals(PluginTrustState.PENDING, PluginTrustStore.effectiveState(null, official = false))
        assertEquals(PluginTrustState.APPROVED, PluginTrustStore.effectiveState("approved", official = false))
        assertEquals(PluginTrustState.DENIED, PluginTrustStore.effectiveState("denied", official = false))
    }
}
