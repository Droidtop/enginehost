package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

class GameScannerTest {
    // The REAL shipped seed registry (the same file droidtop bundles):
    // tests exercise the exact data the app classifies with, so a
    // registry edit that breaks scanning fails here before it ships.
    private val rows = EngineRegistryParser.parse(
        SeedAssets.read("engines-database.json"),
    )

    private class Collector : GameScanner.Listener {
        val found: MutableList<GameFinding> = Collections.synchronizedList(mutableListOf())
        val progress: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var summary: GameScanner.Summary? = null
        val finished get() = summary != null
        val stoppedEarly get() = summary!!.stoppedEarly
        val unreadable get() = summary!!.unreadable
        val lastExamined get() = summary!!.directoriesExamined

        override fun onProgress(directoriesExamined: Int, found: Int) {
            progress += directoriesExamined to found
        }

        override fun onFound(finding: GameFinding) {
            found += finding
        }

        override fun onFinished(summary: GameScanner.Summary) {
            this.summary = summary
        }

        fun folders(): List<String> = found.map { File(it.path).canonicalPath }.sorted()
    }

    private fun tempRoot(): File = createTempDir(prefix = "scan-test").also { it.deleteOnExit() }

    private fun scan(root: File, scanner: GameScanner = GameScanner(rows), full: Boolean = false): Collector =
        Collector().also { scanner.scan(root, it, full) }

    private fun renpy(folder: File): File = folder.apply {
        File(this, "renpy").mkdirs()
        File(this, "game").mkdirs()
        File(this, "game/script.rpyc").writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun godot(folder: File): File = folder.apply {
        mkdirs()
        File(this, "project.godot").writeText("config/features=PackedStringArray(\"4.2\")\n")
    }

    private fun canonical(file: File) = file.canonicalPath

    // ---- Detection through the walk -------------------------------------------------

    @Test
    fun `finds a renpy tree and does not descend into it`() {
        val root = tempRoot()
        val game = renpy(File(root, "library/SomeGame").apply { mkdirs() })
        // A nested rpa must not produce a second candidate inside the same game.
        File(game, "game/archive.rpa").writeBytes(byteArrayOf(1))

        val collector = scan(root)

        assertTrue(collector.finished)
        assertEquals(listOf(canonical(game)), collector.folders())
        assertEquals("renpy", collector.found.single().engine)
    }

    @Test
    fun `a game inside a game is not counted twice`() {
        val root = tempRoot()
        val game = renpy(File(root, "Outer").apply { mkdirs() })
        // A bundled sample project carries its own markers.
        renpy(File(game, "game/sample"))
        godot(File(game, "extras/Other"))

        val collector = scan(root)

        assertEquals(listOf(canonical(game)), collector.folders())
    }

    @Test
    fun `a wrapper folder is walked through and the game inside is found once`() {
        val root = tempRoot()
        val inner = renpy(File(root, "Wrapper/Game-1.0-pc").apply { mkdirs() })

        val collector = scan(root)

        assertEquals(listOf(canonical(inner)), collector.folders())
    }

    @Test
    fun `finds a godot project by its own file`() {
        val root = tempRoot()
        godot(File(root, "exports/Adventure"))

        val collector = scan(root)

        assertEquals(1, collector.found.size)
        assertEquals("godot", collector.found.single().engine)
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

        val finding = scan(root).found.single()

        assertEquals("godot", finding.engine)
        assertEquals("4.5.1", finding.engineVersion)
    }

    @Test
    fun `old renpy version comes from the runtime init when vc_version has only a stamp`() {
        val root = tempRoot()
        val game = File(root, "OldGame").apply { mkdirs() }
        File(game, "renpy").mkdirs()
        File(game, "game").mkdirs()
        File(game, "renpy/vc_version.py").writeText("vc_version = 22090809\n")
        File(game, "renpy/__init__.py").writeText("version_tuple = (7, 5, 3, vc_version)\n")

        val finding = scan(root).found.single()

        assertEquals("renpy", finding.engine)
        assertEquals("7.5.3", finding.engineVersion)
    }

    @Test
    fun `a native build with no known engine is listed with its platform`() {
        val root = tempRoot()
        val game = File(root, "Shelf/Space Port").apply { mkdirs() }
        SyntheticBinaries.file(File(game, "SpacePort.exe"), SyntheticBinaries.pe(0x8664), 2_000_000)
        File(game, "Data").mkdirs()
        // Next to it, a folder that is only an installer is not a game.
        val installers = File(root, "Shelf/Installers").apply { mkdirs() }
        SyntheticBinaries.file(File(installers, "setup.exe"), SyntheticBinaries.pe(0x8664), 40_000_000)

        val collector = scan(root)

        val finding = collector.found.single()
        assertEquals(canonical(game), canonical(File(finding.path)))
        assertNull(finding.engine)
        assertEquals(BuildPlatform.WINDOWS.bit, finding.platforms)
        assertEquals("SpacePort.exe", finding.launch)
    }

    @Test
    fun `a folder with no engine evidence finds nothing`() {
        val root = tempRoot()
        File(root, "documents").mkdirs()
        File(root, "documents/notes.txt").writeText("plain text")

        val collector = scan(root)

        assertTrue(collector.finished)
        assertTrue(collector.found.isEmpty())
    }

    // ---- Archives -------------------------------------------------------------------

    @Test
    fun `an archive that still needs unpacking is listed as one`() {
        val root = tempRoot()
        SyntheticBinaries.archive(File(root, "Downloads/Cool Game-1.0.zip").also { it.parentFile.mkdirs() }, ArchiveFiles.MIN_BYTES + 1)
        // Too small, and big but with no archive header behind its name: neither is a game archive.
        SyntheticBinaries.archive(File(root, "Downloads/patch.zip"), 1024)
        SyntheticBinaries.archive(File(root, "Downloads/movie.zip"), ArchiveFiles.MIN_BYTES + 1, magic = byteArrayOf(1, 2, 3, 4))

        val finding = scan(root).found.single()

        assertEquals(FindingKind.ARCHIVE, finding.kind)
        assertEquals("Cool Game-1.0", finding.name)
        assertEquals(BuildPlatform.ARCHIVE.bit, finding.platforms)
    }

    @Test
    fun `an archive beside the folder it unpacks to is not counted again`() {
        val root = tempRoot()
        godot(File(root, "Shelf/Cool Game-1.0"))
        SyntheticBinaries.archive(File(root, "Shelf/Cool Game-1.0.zip"), ArchiveFiles.MIN_BYTES + 1)
        // A different release's archive in the same folder is still listed.
        SyntheticBinaries.archive(File(root, "Shelf/Other Game.7z"), ArchiveFiles.MIN_BYTES + 1, magic = byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))

        val collector = scan(root)

        assertEquals(setOf(FindingKind.FOLDER, FindingKind.ARCHIVE), collector.found.map { it.kind }.toSet())
        assertEquals(setOf("Cool Game-1.0", "Other Game"), collector.found.map { it.name }.toSet())
        assertEquals(1, collector.found.count { it.kind == FindingKind.ARCHIVE })
    }

