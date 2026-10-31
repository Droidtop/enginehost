package dev.enginehost

import org.junit.Assert.assertEquals
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
}
