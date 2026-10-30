package dev.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackScreenRuleTest {
    @Test
    fun anEngineThatReportsProgressForSixtySecondsIsNeverAsked() {
        // A fake engine: its loading picture lifted at once, a progress report every second for 60 s.
        for (second in 0..120) {
            val now = second * 1_000L
            val reportedAt = minOf(now, 60_000L)
            if (second <= 60) {
                assertFalse("asked at ${second}s", BlackScreenRule.shouldAsk(now, 0, reportedAt, ownPictureShown = false, firstLaunch = false))
            }
        }
    }

    @Test
    fun theWindowRunsFromTheLastReport() {
        val last = 60_000L
        assertFalse(BlackScreenRule.shouldAsk(last + 44_000, 0, last, ownPictureShown = false, firstLaunch = false))
        assertTrue(BlackScreenRule.shouldAsk(last + 45_000, 0, last, ownPictureShown = false, firstLaunch = false))
    }

    @Test
    fun theGamesOwnPictureIsNotABlackScreen() {
        assertFalse(BlackScreenRule.shouldAsk(10 * 60_000L, 0, 0, ownPictureShown = true, firstLaunch = false))
    }

    @Test
    fun aGameThatShowsAndReportsNothingIsAskedAfterTheWindow() {
        assertFalse(BlackScreenRule.shouldAsk(44_999, 0, 0, ownPictureShown = false, firstLaunch = false))
        assertTrue(BlackScreenRule.shouldAsk(45_000, 0, 0, ownPictureShown = false, firstLaunch = false))
    }

    @Test
    fun aCoreBuildsFirstLaunchHasLonger() {
        assertFalse(BlackScreenRule.shouldAsk(149_999, 0, 0, ownPictureShown = false, firstLaunch = true))
        assertTrue(BlackScreenRule.shouldAsk(150_000, 0, 0, ownPictureShown = false, firstLaunch = true))
    }

    @Test
    fun watchingStopsOnlyAfterALongSilence() {
        assertFalse(BlackScreenRule.givesUp(170_000, 0, 0, firstLaunch = false))
        assertTrue(BlackScreenRule.givesUp(180_001, 0, 0, firstLaunch = false))
        assertFalse(BlackScreenRule.givesUp(240_000, 0, 100_000, firstLaunch = false))
        assertFalse(BlackScreenRule.givesUp(280_000, 0, 0, firstLaunch = true))
    }
}