    // ---- Skipped places and limits ----------------------------------------------------

    @Test
    fun `hidden folders and the app data tree at the top of storage are skipped`() {
        val root = tempRoot()
        godot(File(root, ".hidden/Game"))
        godot(File(root, "Android/Game"))
        godot(File(root, "Shelf/Android/Game"))
        godot(File(root, "\$RECYCLE.BIN/Game"))

        val collector = scan(root)

        assertEquals(listOf(canonical(File(root, "Shelf/Android/Game"))), collector.folders())
    }

    @Test
    fun `cancel stops the walk and reports stopping early`() {
        val root = tempRoot()
        for (index in 0 until 50) File(root, "folder$index").mkdirs()
        val scanner = GameScanner(rows)
        scanner.cancel()
        val collector = scan(root, scanner)
        assertTrue(collector.finished)
        assertTrue(collector.stoppedEarly)
    }

    @Test
    fun `the directory cap stops the walk early`() {
        val root = tempRoot()
        for (index in 0 until 60) File(root, "folder$index/inner").mkdirs()
        val collector = scan(root, GameScanner(rows, maxDirectories = 10))
        assertTrue(collector.stoppedEarly)
        assertTrue(collector.lastExamined <= 10 + GameScanner.DEFAULT_WORKERS)
    }

    @Test
    fun `examined count covers every folder visited, including a found game root and an unreadable one`() {
        val root = tempRoot()
        // A game root: examined once, not descended into.
        val game = godot(File(root, "library/SomeGame"))
        // A plain folder with no engine evidence: examined, then descended into.
        val plain = File(root, "plain").apply { mkdirs() }
        File(plain, "notes.txt").writeText("just some notes")
        // A folder whose listing cannot be read: still examined, counted unreadable.
        // Model the failed listing directly: chmod-based tests are not reliable
        // when Gradle runs as root or on a filesystem without POSIX permissions.
        val locked = File(root, "locked").apply { mkdirs() }
        File(locked, "child").mkdirs()

        val collector = scan(
            root,
            GameScanner(rows, lister = { directory ->
                if (directory.canonicalFile == locked.canonicalFile) null else DirEntries.list(directory)
            }),
        )

        assertTrue(collector.finished)
        assertEquals(listOf(canonical(game)), collector.folders())
        // Every folder actually visited by the walk: root, library, library/SomeGame,
        // plain, locked. locked/child is never visited because locked could not be
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
        repeat(25) { index -> godot(File(root, "Game$index")) }

        val collector = scan(root)

        // root + 25 game roots = 26 folders examined; the 25th one hit is a
        // game root, so a correct implementation must still report there.
        assertTrue(
            "expected an onProgress(25, ...) call, got ${collector.progress}",
            collector.progress.any { (examined, _) -> examined == 25 },
        )
    }

