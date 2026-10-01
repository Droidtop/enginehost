package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Detection of one folder against the SHIPPED registry seed (the same file
 * droidtop classifies with), with synthetic folders and byte headers: no
 * real game or program file is used.
 */
class FolderAnalyzerTest {
    private val rows = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))
    private val analyzer = FolderAnalyzer(rows)

    private fun tempRoot(): File = createTempDir(prefix = "analyze-test").also { it.deleteOnExit() }

    private fun analyze(folder: File): GameFinding? =
        analyzer.analyze(folder, DirEntries.list(folder).orEmpty(), 0L)

    private fun unity(folder: File, machine: Int = 0x8664) {
        File(folder, "Game_Data/Managed").mkdirs()
        File(folder, "Game_Data/Managed/Assembly-CSharp.dll").writeBytes(byteArrayOf(1))
        File(folder, "Game_Data/globalgamemanagers").writeBytes(ByteArray(20) + "2021.3.16f1".toByteArray() + ByteArray(1))
        SyntheticBinaries.file(File(folder, "UnityPlayer.dll"), SyntheticBinaries.pe(machine), 1_000_000)
        SyntheticBinaries.file(File(folder, "Game.exe"), SyntheticBinaries.pe(machine), 700_000)
    }

    // ---- Engine markers, with the platform layer around them ------------------------

    @Test
    fun `a unity windows build is an unhosted engine with its machine and launch file`() {
        val folder = File(tempRoot(), "Adventure").apply { mkdirs() }
        unity(folder)

        val finding = analyze(folder)!!
        assertEquals("unity", finding.engine)
        assertFalse(finding.hosted)
        assertEquals(BuildPlatform.WINDOWS.bit, finding.platforms)
        assertEquals("x86_64", finding.architecture)
        assertEquals("Game.exe", finding.launch)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertEquals("2021.3.16f1", finding.engineVersion)
    }

    @Test
    fun `a unity folder that also ships a linux player carries both platform bits`() {
        val folder = File(tempRoot(), "Adventure").apply { mkdirs() }
        unity(folder)
        SyntheticBinaries.file(File(folder, "Game.x86_64"), SyntheticBinaries.elf(62), 700_000)

        val finding = analyze(folder)!!
        assertEquals(BuildPlatform.mask(BuildPlatform.WINDOWS, BuildPlatform.LINUX), finding.platforms)
    }

    @Test
    fun `a plain wrapper folder is not a game but the game inside it is`() {
        val wrapper = File(tempRoot(), "Adventure-1.0").apply { mkdirs() }
        val inner = File(wrapper, "Inner").apply { mkdirs() }
        unity(inner)

        assertNull(analyze(wrapper))
        assertEquals("unity", analyze(inner)!!.engine)
    }

    @Test
    fun `a wrapper that the registry sees through still names the builds one folder down`() {
        val wrapper = File(tempRoot(), "Adventure-1.0").apply { mkdirs() }
        File(wrapper, "readme.html").writeText("<html></html>")
        unity(File(wrapper, "Inner").apply { mkdirs() })

        val finding = analyze(wrapper)!!
        assertEquals("unity", finding.engine)
        assertEquals(BuildPlatform.WINDOWS.bit, finding.platforms)
        assertEquals("Inner/Game.exe", finding.launch)
    }

    @Test
    fun `an engine data game is android runnable whatever launcher executable sits beside it`() {
        val folder = File(tempRoot(), "Visual Story").apply { mkdirs() }
        File(folder, "renpy").mkdirs()
        File(folder, "game").mkdirs()
        SyntheticBinaries.file(File(folder, "Story.exe"), SyntheticBinaries.pe(0x8664), 400_000)
        SyntheticBinaries.file(File(folder, "Story.sh"), SyntheticBinaries.script())

        val finding = analyze(folder)!!
        assertEquals("renpy", finding.engine)
        assertTrue(finding.hosted)
        assertEquals(BuildPlatform.PORTABLE.bit, finding.platforms)
        assertEquals(Confidence.HIGH, finding.confidence)
    }

    @Test
    fun `rpg maker xp vx vxace mv and mz markers classify through the registry`() {
        fun folder(vararg files: String): File = File(tempRoot(), "G").apply {
            mkdirs()
            files.forEach { path -> File(this, path).apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1)) }
        }
        assertEquals("xp", analyze(folder("Game.rgssad"))!!.engineContext)
        assertEquals("vx", analyze(folder("Game.rgss2a"))!!.engineContext)
        assertEquals("vxace", analyze(folder("Game.rgss3a"))!!.engineContext)
        assertEquals("mv", analyze(folder("www/js/rpg_core.js"))!!.engineContext)
        assertEquals("mz", analyze(folder("js/rmmz_core.js"))!!.engineContext)
    }

    @Test
    fun `kirikiri godot flash and nwjs markers classify`() {
        fun folder(vararg files: String): File = File(tempRoot(), "G").apply {
            mkdirs()
            files.forEach { path -> File(this, path).apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1)) }
        }
        assertEquals("kirikiri2", analyze(folder("data.xp3"))!!.engine)
        assertEquals("godot", analyze(folder("project.godot"))!!.engine)
        assertEquals("nwjs-electron", analyze(folder("package.nw"))!!.engine)
        assertEquals("gamemaker", analyze(folder("data.win"))!!.engine)
        assertEquals("unreal", analyze(folder("Engine/Binaries/x"))!!.engine)
        val swf = File(tempRoot(), "G").apply { mkdirs() }
        File(swf, "game.swf").writeBytes("FWS".toByteArray() + byteArrayOf(9))
        assertEquals("flash_air", analyze(swf)!!.engine)
    }

    @Test
    fun `a bare html page is a weak find and a twine story is a strong one`() {
        val page = File(tempRoot(), "Page").apply { mkdirs() }
        File(page, "index.html").writeText("<html><body>hello</body></html>")
        val weak = analyze(page)!!
        assertEquals("html", weak.engine)
        assertEquals(Confidence.LOW, weak.confidence)
        assertEquals(BuildPlatform.mask(BuildPlatform.PORTABLE, BuildPlatform.WEB), weak.platforms)

        val story = File(tempRoot(), "Story").apply { mkdirs() }
        File(story, "index.html").writeText("<tw-storydata name=\"The Tale\" format=\"SugarCube\" format-version=\"2.36.1\"></tw-storydata>")
        val strong = analyze(story)!!
        assertEquals(Confidence.HIGH, strong.confidence)
        assertEquals("The Tale", strong.name)
    }

    // ---- Executables the registry does not know ------------------------------------

    @Test
    fun `a native windows build with no known engine is still a game`() {
        val folder = File(tempRoot(), "Adventure Land").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "AdventureLand.exe"), SyntheticBinaries.pe(0x8664, dotNet = true), 1_000_000)
        File(folder, "Data").mkdirs()

        val finding = analyze(folder)!!
        assertNull(finding.engine)
        assertFalse(finding.hosted)
        assertEquals(BuildPlatform.WINDOWS.bit, finding.platforms)
        assertEquals("AdventureLand.exe", finding.launch)
        assertTrue(finding.dotNet)
        assertEquals(Confidence.MEDIUM, finding.confidence)
    }

    @Test
    fun `a linux appimage with data beside it is a linux game`() {
        val folder = File(tempRoot(), "Space Port").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "SpacePort.AppImage"), SyntheticBinaries.elf(62, appImage = true), 6_000_000)
        File(folder, "saves").mkdirs()

        val finding = analyze(folder)!!
        assertEquals(BuildPlatform.LINUX.bit, finding.platforms)
        assertEquals("SpacePort.AppImage", finding.launch)
    }

    @Test
    fun `one small program alone is not a game`() {
        val folder = File(tempRoot(), "Misc").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "tool.exe"), SyntheticBinaries.pe(0x8664), 10_000)

        assertNull(analyze(folder))
    }

    @Test
    fun `a folder of many unrelated programs is a tools folder`() {
        val folder = File(tempRoot(), "Utilities").apply { mkdirs() }
        for (index in 0 until 10) {
            SyntheticBinaries.file(File(folder, "util$index.exe"), SyntheticBinaries.pe(0x8664), 300_000)
        }
        assertNull(analyze(folder))
    }

    @Test
    fun `installers alone never make a game`() {
        val folder = File(tempRoot(), "Downloads").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "setup.exe"), SyntheticBinaries.pe(0x8664), 50_000_000)
        SyntheticBinaries.file(File(folder, "vcredist_x64.exe"), SyntheticBinaries.pe(0x8664), 5_000_000)
        File(folder, "notes").mkdirs()
        File(folder, "extras").mkdirs()

        assertNull(analyze(folder))
    }

    @Test
    fun `a folder with no executables and no engine evidence finds nothing`() {
        val folder = File(tempRoot(), "Documents").apply { mkdirs() }
        File(folder, "notes.txt").writeText("plain")
        assertNull(analyze(folder))
    }

    // ---- Screening ------------------------------------------------------------------

    @Test
    fun `screening is derived from the registry so every rule can still be reached`() {
        val screen = RegistryScreen(rows)
        fun file(name: String, size: Long = 10) = DirEntry(name, false, size, 0)
        fun dir(name: String) = DirEntry(name, true, 0, 0)
        assertTrue(screen.matches(listOf(dir("renpy"))))
        assertTrue(screen.matches(listOf(file("RPG_RT.ldb"))))
        assertTrue(screen.matches(listOf(file("DATA.XP3"))))
        assertTrue(screen.matches(listOf(file("Game.exe"))))
        assertTrue(screen.matches(listOf(file("index.html"))))
        assertTrue(screen.matches(listOf(dir("MyGame_Data"))))
        assertTrue(screen.matches(listOf(file("Game", size = 5_000_000))))
        assertFalse(screen.matches(listOf(file("Game", size = 100))))
        assertFalse(screen.matches(listOf(file("notes.txt"), dir("photos"))))
    }

    @Test
    fun `the signature salt changes with the rule set so a registry update rescans`() {
        assertNotEquals(FolderAnalyzer(rows).salt, FolderAnalyzer(rows.drop(1)).salt)
        assertEquals(FolderAnalyzer(rows).salt, FolderAnalyzer(rows).salt)
    }

    @Test
    fun `a directory signature ignores listing order and notices a changed file`() {
        val a = DirEntry("a", false, 10, 100)
        val b = DirEntry("b", true, 0, 200)
        assertEquals(DirEntries.signature(listOf(a, b), 1), DirEntries.signature(listOf(b, a), 1))
        assertNotEquals(DirEntries.signature(listOf(a, b), 1), DirEntries.signature(listOf(a.copy(size = 11), b), 1))
        assertNotEquals(DirEntries.signature(listOf(a, b), 1), DirEntries.signature(listOf(a, b.copy(modified = 201)), 1))
        assertNotEquals(DirEntries.signature(listOf(a, b), 1), DirEntries.signature(listOf(a), 1))
        assertNotEquals(DirEntries.signature(listOf(a, b), 1), DirEntries.signature(listOf(a, b), 2))
        assertNotNull(DirEntries.list(tempRoot()))
    }
}
