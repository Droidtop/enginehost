package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The execFile half of the launch preflight, tested through
 * [GameRunner.missingExecFile]: the whole decision, whose answer
 * [GameRunner.plan] turns into a Plan.Failure sentence. This app has no
 * Robolectric, so plan() itself, which needs a real Context to resolve
 * plugins and read the sentence's string resource, cannot run in a unit
 * test -- which is why the decision sits in a Context-free function.
 */
class GameRunnerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun makeConfig(
        engine: String = "html",
        engineContext: String? = null,
        execFile: String? = null,
        saveFolder: String? = null,
        engineVersion: String = "1.0.0"
    ): EngineConfig {
        return EngineConfig(
            engine = engine,
            engineContext = engineContext,
            engineVersion = Version.parse(engineVersion),
            runtimeRequirements = emptyMap(),
            pluginVersionConstraint = null,
            execFile = execFile,
            saveFolder = saveFolder,
            options = null,
        )
    }

    @Test
    fun `an entry file the game folder has lets the launch proceed`() {
        val gameFolder = temporaryFolder.newFolder("game")
        File(gameFolder, "Game.exe").writeText("")
        assertNull(GameRunner.missingExecFile(gameFolder, makeConfig(execFile = "Game.exe")))
    }

    @Test
    fun `an entry file the game folder lacks is the launch failure`() {
        val gameFolder = temporaryFolder.newFolder("game")
        assertEquals("Game.exe", GameRunner.missingExecFile(gameFolder, makeConfig(execFile = "Game.exe")))
    }

    @Test
    fun `a config naming no entry file asks for no check`() {
        val gameFolder = temporaryFolder.newFolder("game")
        assertNull(GameRunner.missingExecFile(gameFolder, makeConfig(execFile = null)))
    }

    @Test
    fun `an entry file name that escapes the game folder still has to name an existing file`() {
        val gameFolder = temporaryFolder.newFolder("game")
        assertEquals("../outside.exe", GameRunner.missingExecFile(gameFolder, makeConfig(execFile = "../outside.exe")))
    }
}
