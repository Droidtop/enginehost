package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TestingConfigStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val working = """{"engine":"renpy","engineVersion":"7.3.5","title":"Working"}"""
    private val tested = JSONObject().put("engine", "renpy").put("engineVersion", "7.4.11").put("title", "Tested")

    private fun store() = TestingConfigStore(temporaryFolder.newFolder("store"))

    private fun gameWithWorkingConfig() = temporaryFolder.newFolder("game").also {
        it.resolve(CONFIG_FILE_NAME).writeText(working)
    }

    @Test
    fun `a test stores the configuration outside the game folder and leaves the working one alone`() {
        val game = gameWithWorkingConfig()
        val store = store()
        store.set(game, tested, now = 42L)

        assertEquals(working, game.resolve(CONFIG_FILE_NAME).readText())
        assertEquals(listOf(CONFIG_FILE_NAME), game.list()!!.toList())
        val pending = store.get(game)!!
        assertEquals("7.4.11", pending.config.getString("engineVersion"))
        assertEquals(42L, pending.savedAt)
    }

    @Test
    fun `a test launch runs the testing configuration, any other launch the working one`() {
        val game = gameWithWorkingConfig()
        val store = store()
        store.set(game, tested)

        val testLaunch = EngineConfigReader.resolve(game, null, store.get(game)!!.config.toString())
        assertEquals("7.4.11", testLaunch.engineVersion.toString())
        assertEquals("Tested", testLaunch.title)

        val play = EngineConfigReader.resolve(game, null)
        assertEquals("7.3.5", play.engineVersion.toString())
    }

    @Test
    fun `discard rolls back to the working config untouched`() {
        val game = gameWithWorkingConfig()
        val store = store()
        store.set(game, tested)
        store.discard(game)

        assertNull(store.get(game))
        assertEquals(working, game.resolve(CONFIG_FILE_NAME).readText())
    }

    @Test
    fun `keep writes the testing configuration as the folder's config and settles it`() {
        val game = gameWithWorkingConfig()
        val store = store()
        store.set(game, tested)
        store.keep(game)

        assertNull(store.get(game))
        assertEquals("7.4.11", JSONObject(game.resolve(CONFIG_FILE_NAME).readText()).getString("engineVersion"))
    }

    @Test
    fun `a new test replaces the pending one`() {
        val game = gameWithWorkingConfig()
        val store = store()
        store.set(game, tested, now = 1L)
        store.set(game, JSONObject(tested.toString()).put("engineVersion", "7.5.0"), now = 2L)

        val pending = store.get(game)!!
        assertEquals("7.5.0", pending.config.getString("engineVersion"))
        assertEquals(2L, pending.savedAt)
    }

    @Test
    fun `testing configurations are per game`() {
        val store = store()
        val first = temporaryFolder.newFolder("first")
        val second = temporaryFolder.newFolder("second")
        store.set(first, tested)

        assertNull(store.get(second))
    }

    @Test
    fun `keep refuses a configuration Enginehost could not read and writes nothing`() {
        val game = gameWithWorkingConfig()
        val store = store()
        store.set(game, JSONObject().put("title", "no engine"))

        assertThrows(InvalidEngineConfigException::class.java) { store.keep(game) }
        assertEquals(working, game.resolve(CONFIG_FILE_NAME).readText())
        assertFalse(store.get(game) == null)
    }
}