    // ---- Scale: parallel, incremental, resumable ----------------------------------------

    @Test
    fun `a parallel walk of many folders finds every game exactly once`() {
        val root = tempRoot()
        val expected = mutableListOf<String>()
        for (genre in 0 until 8) {
            for (index in 0 until 10) {
                val folder = File(root, "genre$genre/series${index % 3}/Game${genre}_$index")
                if (index % 2 == 0) renpy(folder.apply { mkdirs() }) else godot(folder)
                expected += canonical(folder)
            }
        }

        val collector = scan(root, GameScanner(rows, workers = 3))

        assertEquals(expected.sorted(), collector.folders())
        assertEquals(80, collector.summary!!.found)
        assertFalse(collector.stoppedEarly)
    }

    @Test
    fun `a rescan announces nothing when nothing changed and only the changed folder when one did`() {
        val root = tempRoot()
        val games = (0 until 6).map { godot(File(root, "shelf/Game$it")) }
        val store = MemoryScanStore()

        val first = scan(root, GameScanner(rows, store = store))
        assertEquals(6, first.found.size)
        assertEquals(0, first.summary!!.unchanged)

        val second = scan(root, GameScanner(rows, store = store))
        assertTrue(second.found.isEmpty())
        assertEquals(6, second.summary!!.found)
        assertEquals(6, second.summary!!.unchanged)

        File(games[3], "extra.txt").writeText("a new file")
        val third = scan(root, GameScanner(rows, store = store))
        assertEquals(listOf(canonical(games[3])), third.folders())
        assertEquals(5, third.summary!!.unchanged)
    }

    @Test
    fun `a full rescan ignores what is cached`() {
        val root = tempRoot()
        repeat(3) { godot(File(root, "Game$it")) }
        val store = MemoryScanStore()
        scan(root, GameScanner(rows, store = store))

        val full = scan(root, GameScanner(rows, store = store), full = true)

        assertEquals(3, full.found.size)
        assertEquals(0, full.summary!!.unchanged)
    }

    @Test
    fun `folders that are not games are remembered as such while unchanged`() {
        val root = tempRoot()
        File(root, "tools").mkdirs()
        // One small program alone: looked at, not a game.
        SyntheticBinaries.file(File(root, "tools/one.exe"), SyntheticBinaries.pe(0x8664), 5_000)
        val store = MemoryScanStore()
        scan(root, GameScanner(rows, store = store))
        assertTrue(store.negatives.keys.any { it.endsWith("tools") })

        // The second walk sees the same signature, so the folder stays a known non-game.
        val again = scan(root, GameScanner(rows, store = store))
        assertTrue(again.found.isEmpty())
        assertTrue(store.negatives.keys.any { it.endsWith("tools") })
    }

    @Test
    fun `a game that is gone is forgotten by a complete rescan but kept by an interrupted one`() {
        val root = tempRoot()
        val keep = godot(File(root, "Keep"))
        val gone = godot(File(root, "Gone"))
        val store = MemoryScanStore()
        scan(root, GameScanner(rows, store = store))
        assertEquals(2, store.games.size)

        gone.deleteRecursively()
        val stopped = GameScanner(rows, store = store).also { it.cancel() }
        scan(root, stopped)
        assertEquals(2, store.games.size)

        val complete = scan(root, GameScanner(rows, store = store))
        assertFalse(complete.stoppedEarly)
        assertEquals(listOf(keep.path), store.games.keys.toList())
    }

    @Test
    fun `a stopped scan keeps what it had found`() {
        val root = tempRoot()
        repeat(5) { godot(File(root, "Game$it")) }
        val store = MemoryScanStore()

        // Stopped after the second find: what was found up to then is saved, not lost.
        val scanner = GameScanner(rows, store = store, workers = 1)
        val collector = Collector()
        scanner.scan(
            root,
            object : GameScanner.Listener {
                override fun onProgress(directoriesExamined: Int, found: Int) = Unit
                override fun onFound(finding: GameFinding) {
                    collector.found += finding
                    if (collector.found.size == 2) scanner.cancel()
                }

                override fun onFinished(summary: GameScanner.Summary) {
                    collector.summary = summary
                }
            },
        )

        assertTrue(collector.stoppedEarly)
        assertTrue(store.games.size >= 2)
    }
}
