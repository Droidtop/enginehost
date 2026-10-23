package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameConfigStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val folderConfig = """{"engine":"renpy","engineVersion":"7.3.5","title":"Folder"}"""
    private val setUp = JSONObject().put("engine", "renpy").put("engineVersion", "7.4.11").put("title", "Set up")

    private fun store() = GameConfigStore(temporaryFolder.newFolder("game-configs"))

    @Test
    fun `a setup is kept in Enginehost's storage and nothing is written into the game folder`() {
        val game = temporaryFolder.newFolder("game")
        val store = store()
        store.set(game, setUp)

        assertEquals("7.4.11", store.get(game)!!.getString("engineVersion"))
        assertEquals(emptyList<String>(), game.list()!!.toList())
    }

    @Test
    fun `setups are per game`() {
        val store = store()
        store.set(temporaryFolder.newFolder("first"), setUp)
        assertNull(store.get(temporaryFolder.newFolder("second")))
    }

    @Test
    fun `a setup Enginehost could not read is refused`() {
        val game = temporaryFolder.newFolder("game")
        val store = store()
        assertThrows(InvalidEngineConfigException::class.java) { store.set(game, JSONObject().put("title", "no engine")) }
        assertNull(store.get(game))
    }

    @Test
    fun `without a folder file the game runs on its setup`() {
        val game = temporaryFolder.newFolder("game")
        val config = EngineConfigReader.resolve(game, null, storedJson = setUp.toString())
        assertEquals("Set up", config.title)
    }

    @Test
    fun `the game folder's own enginehost json takes precedence over the setup`() {
        val game = temporaryFolder.newFolder("game").also { it.resolve(CONFIG_FILE_NAME).writeText(folderConfig) }
        val config = EngineConfigReader.resolve(game, null, storedJson = setUp.toString())
        assertEquals("Folder", config.title)
    }

    @Test
    fun `a test launch still runs the testing configuration over both`() {
        val game = temporaryFolder.newFolder("game").also { it.resolve(CONFIG_FILE_NAME).writeText(folderConfig) }
        val testing = JSONObject(setUp.toString()).put("title", "Testing").toString()
        val config = EngineConfigReader.resolve(game, null, testingJson = testing, storedJson = setUp.toString())
        assertEquals("Testing", config.title)
    }
}
