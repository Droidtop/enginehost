package dev.enginehost

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PadGlyphsTest {
    @Test
    fun padsAreSortedByWhatTheirButtonsPrint() {
        assertEquals(PadFamily.XBOX, PadFamilies.classify(0x045e, 0x028e, "Xbox 360 Controller"))
        assertEquals(PadFamily.PLAYSTATION, PadFamilies.classify(0x054c, 0x09cc, "Wireless Controller"))
        assertEquals(PadFamily.NINTENDO, PadFamilies.classify(0x057e, 0x2009, "Pro Controller"))
        // SDL's own rules around its table.
        assertEquals(PadFamily.XBOX, PadFamilies.classify(0x18d1, 0x9400, "Stadia Controller"))
        assertEquals(PadFamily.NINTENDO, PadFamilies.classify(0, 0, "Lic Pro Controller"))
        assertNull(PadFamilies.classify(0x057e, 0x2007, "NES Controller (R)"))
        assertNull(PadFamilies.classify(0x2022, 0x3001, "Retroid Pocket Controller"))
    }

    @Test
    fun theChoiceWinsThenThePadThenXbox() {
        assertEquals(PadLabels(PadFamily.NINTENDO, PadLabels.Source.CHOSEN), PadFamilies.labelsFor(PadFamily.NINTENDO, PadFamily.XBOX))
        assertEquals(PadLabels(PadFamily.PLAYSTATION, PadLabels.Source.DETECTED), PadFamilies.labelsFor(null, PadFamily.PLAYSTATION))
        assertEquals(PadLabels(PadFamily.XBOX, PadLabels.Source.ASSUMED), PadFamilies.labelsFor(null, null))
    }

    @Test
    fun keyCodesAreButtonPositions() {
        assertEquals(PadGlyph.Face(PadGlyph.Position.BOTTOM), PadGlyph.ofKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(PadGlyph.Face(PadGlyph.Position.RIGHT), PadGlyph.ofKey(KeyEvent.KEYCODE_BUTTON_B))
        // The bottom button reads A on Xbox and B on Nintendo; PlayStation draws a cross instead.
        assertEquals("A", PadGlyph.letter(PadFamily.XBOX, PadGlyph.Position.BOTTOM))
        assertEquals("B", PadGlyph.letter(PadFamily.NINTENDO, PadGlyph.Position.BOTTOM))
        assertNull(PadGlyph.letter(PadFamily.PLAYSTATION, PadGlyph.Position.BOTTOM))
    }

    @Test
    fun shouldersTriggersAndTheMiddleReadAsEachFamilyPrintsThem() {
        val lt = PadGlyph.ofKey(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals("LT", PadGlyph.tab(PadFamily.XBOX, lt))
        assertEquals("L2", PadGlyph.tab(PadFamily.PLAYSTATION, lt))
        assertEquals("ZL", PadGlyph.tab(PadFamily.NINTENDO, lt))
        assertEquals(lt, PadGlyph.ofAxis(MotionEvent.AXIS_LTRIGGER, 1))
        val start = PadGlyph.ofKey(KeyEvent.KEYCODE_BUTTON_START)
        assertEquals("Menu", PadGlyph.tab(PadFamily.XBOX, start))
        assertEquals("Options", PadGlyph.tab(PadFamily.PLAYSTATION, start))
        assertEquals("+", PadGlyph.tab(PadFamily.NINTENDO, start))
    }

    @Test
    fun sticksAndTheDpadCarryTheirDirection() {
        assertEquals(PadGlyph.Stick(left = true, arrow = PadGlyph.Arrow.LEFT), PadGlyph.ofAxis(MotionEvent.AXIS_X, -1))
        assertEquals(PadGlyph.Stick(left = false, arrow = PadGlyph.Arrow.VERTICAL), PadGlyph.ofAxis(MotionEvent.AXIS_RZ))
        assertEquals(PadGlyph.Dpad(PadGlyph.Arrow.RIGHT), PadGlyph.ofAxis(MotionEvent.AXIS_HAT_X, 1))
        assertEquals(PadGlyph.Dpad(PadGlyph.Arrow.UP), PadGlyph.ofKey(KeyEvent.KEYCODE_DPAD_UP))
    }
}
