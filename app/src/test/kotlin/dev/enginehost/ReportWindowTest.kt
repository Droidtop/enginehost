package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class ReportWindowTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute, 0) }.timeInMillis

    @Test
    fun aStampIsReadInTheCurrentYear() {
        val now = at(2026, 10, 23, 18, 0)
        assertEquals(at(2026, 10, 23, 17, 30), ProblemReport.eventTime("10-23 17:30:00 planned", now))
    }

    @Test
    fun aStampLaterThanNowIsLastYears() {
        val now = at(2027, 1, 2, 9, 0)
        assertEquals(at(2026, 12, 31, 23, 0), ProblemReport.eventTime("12-31 23:00:00 planned", now))
    }

    @Test
    fun aLineWithoutAStampIsNotPlaced() {
        assertNull(ProblemReport.eventTime("garbage", at(2026, 10, 23, 18, 0)))
    }

    @Test
    fun onlyTheSessionWindowIsKept() {
        val now = at(2026, 10, 23, 18, 0)
        val lines = listOf(
            "10-23 16:00:00 another game's plan",
            "10-23 17:00:00 this game's plan",
            "10-23 17:10:00 this game ended",
            "10-23 17:20:00 a later game's plan",
        )
        val kept = ProblemReport.inWindow(lines, at(2026, 10, 23, 16, 59), at(2026, 10, 23, 17, 20), now)
        assertEquals(listOf("10-23 17:00:00 this game's plan", "10-23 17:10:00 this game ended"), kept)
    }

    @Test
    fun withNoLaterLaunchTheWindowRunsToNow() {
        val now = at(2026, 10, 23, 18, 0)
        val lines = listOf("10-23 16:00:00 before", "10-23 17:30:00 during")
        assertEquals(listOf("10-23 17:30:00 during"), ProblemReport.inWindow(lines, at(2026, 10, 23, 17, 0), null, now))
    }
}
