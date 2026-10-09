package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeClassTest {
    @Test
    fun aGridFitsAsManyColumnsAsTheWidthAllowsAndAlwaysOne() {
        // 320dp cards with a 16dp gap, in px at 1x.
        assertEquals(1, SizeClass.columns(360, 320, 16))
        assertEquals(1, SizeClass.columns(100, 320, 16))
        assertEquals(2, SizeClass.columns(700, 320, 16))
        assertEquals(3, SizeClass.columns(1000, 320, 16))
        assertEquals(1, SizeClass.columns(800, 0, 16))
    }

    @Test
    fun destinationsMoveToARailOnWideOrShortWindows() {
        assertFalse(SizeClass.usesRail(412, 892))
        assertTrue(SizeClass.usesRail(892, 412))
        assertTrue(SizeClass.usesRail(600, 1000))
        assertTrue(SizeClass.usesRail(500, 400))
    }
}
