package dev.enginehost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineSaveFoldersTest {
    private val root: File = Files.createTempDirectory("saves").toFile().canonicalFile

    @Test
    fun aFolderTheEngineNamesInsideTheSaveRootIsKept() {
        val godot = File(root, "godot/app_userdata/Goodbye Eternity")
        assertEquals(godot, EngineSaveFolders.within(root, godot))
    }

    @Test
    fun theSaveRootItselfIsKept() {
        assertEquals(root, EngineSaveFolders.within(root, root))
    }

    @Test
    fun aFolderOutsideTheSaveRootIsIgnored() {
        assertNull(EngineSaveFolders.within(root, File(root.parentFile, "elsewhere")))
        assertNull(EngineSaveFolders.within(root, File(root, "../elsewhere")))
        assertNull(EngineSaveFolders.within(root, File(root.path + "-sibling")))
    }

    @Test
    fun aRelativePathIsIgnored() {
        assertNull(EngineSaveFolders.within(root, File("godot/app_userdata/x")))
    }
}
