package dev.enginehost

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SaveFoldersTest {
    @Test
    fun `system locations without a per-game name get a stable per-game save namespace`() {
        listOf("html", "flash_air", "catsystem2").forEach { engine ->
            assertEquals("My Game", SaveFolders.defaultFor(engine, null, null, "My Game"))
        }
        listOf("mv", "mz").forEach { context ->
            assertEquals("My Game", SaveFolders.defaultFor("rpgmaker", context, null, "My Game"))
        }
    }

    @Test
    fun `engines that save beside the game get no host folder`() {
        listOf("kirikiri2", "buriko", "cmvs", "nscripter").forEach { engine ->
            assertNull(SaveFolders.defaultFor(engine, null, null, "My Game"))
        }
        listOf("2000", "2003", "xp", "vx", "vxace").forEach { context ->
            assertNull(SaveFolders.defaultFor("rpgmaker", context, null, "My Game"))
        }
    }

    @Test
    fun `engines with their own external namespace keep control`() {
        assertNull(SaveFolders.defaultFor("renpy", null, null, "My Game"))
        assertNull(SaveFolders.defaultFor("godot", null, null, "My Game"))
    }

    @Test
    fun `only the engines that lost their host folder look for earlier saves`() {
        listOf("kirikiri2", "buriko", "cmvs").forEach { assertTrue(SaveFolders.formerlyNamed(it, null)) }
        listOf("2000", "2003", "xp", "vx", "vxace").forEach { assertTrue(SaveFolders.formerlyNamed("rpgmaker", it)) }
        // NScripter never had a folder of its own, and the named ones still do.
        listOf("nscripter", "html", "flash_air", "catsystem2", "renpy", "godot").forEach {
            assertFalse(SaveFolders.formerlyNamed(it, null))
        }
        listOf("mv", "mz").forEach { assertFalse(SaveFolders.formerlyNamed("rpgmaker", it)) }
    }

    private fun folder(prefix: String): File = createTempDir(prefix = prefix).also { it.deleteOnExit() }

    @Test
    fun `earlier saves are copied to the same place in the game folder, and nothing is lost`() {
        val earlier = folder("earlier-saves")
        val game = folder("game")
        File(earlier, "save/save000.dat").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        File(earlier, "Save01.lsd").writeBytes(byteArrayOf(2))
        // The game folder's own copy is the game's, whatever the earlier one says.
        File(earlier, "Save02.lsd").writeBytes(byteArrayOf(3))
        File(game, "Save02.lsd").writeBytes(byteArrayOf(4))

        val saves = requireNotNull(EarlierSaves.find(earlier, game))
        assertEquals(listOf("Save01.lsd", "save/save000.dat"), saves.missing)

        assertEquals(EarlierSaves.Copied(copied = 2, failed = 0), saves.copy())
        assertArrayEquals(byteArrayOf(1), File(game, "save/save000.dat").readBytes())
        assertArrayEquals(byteArrayOf(2), File(game, "Save01.lsd").readBytes())
        assertArrayEquals(byteArrayOf(4), File(game, "Save02.lsd").readBytes())
        listOf("save/save000.dat", "Save01.lsd", "Save02.lsd").forEach { assertTrue(File(earlier, it).isFile) }
        assertTrue(game.walkTopDown().none { it.name.endsWith(".enginehost-copy") })

        // Once the game folder has them all there is nothing left to offer.
        assertNull(EarlierSaves.find(earlier, game))
    }

    @Test
    fun `an empty earlier folder offers nothing`() {
        assertNull(EarlierSaves.find(folder("earlier-empty"), folder("game")))
    }
}
