package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccentPaletteTest {
    private val darkGround = 0xFF11151C.toInt()
    private val darkSurface = 0xFF181D26.toInt()
    private val lightGround = 0xFFEEF1F6.toInt()
    private val lightSurface = 0xFFFFFFFF.toInt()
    private val azure = 0xFF3BB5FF.toInt()

    private fun hue(colour: Int) = AccentPalette.toHsl(colour)[0]

    @Test
    fun `an accent that already reads on its ground is kept as it is`() {
        assertEquals(azure, AccentPalette.guard(azure, darkGround))
    }

    @Test
    fun `an accent too light for the light ground keeps its hue and darkens until it reads`() {
        val guarded = AccentPalette.guard(azure, lightGround)
        assertNotEquals(azure, guarded)
        assertTrue(AccentPalette.contrast(guarded, lightGround) >= AccentPalette.MIN_CONTRAST)
        assertTrue(abs(hue(guarded) - hue(azure)) < 3)
    }

    @Test
    fun `a dark pick on the dark ground lightens until it reads`() {
        val navy = 0xFF0A1A40.toInt()
        val guarded = AccentPalette.guard(navy, darkGround)
        assertTrue(AccentPalette.contrast(guarded, darkGround) >= AccentPalette.MIN_CONTRAST)
        assertTrue(AccentPalette.toHsl(guarded)[2] > AccentPalette.toHsl(navy)[2])
    }

    @Test
    fun `text on the accent fill is whichever ink reads better`() {
        // Near-black on azure, as the shipped dark palette has it; white on the darkened light accent.
        assertEquals(0xFF06121C.toInt(), AccentPalette.of(azure, darkGround, darkSurface, dark = true).onAccent)
        assertEquals(0xFFFFFFFF.toInt(), AccentPalette.of(azure, lightGround, lightSurface, dark = false).onAccent)
    }

    @Test
    fun `the container sits between the surface and the accent and its text reads on it`() {
        for ((ground, surface, dark) in listOf(Triple(darkGround, darkSurface, true), Triple(lightGround, lightSurface, false))) {
            val palette = AccentPalette.of(0xFFE0457B.toInt(), ground, surface, dark)
            assertTrue(AccentPalette.contrast(palette.onContainer, palette.container) >= AccentPalette.MIN_CONTRAST)
            assertTrue(AccentPalette.contrast(palette.onAccent, palette.accent) >= AccentPalette.MIN_CONTRAST)
        }
    }

    @Test
    fun `hsl round trips`() {
        val hsl = AccentPalette.toHsl(azure)
        val back = AccentPalette.fromHsl(hsl[0], hsl[1], hsl[2])
        for (shift in listOf(16, 8, 0)) {
            assertTrue(abs(((back shr shift) and 0xFF) - ((azure shr shift) and 0xFF)) <= 1)
        }
    }
}
