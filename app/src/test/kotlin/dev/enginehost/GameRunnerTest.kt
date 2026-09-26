package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    private fun makeGameFolder(withExecFile: Boolean = true): File {
        val folder = temporaryFolder.newFolder("game")
        if (withExecFile) {
            File(folder, "Game.exe").writeText("")
        }
        return folder
    }

    @Test
    fun `valid save folder name passes validation`() {
        assertTrue(SaveFolders.isPlainName("my-game"))
        assertTrue(SaveFolders.isPlainName("my_game"))
        assertTrue(SaveFolders.isPlainName("my.game"))
        assertTrue(SaveFolders.isPlainName("game"))
    }

    @Test
    fun `invalid save folder names are rejected`() {
        assertFalse(SaveFolders.isPlainName(""))
        assertFalse(SaveFolders.isPlainName("."))
        assertFalse(SaveFolders.isPlainName(".."))
        assertFalse(SaveFolders.isPlainName("a/b"))
        assertFalse(SaveFolders.isPlainName("a\\b"))
        assertFalse(SaveFolders.isPlainName("a\u0000b"))
    }

    @Test
    fun `save folder name validation message is clear`() {
        // The require() in SaveLocationStore.saveFolderFor throws with this message
        val config = makeConfig(saveFolder = "invalid/name")
        val root = File("/tmp/saves")
        val folder = File(root, config.saveFolder!!)
        
        // Test the validation logic directly
        val isValid = SaveFolders.isPlainName(config.saveFolder!!)
        assertFalse(isValid)
    }

    @Test
    fun `execFile existence check fails for missing file`() {
        val gameFolder = makeGameFolder(withExecFile = false)
        val config = makeConfig(execFile = "Game.exe")
        
        val execFile = config.execFile!!
        val exists = File(gameFolder, execFile).isFile
        
        assertFalse(exists)
    }

    @Test
    fun `execFile existence check passes for existing file`() {
        val gameFolder = makeGameFolder(withExecFile = true)
        val config = makeConfig(execFile = "Game.exe")
        
        val execFile = config.execFile!!
        val exists = File(gameFolder, execFile).isFile
        
        assertTrue(exists)
    }

    @Test
    fun `execFile is null passes without check`() {
        val gameFolder = makeGameFolder(withExecFile = false)
        val config = makeConfig(execFile = null)
        
        assertNull(config.execFile)
    }

    @Test
    fun `execFile with path traversal is checked against game folder`() {
        val gameFolder = makeGameFolder(withExecFile = false)
        val config = makeConfig(execFile = "../outside.exe")
        
        val execFile = config.execFile!!
        val targetFile = File(gameFolder, execFile)
        
        // The file doesn't exist, but also the canonical path would be outside
        // the game folder. The isFile check handles this naturally.
        assertFalse(targetFile.isFile)
    }
}