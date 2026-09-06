package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveFoldersTest {
    @Test
    fun `every RPG Maker generation gets a stable per-game save namespace`() {
        listOf("2000", "2003", "xp", "vx", "vxace", "mv", "mz").forEach { context ->
            assertEquals("My Game", SaveFolders.defaultFor("rpgmaker", context, null, "My Game"))
        }
    }

    @Test
    fun `engines with their own external namespace keep control`() {
        assertNull(SaveFolders.defaultFor("renpy", null, null, "My Game"))
        assertNull(SaveFolders.defaultFor("godot", null, null, "My Game"))
    }
}
