package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameOverridesTest {
    @Test
    fun setsATopLevelKeyAndAnOptionAndTakesThemBack() {
        val one = GameOverrides.with(null, "pluginVersion", "1.0.1-2")
        val two = GameOverrides.with(one, "options.fullscreen", true)
        assertEquals("1.0.1-2", GameOverrides.valueOf(two, "pluginVersion"))
        assertEquals(true, GameOverrides.valueOf(two, "options.fullscreen"))
        val back = GameOverrides.with(GameOverrides.with(two, "pluginVersion", null), "options.fullscreen", null)
        assertNull(back)
    }

    @Test
    fun anOptionNotSetIsNull() {
        assertNull(GameOverrides.valueOf("{\"options\":{\"a\":1}}", "options.b"))
        assertNull(GameOverrides.valueOf(null, "pluginVersion"))
    }

    @Test
    fun overridesWinOverTheFoldersFileAndMergeOptionsKeyByKey() {
        val folder = JSONObject("{\"pluginVersion\":\"1.0.0-1\",\"options\":{\"a\":1,\"b\":2}}")
        val overrides = JSONObject("{\"pluginVersion\":\"1.0.1-2\",\"options\":{\"b\":3}}")
        val merged = EngineConfigReader.mergeAuthoritative(overrides, folder)
        assertEquals("1.0.1-2", merged.getString("pluginVersion"))
        assertEquals(1, merged.getJSONObject("options").getInt("a"))
        assertEquals(3, merged.getJSONObject("options").getInt("b"))
    }
}
