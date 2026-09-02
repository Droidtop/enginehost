package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameScannerTest {
    // The REAL shipped seed registry (the same file droidtop bundles):
    // tests exercise the exact data the app classifies with, so a
    // registry edit that breaks scanning fails here before it ships.
    private val rows = EngineRegistryParser.parse(
        File("src/main/assets/engines-database.json").readText(),
    )

    private class Collector : GameScanner.Listener {
        val found = mutableListOf<GameCandidate>()
        var finished = false
        var stoppedEarly = false
        var lastExamined = 0
        var unreadable = 0
        override fun onProgress(directoriesExamined: Int, found: Int) = Unit
        override fun onFound(candidate: GameCandidate) {
            found += candidate
        }

        override fun onFinished(directoriesExamined: Int, found: Int, stoppedEarly: Boolean, unreadable: Int) {
            finished = true
            this.stoppedEarly = stoppedEarly
            this.lastExamined = directoriesExamined
            this.unreadable = unreadable
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
        GameScanner(rows).scan(root, collector)

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
        GameScanner(rows).scan(root, collector)

        assertEquals(1, collector.found.size)
        assertEquals("godot", collector.found.single().detection.engine)
    }

    @Test
    fun `reads the engine version out of a godot pack header`() {
        val root = tempRoot()
        val game = File(root, "exports/Packed").apply { mkdirs() }
        val header = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("GDPC".toByteArray(Charsets.US_ASCII))
        header.putInt(2)
        header.putInt(4)
        header.putInt(5)
        header.putInt(1)
        File(game, "game.pck").writeBytes(header.array())

        val collector = Collector()
        GameScanner(rows).scan(root, collector)

        val detection = collector.found.single().detection
        assertEquals("godot", detection.engine)
        assertEquals("4.5.1", detection.engineVersion)
    }

    @Test
    fun `old renpy version comes from the runtime init when vc_version has only a stamp`() {
        val root = tempRoot()
        val game = File(root, "OldGame").apply { mkdirs() }
        File(game, "renpy").mkdirs()
        File(game, "game").mkdirs()
        File(game, "renpy/vc_version.py").writeText("vc_version = 22090809\n")
        File(game, "renpy/__init__.py").writeText("version_tuple = (7, 5, 3, vc_version)\n")

        val collector = Collector()
        GameScanner(rows).scan(root, collector)

        val detection = collector.found.single().detection
        assertEquals("renpy", detection.engine)
        assertEquals("7.5.3", detection.engineVersion)
    }

    @Test
    fun `a folder with no engine evidence finds nothing`() {
        val root = tempRoot()
        File(root, "documents").mkdirs()
        File(root, "documents/notes.txt").writeText("plain text")

        val collector = Collector()
        GameScanner(rows).scan(root, collector)

        assertTrue(collector.finished)
        assertTrue(collector.found.isEmpty())
    }

    @Test
    fun `cancel stops the walk and reports stopping early`() {
        val root = tempRoot()
        for (index in 0 until 50) File(root, "folder$index").mkdirs()
        val scanner = GameScanner(rows)
        scanner.cancel()
        val collector = Collector()
        scanner.scan(root, collector)
        assertTrue(collector.finished)
        assertTrue(collector.stoppedEarly)
    }

    @Test
    fun `examined count covers every folder visited, including a found game root and an unreadable one`() {
        val root = tempRoot()
        // A game root: examined once, not descended into.
        val game = File(root, "library/SomeGame").apply { mkdirs() }
        File(game, "project.godot").writeText("this file is enough evidence on its own")
        // A plain folder with no engine evidence: examined, then descended into.
        val plain = File(root, "plain").apply { mkdirs() }
        File(plain, "notes.txt").writeText("just some notes")
        // A folder whose listing cannot be read: still examined, counted unreadable.
        val locked = File(root, "locked").apply { mkdirs() }
        File(locked, "child").mkdirs()
        check(locked.setReadable(false, false)) { "test setup needs to be able to lock down a folder" }

        val collector = Collector()
        try {
            GameScanner(rows).scan(root, collector)
        } finally {
            // Restore permissions so JVM temp-dir cleanup (and deleteOnExit) can remove it.
            locked.setReadable(true, false)
        }

        assertTrue(collector.finished)
        assertEquals(1, collector.found.size)
        // Every folder actually visited by the walk: root, library, library/SomeGame,
        // plain, locked. lockedChild is never visited because locked could not be
        // listed. library/SomeGame's own children are never visited because it was
        // recognized as a game root.
        assertEquals(5, collector.lastExamined)
        assertTrue(collector.unreadable >= 1)
    }

    @Test
    fun `progress is still reported when a whole run of folders is found game roots`() {
        // Regression test for the diagnosed bug: onProgress used to only be
        // called from the bottom of the loop body, which the found-a-game
        // branch `continue`d past. A stretch of the walk dominated by found
        // game roots therefore reported no progress at all, even though the
        // examined count was climbing internally. Twenty-five identical game
        // roots under one plain parent guarantee that whichever folder is the
        // 25th one examined overall -- a boundary the batched PROGRESS_EVERY
        // check only reports on -- is itself a found-game-root folder, so
        // this reproduces the skipped branch regardless of listing order.
        val root = tempRoot()
        repeat(25) { index ->
            val game = File(root, "Game$index").apply { mkdirs() }
            File(game, "project.godot").writeText("evidence enough on its own")
        }

        val progressReports = mutableListOf<Pair<Int, Int>>()
        val listener = object : GameScanner.Listener {
            override fun onProgress(directoriesExamined: Int, found: Int) {
                progressReports += directoriesExamined to found
            }
            override fun onFound(candidate: GameCandidate) = Unit
            override fun onFinished(directoriesExamined: Int, found: Int, stoppedEarly: Boolean, unreadable: Int) = Unit
        }
        GameScanner(rows).scan(root, listener)

        // root + 25 game roots = 26 folders examined; the 25th one hit is a
        // game root, so a correct implementation must still report there.
        assertTrue(
            "expected an onProgress(25, ...) call, got $progressReports",
            progressReports.any { (examined, _) -> examined == 25 },
        )
    }
}
