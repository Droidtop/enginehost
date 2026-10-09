package dev.enginehost

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerBindingStoreTest {
    private class MemoryStorage : BindingStorage {
        val values = mutableMapOf<String, Any>()
        override fun binding(key: String) = values[key] as? JSONObject
        override fun flag(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
        override fun contains(key: String) = values.containsKey(key)
        override fun edit(change: (MutableMap<String, Any>) -> Unit) = change(values)
    }

    private val storage = MemoryStorage()
    private fun store(engine: String? = "renpy", game: String? = null) = ControllerBindingStore(storage, engine, game)
    private val action = ControllerBindingStore(storage, "renpy").actions().first()
    private fun key(code: Int) = ControllerBinding.Key(code)

    @Test
    fun anUntouchedActionIsItsDefaultFromTheDefaultLevel() {
        assertEquals(action.default, store().get(action))
        assertEquals(ControllerBindingStore.Source.DEFAULT, store(game = "/g/a").sourceOf(action))
    }

    @Test
    fun theFirstLevelThatHasABindingWins() {
        store(engine = null).set(action, key(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(ControllerBindingStore.Source.EVERYWHERE, store(game = "/g/a").sourceOf(action))
        store().set(action, key(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(key(KeyEvent.KEYCODE_BUTTON_R1), store(game = "/g/a").get(action))
        assertEquals(ControllerBindingStore.Source.ENGINE, store(game = "/g/a").sourceOf(action))
        store(game = "/g/a").set(action, key(KeyEvent.KEYCODE_BUTTON_Z))
        assertEquals(key(KeyEvent.KEYCODE_BUTTON_Z), store(game = "/g/a").get(action))
        assertEquals(ControllerBindingStore.Source.THIS_GAME, store(game = "/g/a").sourceOf(action))
    }

    @Test
    fun aGamesButtonsDoNotReachItsEngineOrAnotherGame() {
        store(game = "/g/a").set(action, key(KeyEvent.KEYCODE_BUTTON_Z))
        assertEquals(action.default, store().get(action))
        assertEquals(action.default, store(game = "/g/b").get(action))
        assertTrue(store(game = "/g/a").hasGameBindings())
        assertFalse(store(game = "/g/b").hasGameBindings())
        assertFalse(store().hasGameBindings())
    }

    @Test
    fun clearingOrResettingAGameLeavesTheOtherLevelsAlone() {
        store().set(action, key(KeyEvent.KEYCODE_BUTTON_R1))
        store(game = "/g/a").set(action, key(KeyEvent.KEYCODE_BUTTON_Z))
        store(game = "/g/a.b").set(action, key(KeyEvent.KEYCODE_BUTTON_C))
        store(game = "/g/a").clearOverride(action)
        assertEquals(key(KeyEvent.KEYCODE_BUTTON_R1), store(game = "/g/a").get(action))
        store(game = "/g/a").set(action, key(KeyEvent.KEYCODE_BUTTON_Z))
        store(game = "/g/a").reset()
        assertFalse(store(game = "/g/a").hasGameBindings())
        assertEquals(key(KeyEvent.KEYCODE_BUTTON_R1), store().get(action))
        assertTrue(store(game = "/g/a.b").hasGameBindings())
    }

    @Test
    fun theExportedMapCarriesTheGamesButtons() {
        store(game = "/g/a").set(action, key(KeyEvent.KEYCODE_BUTTON_Z))
        val exported = store(game = "/g/a").exportJson().getJSONObject(action.id)
        assertEquals(KeyEvent.KEYCODE_BUTTON_Z, exported.getInt("code"))
    }
}
