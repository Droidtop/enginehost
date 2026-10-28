package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SaveBackupsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_761_670_000_000L

    private fun saves(): File = temp.newFolder("saves").also {
        File(it, "slot1.sav").writeText("one")
        File(it, "sub").mkdirs()
        File(it, "sub/slot2.sav").writeText("two")
    }

    @Test
    fun `a backup copies every save into a new dated folder and never reuses one`() {
        val source = saves()
        val backups = temp.newFolder("backups")
        val (first, copied) = SaveBackups.backUp(listOf(source), backups, now)
        assertEquals(2, copied.files)
        assertEquals("one", File(first, "saves/slot1.sav").readText())
        assertEquals("two", File(first, "saves/sub/slot2.sav").readText())
        val (second, _) = SaveBackups.backUp(listOf(source), backups, now)
        assertNotEquals(first, second)
        assertEquals(listOf(first.name, second.name).sorted(), backups.list()!!.sorted())
    }

    @Test
    fun `a link is neither followed nor copied`() {
        val source = saves()
        val outside = temp.newFolder("outside").also { File(it, "secret.txt").writeText("not a save") }
        Files.createSymbolicLink(File(source, "escape").toPath(), outside.toPath())
        val (backup, copied) = SaveBackups.backUp(listOf(source), temp.newFolder("backups"), now)
        assertFalse(File(backup, "saves/escape/secret.txt").exists())
        assertEquals(2, copied.files)
        assertEquals(1, copied.skipped)
    }

    @Test
    fun `a restore backs the current saves up first and keeps saves the backup lacks`() {
        val source = saves()
        val backups = temp.newFolder("backups")
        val (backup, _) = SaveBackups.backUp(listOf(source), backups, now)
        File(source, "slot1.sav").writeText("changed")
        File(source, "slot3.sav").writeText("new")
        SaveBackups.restore(backup, listOf(source), backups, now + 60_000)
        assertEquals("one", File(source, "slot1.sav").readText())
        assertEquals("new", File(source, "slot3.sav").readText())
        val taken = SaveBackups.list(backups)
        assertEquals(2, taken.size)
        // Newest first: the backup the restore took of the changed saves.
        assertEquals("changed", File(taken[0].folder, "saves/slot1.sav").readText())
        assertTrue(taken[0].takenAt > taken[1].takenAt)
    }

    @Test
    fun `a copy goes to a new folder in the destination`() {
        val source = saves()
        val destination = temp.newFolder("usb")
        val (first, _) = SaveBackups.copyTo(listOf(source), destination, "MGQ Paradox saves")
        val (second, _) = SaveBackups.copyTo(listOf(source), destination, "MGQ Paradox saves")
        assertEquals("MGQ Paradox saves", first.name)
        assertEquals("MGQ Paradox saves 2", second.name)
        assertEquals("two", File(second, "saves/sub/slot2.sav").readText())
    }
}
