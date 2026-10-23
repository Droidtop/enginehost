package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimumPressTest {
    private var clock = 0L
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val delivered = mutableListOf<String>()
    private val presses = MinimumPress<String>(minimumMs = 60, now = { clock }, later = { delay, run -> scheduled += (clock + delay) to run })

    private fun runDue() {
        val due = scheduled.filter { it.first <= clock }
        scheduled.removeAll(due)
        due.forEach { it.second() }
    }

    @Test
    fun aPressAsLongAsTheMinimumIsDeliveredAtOnce() {
        presses.down(1)
        clock = 80
        assertTrue(presses.up(1, "up") { delivered += it })
        assertEquals(listOf("up"), delivered)
    }

    @Test
    fun aShorterPressHasItsReleaseHeldUntilTheMinimum() {
        presses.down(1)
        clock = 5
        assertFalse(presses.up(1, "up") { delivered += it })
        assertEquals(emptyList<String>(), delivered)
        clock = 59; runDue()
        assertEquals(emptyList<String>(), delivered)
        clock = 60; runDue()
        assertEquals(listOf("up"), delivered)
    }

    @Test
    fun aNewPressDeliversTheHeldReleaseFirstAndOnlyOnce() {
        presses.down(1)
        clock = 5
        presses.up(1, "first up") { delivered += it }
        clock = 20
        presses.down(1)
        assertEquals(listOf("first up"), delivered)
        clock = 100; runDue()
        assertEquals(listOf("first up"), delivered)
    }

    @Test
    fun aReleaseWithNoPressSeenIsDeliveredAtOnce() {
        assertTrue(presses.up(2, "up") { delivered += it })
        assertEquals(listOf("up"), delivered)
    }

    @Test
    fun keysAreHeldIndependently() {
        presses.down(1); presses.down(2)
        clock = 10
        presses.up(1, "one") { delivered += it }
        clock = 70
        assertTrue(presses.up(2, "two") { delivered += it })
        runDue()
        assertEquals(listOf("two", "one"), delivered)
    }
}
