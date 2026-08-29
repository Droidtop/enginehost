package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineConfigReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `folder config wins while caller fills missing fields and options`() {
        val gameFolder = temporaryFolder.newFolder("game")
        gameFolder.resolve(CONFIG_FILE_NAME).writeText(
            """{
                "engine": "renpy",
                "engineContext": "python3",
                "engineVersion": "8.2.1",
                "options": {
                    "renderer": "gl2",
                    "nested": { "folder": true }
                }
            }""".trimIndent(),
        )

        val config = EngineConfigReader.resolve(
            gameFolder,
            """{
                "engine": "godot",
                "engineContext": "godot4",
                "engineVersion": "4.3",
                "execFile": "game.py",
                "pluginVersion": "2.0.0-2.4.0",
                "options": {
                    "renderer": "vulkan",
                    "touch": true,
                    "nested": { "folder": false, "caller": true }
                }
            }""".trimIndent(),
        )

        assertEquals("renpy", config.engine)
        assertEquals("python3", config.engineContext)
        assertEquals("8.2.1", config.engineVersion.toString())
        assertEquals("game.py", config.execFile)
        assertEquals("gl2", config.options!!.getString("renderer"))
        assertEquals(true, config.options.getBoolean("touch"))
        assertEquals(true, config.options.getJSONObject("nested").getBoolean("folder"))
        assertEquals(true, config.options.getJSONObject("nested").getBoolean("caller"))
    }

    @Test
    fun `folder can be completed by caller before required-field validation`() {
        val gameFolder = temporaryFolder.newFolder("game")
        gameFolder.resolve(CONFIG_FILE_NAME).writeText("""{"engine":"godot"}""")

        val config = EngineConfigReader.resolve(gameFolder, """{"engineVersion":"4.3"}""")

        assertEquals("godot", config.engine)
        assertEquals("4.3", config.engineVersion.toString())
        assertNull(config.execFile)
    }

    @Test
    fun `symbolic or malformed engine version is rejected instead of becoming zero`() {
        val gameFolder = temporaryFolder.newFolder("game")

        assertThrows(InvalidEngineConfigException::class.java) {
            EngineConfigReader.resolve(
                gameFolder,
                """{"engine":"rpgmaker","engineVersion":"MZ"}""",
            )
        }
    }

    @Test
    fun `empty or reversed plugin version allowlist is rejected`() {
        val gameFolder = temporaryFolder.newFolder("allowlist")

        listOf("", "2.0-1.0").forEach { allowlist ->
            assertThrows(InvalidEngineConfigException::class.java) {
                EngineConfigReader.resolve(
                    gameFolder,
                    """{"engine":"renpy","engineVersion":"8.5","pluginVersion":"$allowlist"}""",
                )
            }
        }
    }
}
