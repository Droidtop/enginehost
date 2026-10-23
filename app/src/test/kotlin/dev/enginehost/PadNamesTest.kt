package dev.enginehost

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PadNamesTest {
    @Test
    fun buttonsReadAsPrinted() {
        assertEquals("A", PadNames.key(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("L3", PadNames.key(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals("D-pad ↑", PadNames.key(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun theShortcutIsAChordOfNames() {
        assertEquals(
            "Start + Select",
            PadNames.chord(setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START)),
        )
    }

    @Test
    fun axesNameTheStickAndDirection() {
        assertEquals("Left stick ←", PadNames.axis(MotionEvent.AXIS_X, -1))
        assertEquals("Left stick ↕", PadNames.axis(MotionEvent.AXIS_Y))
        assertEquals("Right stick ↓", PadNames.axis(MotionEvent.AXIS_RZ, 1))
        assertEquals("L2", PadNames.axis(MotionEvent.AXIS_LTRIGGER, 1))
        assertEquals("D-pad →", PadNames.axis(MotionEvent.AXIS_HAT_X, 1))
    }

    @Test
    fun otherNamesAreMadeReadable() {
        assertEquals("Page up", PadNames.readable("PAGE_UP"))
    }
}
