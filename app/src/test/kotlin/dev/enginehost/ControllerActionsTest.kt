package dev.enginehost

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-engine action sets, checked for the properties a person would
 * notice on the controller screen: nothing ships bound to nothing, no id
 * appears twice in one set, and no two actions in one set fight over the
 * same physical control.
 */
class ControllerActionsTest {
    private val scopes = listOf(
        "renpy", "godot", "kirikiri", "kirikiri2", "catsystem2", "cmvs",
        "rpgmaker/xp", "rpgmaker/vx", "rpgmaker/vxace",
        "rpgmaker/2000", "rpgmaker/2003",
        "rpgmaker/mv", "rpgmaker/mz",
    )

    private val allSets: List<Pair<String, List<ControllerAction>>> =
        (scopes.map { it to ControllerActions.forEngine(it) }) + ("common" to ControllerActions.common)

    @Test
    fun `no shipped default is unbound`() {
        val unbound = allSets.flatMap { (scope, actions) ->
            actions.filter { it.default is ControllerBinding.None }.map { "$scope/${it.id}" }
        }
        assertEquals(emptyList<String>(), unbound)
    }

    @Test
    fun `ids and titles are unique within a set`() {
        for ((scope, actions) in allSets) {
            assertEquals("$scope has a duplicate action id", actions.size, actions.map { it.id }.distinct().size)
            assertEquals("$scope has a duplicate title", actions.size, actions.map { it.title }.distinct().size)
            assertTrue("$scope has a blank title", actions.none { it.title.isBlank() })
        }
    }

    @Test
    fun `no two actions in a set claim the same control`() {
        for ((scope, actions) in allSets) {
            val defaults = actions.map { it.default }
            assertEquals("$scope binds one control twice", defaults.size, defaults.distinct().size)
        }
    }

    @Test
    fun `an engine without a set of its own falls back to the common list`() {
        assertSame(ControllerActions.common, ControllerActions.forEngine(null))
        assertSame(ControllerActions.common, ControllerActions.forEngine("buriko"))
        assertSame(ControllerActions.common, ControllerActions.forEngine("flash_air"))
        assertSame(ControllerActions.common, ControllerActions.forEngine("html"))
        assertSame(ControllerActions.common, ControllerActions.forEngine("nothing-we-ship"))
    }

    @Test
    fun `RPG Maker's three runtimes get three different sets`() {
        val rgss = ControllerActions.forEngine("rpgmaker/vxace")
        val easyrpg = ControllerActions.forEngine("rpgmaker/2000")
        val web = ControllerActions.forEngine("rpgmaker/mv")
        assertNotEquals(rgss, easyrpg)
        assertNotEquals(easyrpg, web)
        assertSame(rgss, ControllerActions.forEngine("rpgmaker/xp"))
        assertSame(easyrpg, ControllerActions.forEngine("rpgmaker/2003"))
        assertSame(web, ControllerActions.forEngine("rpgmaker/mz"))
        assertTrue(rgss.any { it.id == "rgss_c" })
        assertTrue(easyrpg.any { it.id == "easyrpg_decision" })
        assertTrue(web.any { it.id == "mvmz_ok" })
    }

    @Test
    fun `CMVS offers its own key functions and folds the ones the engine folds`() {
        val cmvs = ControllerActions.forEngine("cmvs")
        assertNotEquals(ControllerActions.common, cmvs)
        // Every button that did something before is still that thing.
        val onControl = cmvs.associateBy { it.default }
        assertEquals("cmvs_confirm", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_A)]?.id)
        assertEquals("cmvs_cancel", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_B)]?.id)
        assertEquals("cmvs_popup_menu", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_START)]?.id)
        assertEquals("cmvs_forced_skip", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_X)]?.id)
        assertEquals("cmvs_auto_advance", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_Y)]?.id)
        assertEquals("cmvs_history_mode", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_SELECT)]?.id)
        assertEquals("cmvs_quick_save", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_L1)]?.id)
        assertEquals("cmvs_quick_load", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_R1)]?.id)
        assertEquals("cmvs_history_up", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_L2)]?.id)
        assertEquals("cmvs_history_down", onControl[ControllerBinding.Key(KeyEvent.KEYCODE_BUTTON_R2)]?.id)
        assertTrue(cmvs.any { it.id == "left_x" } && cmvs.any { it.id == "left_y" })
        // 09 read-skip, 22 window-centring and 23/24 menu-value are folded or
        // dropped for the engine's own reasons; none of them is a row.
        assertTrue(cmvs.none { it.id.contains("read_skip") })
        assertTrue(cmvs.none { it.id.contains("outer") || it.id.contains("menu_value") })
    }

    @Test
    fun `scopes carry the context only where one family covers several engines`() {
        assertEquals("renpy", ControllerScope.of("renpy", "standard"))
        assertEquals("kirikiri", ControllerScope.of("KiriKiri", "default"))
        assertEquals("rpgmaker/vxace", ControllerScope.of("rpgmaker", "vxace"))
        assertEquals("rpgmaker/2000", ControllerScope.of("rpgmaker", "2000"))
        assertEquals("rpgmaker", ControllerScope.of("rpgmaker", DEFAULT_ENGINE_CONTEXT))
        assertEquals("rpgmaker", ControllerScope.of("rpgmaker", null))
        assertEquals(null, ControllerScope.of(null, "vxace"))
    }

    @Test
    fun `every bypassable scope has a set of its own`() {
        val bypassable = listOf("renpy", "godot", "rpgmaker/2000", "rpgmaker/2003")
        for (scope in bypassable) {
            assertTrue("$scope offers no bypass", ControllerActions.offersBypass(scope))
            assertNotEquals("$scope has no set of its own", ControllerActions.common, ControllerActions.forEngine(scope))
        }
        assertTrue(scopes.filterNot { it in bypassable }.none(ControllerActions::offersBypass))
        assertTrue(!ControllerActions.offersBypass(null))
    }
}
