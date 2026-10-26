package dev.enginehost

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the host menu trigger lives by: hold Select (400ms) opens the menu.
 * The press that completes the hold never reaches the game, nor does anything
 * else the person does with that button until they let go, and the one press
 * that did reach the game is released rather than left held down.
 */
class HostMenuComboTest {
    private val select = KeyEvent.KEYCODE_BUTTON_SELECT
    private val start = KeyEvent.KEYCODE_BUTTON_START
    private val a = KeyEvent.KEYCODE_BUTTON_A

    private fun combo() = HostMenuCombo()

    @Test
    fun `the default trigger is hold Select (400ms)`() {
        assertEquals(setOf(select), HostMenuHotkeyStore.DEFAULT_COMBO)
    }

    @Test
    fun `a button outside the trigger is never touched`() {
        val combo = combo()
        assertTrue(combo.down(a, 0) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.up(a, 0) is HostMenuCombo.Verdict.Pass)
    }

    @Test
    fun `the first press reaches the game and the hold does not`() {
        val combo = combo()
        assertTrue(combo.down(select, 0) is HostMenuCombo.Verdict.Pass)
        // Hold for 400ms
        val opened = combo.tick(400)
        assertTrue(opened is HostMenuCombo.Verdict.Open)
        assertEquals(emptySet<Int>(), (opened as HostMenuCombo.Verdict.Open).stuck)
    }

    @Test
    fun `every release of the trigger is consumed once the menu has opened`() {
        val combo = combo()
        combo.down(select, 0)
        combo.tick(400) // hold completes
        assertTrue(combo.up(select, 500) is HostMenuCombo.Verdict.Consume)
    }

    @Test
    fun `holding the trigger does not open the menu twice`() {
        val combo = combo()
        combo.down(select, 0)
        combo.tick(400) // hold completes
        // Android repeats ACTION_DOWN while a button is held.
        assertTrue(combo.down(select, 500) is HostMenuCombo.Verdict.Consume)
    }

    @Test
    fun `letting go before hold completes passes through to the game`() {
        val combo = combo()
        combo.down(select, 0)
        combo.up(select, 200) // released before 400ms
        assertTrue(combo.down(select, 300) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.up(select, 400) is HostMenuCombo.Verdict.Pass)
    }

    @Test
    fun `a member pressed and released on its own is ordinary input`() {
        val combo = combo()
        assertTrue(combo.down(select, 0) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.up(select, 200) is HostMenuCombo.Verdict.Pass)
    }

    @Test
    fun `a one button trigger opens on hold and leaves nothing stuck`() {
        val combo = HostMenuCombo(select, 400)
        combo.down(select, 0)
        val opened = combo.tick(400)
        assertTrue(opened is HostMenuCombo.Verdict.Open)
        assertEquals(emptySet<Int>(), (opened as HostMenuCombo.Verdict.Open).stuck)
    }

    @Test
    fun `back opens the host menu on release only`() {
        val back = KeyEvent.KEYCODE_BACK
        assertTrue(HostBack.opensMenu(back, KeyEvent.ACTION_UP, cancelled = false))
        assertFalse(HostBack.opensMenu(back, KeyEvent.ACTION_DOWN, cancelled = false))
        assertFalse(HostBack.opensMenu(back, KeyEvent.ACTION_UP, cancelled = true))
        assertFalse(HostBack.opensMenu(a, KeyEvent.ACTION_UP, cancelled = false))
    }

    @Test
    fun `a frame that is all black is blank and one lit pixel is not`() {
        val black = IntArray(256) { 0xFF000000.toInt() }
        assertTrue(isBlankFrame(black))
        val lit = black.copyOf().also { it[17] = 0xFF204080.toInt() }
        assertFalse(isBlankFrame(lit))
    }

    @Test
    fun `an empty trigger never fires`() {
        val combo = HostMenuCombo(KeyEvent.KEYCODE_BUTTON_SELECT, 400)
        // This test is now about the trigger key, not the combo
        assertTrue(combo.down(a, 0) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.up(a, 0) is HostMenuCombo.Verdict.Pass)
    }
}
