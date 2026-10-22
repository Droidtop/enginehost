package dev.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a stopped main thread is asked about (owner, 2026-10-22: 15 seconds). */
class RuntimeWatchdogTest {
    private val beat = 100_000L

    @Test
    fun `a stall is asked about only past the hang time`() {
        assertFalse(RuntimeWatchdog.stalled(beat, beat + RuntimeWatchdog.HANG_MS - 1, -1, 0))
        assertTrue(RuntimeWatchdog.stalled(beat, beat + RuntimeWatchdog.HANG_MS, -1, 0))
    }

    @Test
    fun `the same stall is asked about again only after a while`() {
        val askedAt = beat + RuntimeWatchdog.HANG_MS
        assertFalse(RuntimeWatchdog.stalled(beat, askedAt + RuntimeWatchdog.ASK_AGAIN_MS - 1, beat, askedAt))
        assertTrue(RuntimeWatchdog.stalled(beat, askedAt + RuntimeWatchdog.ASK_AGAIN_MS, beat, askedAt))
    }

    @Test
    fun `a new stall after recovering is asked about at once`() {
        val later = beat + 60_000
        assertTrue(RuntimeWatchdog.stalled(later, later + RuntimeWatchdog.HANG_MS, beat, beat + RuntimeWatchdog.HANG_MS))
    }
}
