package dev.enginehost

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seed database and the numbering it is written in. Anything needing a
 * connected pad (axis indices, and therefore the axis half of a
 * translation) belongs on the device; what can be checked here is that the
 * shipped file parses, that its entries say what they are expected to say,
 * and that SDL's Android button numbering is the one this host inverts.
 */
class ControllerProfilesTest {
    private val database = File("src/main/assets/gamecontrollerdb-android.txt")

    private fun entries(): List<SdlMapping> =
        database.readLines().mapNotNull(SdlMapping::parse)

    @Test
    fun `the shipped database is present and every line parses`() {
        assertTrue("run from the app module: ${database.absolutePath}", database.isFile)
        val lines = database.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(lines.size, entries().size)
        assertTrue(entries().size > 200)
    }

    @Test
    fun `every shipped entry is an Android entry`() {
        assertTrue(entries().all { it.targets["platform"] == "Android" })
    }

    @Test
    fun `an entry parses into its targets`() {
        val entry = SdlMapping.parse(
            "0000,Example Pad,a:b1,b:b0,leftx:a0,dpup:h0.1,platform:Android,",
        )!!
        assertEquals("0000", entry.guid)
        assertEquals("Example Pad", entry.name)
        assertEquals("b1", entry.targets["a"])
        assertEquals("h0.1", entry.targets["dpup"])
    }

    @Test
    fun `comments and blank lines are not entries`() {
        assertNull(SdlMapping.parse("# a comment"))
        assertNull(SdlMapping.parse("   "))
    }

    @Test
    fun `SDL's Android button numbering is inverted as SDL defines it`() {
        // src/joystick/android/SDL_sysjoystick.c, keycode_to_SDL.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, SdlAndroidNumbering.keyCodeOf(0))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, SdlAndroidNumbering.keyCodeOf(1))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, SdlAndroidNumbering.keyCodeOf(2))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, SdlAndroidNumbering.keyCodeOf(3))
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, SdlAndroidNumbering.keyCodeOf(4))
        assertEquals(KeyEvent.KEYCODE_BUTTON_MODE, SdlAndroidNumbering.keyCodeOf(5))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, SdlAndroidNumbering.keyCodeOf(6))
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBL, SdlAndroidNumbering.keyCodeOf(7))
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBR, SdlAndroidNumbering.keyCodeOf(8))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L1, SdlAndroidNumbering.keyCodeOf(9))
        assertEquals(KeyEvent.KEYCODE_BUTTON_R1, SdlAndroidNumbering.keyCodeOf(10))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, SdlAndroidNumbering.keyCodeOf(11))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, SdlAndroidNumbering.keyCodeOf(14))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, SdlAndroidNumbering.keyCodeOf(15))
        assertEquals(KeyEvent.KEYCODE_BUTTON_R2, SdlAndroidNumbering.keyCodeOf(16))
        assertEquals(KeyEvent.KEYCODE_BUTTON_1, SdlAndroidNumbering.keyCodeOf(20))
        assertEquals(KeyEvent.KEYCODE_BUTTON_16, SdlAndroidNumbering.keyCodeOf(35))
        assertNull(SdlAndroidNumbering.keyCodeOf(19))
        assertNull(SdlAndroidNumbering.keyCodeOf(36))
    }

    @Test
    fun `the standard controls are a key or an axis, never both and never neither`() {
        StandardControl.entries.forEach { control ->
            assertTrue(control.name, (control.key == null) != (control.axis == null))
        }
    }

    @Test
    fun `every standard control has a distinct SDL name and a distinct identity control`() {
        val controls = StandardControl.entries
        assertEquals(controls.size, controls.map { it.sdl }.toSet().size)
        assertEquals(controls.size, controls.map { it.key to it.axis }.toSet().size)
    }

    @Test
    fun `the Retroid pad's own name is not in the shipped database`() {
        // The console's pad reports as "Retroid Pocket Controller". The
        // database has three entries called "Retroid Pocket" that disagree
        // with each other about which button is A, and no entry under the
        // name this pad actually reports. Nothing is invented for it: with
        // no name match it gets no seed, which is Android's own mapping,
        // which is what it had before any of this existed.
        assertTrue(entries().none { it.name.equals("Retroid Pocket Controller", ignoreCase = true) })
    }

    @Test
    fun `a profile with nothing to correct changes nothing`() {
        assertTrue(ControllerProfile.NONE.isEmpty())
        assertEquals(ControllerProfile.Source.NONE, ControllerProfile.NONE.source)
    }
}
