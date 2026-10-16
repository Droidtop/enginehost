package dev.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pending launch resumes only while fresh (review, 2026-10-16). */
class PendingPluginLaunchTest {
    private val savedAt = 1_000_000_000L
    private val pending = PendingPluginLaunch("/games/a", null, savedAt = savedAt)

    @Test
    fun `fresh up to the limit, stale after it`() {
        assertTrue(pending.isFresh(savedAt))
        assertTrue(pending.isFresh(savedAt + PendingPluginLaunch.MAX_AGE_MS))
        assertFalse(pending.isFresh(savedAt + PendingPluginLaunch.MAX_AGE_MS + 1))
    }

    @Test
    fun `a clock set back, or a launch saved before the timestamp existed, is stale`() {
        assertFalse(pending.isFresh(savedAt - 1))
        assertFalse(PendingPluginLaunch("/games/a", null).isFresh(savedAt))
    }
}
