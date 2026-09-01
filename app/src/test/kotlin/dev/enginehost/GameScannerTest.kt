package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameScannerTest {
    private class Collector : GameScanner.Listener {
        val found = mutableListOf<GameCandidate>()
        var finished = false
        var stoppedEarly = false
        override fun onProgress(directoriesExamined: Int, found: Int) = Unit
        override fun onFound(candidate: GameCandidate) {
            found += candidate
        }

        override fun onFinished(directoriesExamined: Int, found: Int, stoppedEarly: Boolean, unreadable: Int) {
            finished = true
            this.stoppedEarly = stoppedEarly
        }
    }

    private fun tempRoot(): File = createTempDir(prefix = "scan-test").also { it.deleteOnExit() }

    @Test
    fun `finds a renpy tree and does not descend into it`() {
        val root = tempRoot()
        val game = File(root, "library/SomeGame").apply { mkdirs() }
        File(game, "renpy").mkdirs()
        File(game, "game").mkdirs()
        File(game, "game/script.rpyc").writeBytes(byteArrayOf(1, 2, 3))
        // A nested rpa must not produce a second candidate inside the same game.
        File(game, "game/archive.rpa").writeBytes(byteArrayOf(1))

        val collector = Collector()
        GameScanner().scan(root, collector)

        assertTrue(collector.finished)
        assertEquals(listOf(game.canonicalFile), collector.found.map { it.folder.canonicalFile })
        assertEquals("renpy", collector.found.single().detection.engine)
    }

    @Test
    fun `finds a godot project by its own file`() {
        val root = tempRoot()
        val game = File(root, "exports/Adventure").apply { mkdirs() }
        File(game, "project.godot").writeText("config/features=PackedStringArray(\"4.2\")\n")

        val collector = Collector()
        GameScanner().scan(root, collector)

        assertEquals(1, collector.found.size)
        assertEquals("godot", collector.found.single().detection.engine)
    }

    @Test
    fun `a folder with no engine evidence finds nothing`() {
        val root = tempRoot()
        File(root, "documents").mkdirs()
        File(root, "documents/notes.txt").writeText("plain text")

        val collector = Collector()
        GameScanner().scan(root, collector)

        assertTrue(collector.finished)
        assertTrue(collector.found.isEmpty())
    }

    @Test
    fun `cancel stops the walk and reports stopping early`() {
        val root = tempRoot()
        for (index in 0 until 50) File(root, "folder$index").mkdirs()
        val scanner = GameScanner()
        scanner.cancel()
        val collector = Collector()
        scanner.scan(root, collector)
        assertTrue(collector.finished)
        assertTrue(collector.stoppedEarly)
    }
}
