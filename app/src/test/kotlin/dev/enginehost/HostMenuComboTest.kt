package dev.enginehost

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the host menu shortcut lives by: the press that completes the
 * combination never reaches the game, nor does anything else the person
 * does with those buttons until they let go, and the one press that did
 * reach the game is released rather than left held down.
 */
class HostMenuComboTest {
    private val select = KeyEvent.KEYCODE_BUTTON_SELECT
    private val start = KeyEvent.KEYCODE_BUTTON_START
    private val a = KeyEvent.KEYCODE_BUTTON_A

    private fun combo() = HostMenuCombo(HostMenuHotkeyStore.DEFAULT_COMBO)

    @Test
    fun `the default combination is select and start`() {
        assertEquals(setOf(select, start), HostMenuHotkeyStore.DEFAULT_COMBO)
    }

    @Test
    fun `a button outside the combination is never touched`() {
        val combo = combo()
        assertTrue(combo.down(a) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.up(a) is HostMenuCombo.Verdict.Pass)
    }

    @Test
    fun `the first press reaches the game and the completing press does not`() {
        val combo = combo()
        assertTrue(combo.down(select) is HostMenuCombo.Verdict.Pass)
        val opened = combo.down(start)
        assertTrue(opened is HostMenuCombo.Verdict.Open)
        assertEquals(setOf(select), (opened as HostMenuCombo.Verdict.Open).stuck)
    }

    @Test
    fun `every release of a member is consumed once the menu has opened`() {
        val combo = combo()
        combo.down(select)
        combo.down(start)
        assertTrue(combo.up(start) is HostMenuCombo.Verdict.Consume)
        assertTrue(combo.up(select) is HostMenuCombo.Verdict.Consume)
    }

    @Test
    fun `holding the combination does not open the menu twice`() {
        val combo = combo()
        combo.down(select)
        combo.down(start)
        // Android repeats ACTION_DOWN while a button is held.
        assertTrue(combo.down(start) is HostMenuCombo.Verdict.Consume)
        assertTrue(combo.down(select) is HostMenuCombo.Verdict.Consume)
    }

    @Test
    fun `letting go re-arms the shortcut`() {
        val combo = combo()
        combo.down(select)
        combo.down(start)
        combo.up(start)
        combo.up(select)
        assertTrue(combo.down(start) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.down(select) is HostMenuCombo.Verdict.Open)
    }

    @Test
    fun `a member pressed and released on its own is ordinary input`() {
        val combo = combo()
        assertTrue(combo.down(select) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.up(select) is HostMenuCombo.Verdict.Pass)
    }

    @Test
    fun `a one button shortcut opens on its own press and leaves nothing stuck`() {
        val combo = HostMenuCombo(setOf(select))
        val opened = combo.down(select)
        assertTrue(opened is HostMenuCombo.Verdict.Open)
        assertEquals(emptySet<Int>(), (opened as HostMenuCombo.Verdict.Open).stuck)
    }

    @Test
    fun `an empty shortcut never fires`() {
        val combo = HostMenuCombo(emptySet())
        assertTrue(combo.down(select) is HostMenuCombo.Verdict.Pass)
        assertTrue(combo.up(select) is HostMenuCombo.Verdict.Pass)
    }
}
