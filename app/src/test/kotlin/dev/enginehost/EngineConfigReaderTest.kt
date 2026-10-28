package dev.enginehost

import dev.droidtop.shared.version.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
                "runtimeRequirements": {"python":"3.9.10"},
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
        assertEquals(Version.parse("3.9.10"), config.runtimeRequirements["python"])
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

    @Test
    fun `invalid runtime requirement version is rejected`() {
        val gameFolder = temporaryFolder.newFolder("runtime-requirement")
        assertThrows(InvalidEngineConfigException::class.java) {
            EngineConfigReader.resolve(
                gameFolder,
                """{"engine":"rpgmaker","engineVersion":"3.0","runtimeRequirements":{"ruby":"classic"}}""",
            )
        }
    }

    @Test
    fun `title comes from the caller when the folder has none and from the folder otherwise`() {
        val gameFolder = temporaryFolder.newFolder("Some Folder Name")
        gameFolder.resolve(CONFIG_FILE_NAME).writeText("""{"engine":"renpy","engineVersion":"8.2.1"}""")
        val filled = EngineConfigReader.resolve(gameFolder, """{"title":"  A Real Title  "}""")
        assertEquals("A Real Title", filled.title)

        gameFolder.resolve(CONFIG_FILE_NAME).writeText("""{"engine":"renpy","engineVersion":"8.2.1","title":"Folder Says"}""")
        val authoritative = EngineConfigReader.resolve(gameFolder, """{"title":"Caller Says"}""")
        assertEquals("Folder Says", authoritative.title)

        assertNull(EngineConfigReader.parseDocument("""{"engine":"renpy","engineVersion":"8.2.1","title":"   "}""").title)
    }

    @Test
    fun `a root must be a folder inside the game folder that holds the execFile`() {
        fun parse(json: String) = runCatching { EngineConfigReader.parseDocument(json) }
        val base = "\"engine\":\"rpgmaker\",\"engineVersion\":\"3.1\""
        assertEquals("Game 1.0", parse("{$base,\"root\":\"Game 1.0/\",\"execFile\":\"Game 1.0/Game.exe\"}").getOrThrow().root)
        listOf("/abs", "../up", "a/../b", "a\\\\b").forEach { root ->
            assertTrue(root, parse("{$base,\"root\":\"$root\"}").exceptionOrNull() is InvalidEngineConfigException)
        }
        assertTrue(parse("{$base,\"root\":\"Game 1.0\",\"execFile\":\"Other/Game.exe\"}").exceptionOrNull() is InvalidEngineConfigException)
    }
}
