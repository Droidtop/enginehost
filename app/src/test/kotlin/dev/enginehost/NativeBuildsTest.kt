package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeBuildsTest {
    private fun tempRoot(): File = createTempDir(prefix = "native-test").also { it.deleteOnExit() }

    private fun probe(bytes: ByteArray, name: String = "x"): BinaryInfo? = ExecutableProbe.probe(ByteArrayReadAt(bytes), name)

    private fun read(folder: File): NativeBuild =
        NativeBuilds.read(folder, DirEntries.list(folder).orEmpty(), folder.name)

    // ---- Header sniffing ----------------------------------------------------------

    @Test
    fun `a 64-bit PE gui program names its machine`() {
        val info = probe(SyntheticBinaries.pe(0x8664))!!
        assertEquals(BinaryKind.PE, info.kind)
        assertEquals("x86_64", info.architecture)
        assertTrue(info.gui)
        assertFalse(info.dotNet)
    }

    @Test
    fun `a 32-bit PE32 image is read through its own optional header size`() {
        val info = probe(SyntheticBinaries.pe(0x014c, pe32Plus = false, console = true))!!
        assertEquals("x86", info.architecture)
        assertFalse(info.gui)
    }

    @Test
    fun `a CLR header makes a PE image managed code`() {
        assertTrue(probe(SyntheticBinaries.pe(0x014c, pe32Plus = false, dotNet = true))!!.dotNet)
        assertTrue(probe(SyntheticBinaries.pe(0x8664, dotNet = true))!!.dotNet)
        assertFalse(probe(SyntheticBinaries.pe(0x8664))!!.dotNet)
    }

    @Test
    fun `an MZ file with no PE header is a DOS program`() {
        val info = probe(SyntheticBinaries.dos())!!
        assertEquals(BinaryKind.PE, info.kind)
        assertEquals("dos", info.architecture)
    }

    @Test
    fun `ELF binaries name their machine and shared libraries are not programs`() {
        assertEquals("x86_64", probe(SyntheticBinaries.elf(62))!!.architecture)
        assertEquals("arm64", probe(SyntheticBinaries.elf(183, type = 3))!!.architecture)
        assertEquals("x86", probe(SyntheticBinaries.elf(3))!!.architecture)
        // ET_REL: an object file, not something to start.
        assertNull(probe(SyntheticBinaries.elf(62, type = 1)))
    }

    @Test
    fun `an AppImage is an ELF with its own marker`() {
        val info = probe(SyntheticBinaries.elf(62, appImage = true))!!
        assertEquals(BinaryKind.APPIMAGE, info.kind)
        assertEquals("x86_64", info.architecture)
    }

    @Test
    fun `scripts are recognised by a shebang or a dot sh name over text`() {
        assertEquals(BinaryKind.SCRIPT, probe(SyntheticBinaries.script(), "start")!!.kind)
        assertEquals(BinaryKind.SCRIPT, probe("echo hi\n".toByteArray(), "start.sh")!!.kind)
        assertNull(probe("echo hi\n".toByteArray(), "notes.txt"))
        // A .sh name over binary garbage is not a script.
        assertNull(probe(ByteArray(64) { 1 }, "start.sh"))
    }

    @Test
    fun `short and unknown files answer null instead of throwing`() {
        assertNull(probe(ByteArray(0)))
        assertNull(probe(byteArrayOf('M'.code.toByte())))
        assertNull(probe(ByteArray(100) { 7 }))
    }

    // ---- Launch candidates --------------------------------------------------------

    @Test
    fun `installers redistributables crash handlers and uninstallers are never the launch file`() {
        val folder = File(tempRoot(), "Some Game").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "SomeGame.exe"), SyntheticBinaries.pe(0x8664), 3_000_000)
        for (name in listOf("unins000.exe", "vcredist_x86.exe", "UnityCrashHandler64.exe", "setup.exe", "DXSetup.exe", "dotnetfx35.exe", "notification_helper.exe")) {
            SyntheticBinaries.file(File(folder, name), SyntheticBinaries.pe(0x8664), 9_000_000)
        }

        val build = read(folder)
        assertEquals("SomeGame.exe", build.launch)
        assertEquals(1, build.executables)
        assertEquals(BuildPlatform.WINDOWS.bit, build.platforms)
    }

    @Test
    fun `the executable named like the folder outranks launchers and configuration tools`() {
        val folder = File(tempRoot(), "Space Quest").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "Launcher.exe"), SyntheticBinaries.pe(0x8664), 8_000_000)
        SyntheticBinaries.file(File(folder, "config.exe"), SyntheticBinaries.pe(0x8664), 8_000_000)
        SyntheticBinaries.file(File(folder, "SpaceQuest.exe"), SyntheticBinaries.pe(0x8664), 500_000)

        val build = read(folder)
        assertEquals("SpaceQuest.exe", build.launch)
        assertTrue(build.matchesFolder)
    }

    @Test
    fun `windows and linux builds side by side set both platform bits and the best launcher wins`() {
        val folder = File(tempRoot(), "Both").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "Both.exe"), SyntheticBinaries.pe(0x8664), 2_000_000)
        SyntheticBinaries.file(File(folder, "Both.x86_64"), SyntheticBinaries.elf(62), 2_000_000)
        SyntheticBinaries.file(File(folder, "run.sh"), SyntheticBinaries.script())

        val build = read(folder)
        assertEquals(BuildPlatform.mask(BuildPlatform.WINDOWS, BuildPlatform.LINUX), build.platforms)
        assertNotNull(build.launch)
    }

    @Test
    fun `an extensionless ELF binary counts once it is big enough to be one`() {
        val folder = File(tempRoot(), "Native").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "Native"), SyntheticBinaries.elf(183), 5_000_000)
        SyntheticBinaries.file(File(folder, "LICENSE"), "plain text".toByteArray())

        val build = read(folder)
        assertEquals("Native", build.launch)
        assertEquals("arm64", build.architecture)
        assertEquals(BuildPlatform.LINUX.bit, build.platforms)
    }

    @Test
    fun `a build in a conventional subfolder is found when the root has none`() {
        val folder = File(tempRoot(), "Wrapped").apply { mkdirs() }
        SyntheticBinaries.file(File(folder, "bin/Wrapped.exe"), SyntheticBinaries.pe(0x014c, pe32Plus = false), 2_000_000)
        // A redistributable folder is never searched.
        SyntheticBinaries.file(File(folder, "redist/other.exe"), SyntheticBinaries.pe(0x8664), 2_000_000)

        val build = read(folder)
        assertEquals("bin/Wrapped.exe", build.launch)
        assertEquals("x86", build.architecture)
    }

    @Test
    fun `a folder with no executables has no build`() {
        val folder = File(tempRoot(), "Docs").apply { mkdirs() }
        File(folder, "readme.txt").writeText("hello")
        assertEquals(0, read(folder).platforms)
    }

    // ---- Archives -----------------------------------------------------------------

    @Test
    fun `an archive is a candidate only when it is big enough and the first volume`() {
        val big = ArchiveFiles.MIN_BYTES + 1
        assertTrue(ArchiveFiles.candidate(DirEntry("Game.zip", false, big, 0)))
        assertTrue(ArchiveFiles.candidate(DirEntry("Game.7z", false, big, 0)))
        assertTrue(ArchiveFiles.candidate(DirEntry("Game.tar.gz", false, big, 0)))
        assertTrue(ArchiveFiles.candidate(DirEntry("Game.part1.rar", false, big, 0)))
        assertFalse(ArchiveFiles.candidate(DirEntry("Game.part2.rar", false, big, 0)))
        assertFalse(ArchiveFiles.candidate(DirEntry("Game.r01", false, big, 0)))
        assertFalse(ArchiveFiles.candidate(DirEntry("Game.zip", false, 1024, 0)))
        assertFalse(ArchiveFiles.candidate(DirEntry("Game.exe", false, big, 0)))
        assertFalse(ArchiveFiles.candidate(DirEntry("Game.zip", true, big, 0)))
    }

    @Test
    fun `archive magic is read from the first bytes and a tar from its ustar field`() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        assertTrue(ArchiveFiles.hasMagic(ByteArrayReadAt(zip + ByteArray(8))))
        assertTrue(ArchiveFiles.hasMagic(ByteArrayReadAt(byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 0))))
        assertTrue(ArchiveFiles.hasMagic(ByteArrayReadAt("Rar!".toByteArray() + byteArrayOf(0x1a, 7, 0, 0))))
        val tar = ByteArray(300).also { "ustar".toByteArray().copyInto(it, 257) }
        assertTrue(ArchiveFiles.hasMagic(ByteArrayReadAt(tar)))
        assertFalse(ArchiveFiles.hasMagic(ByteArrayReadAt(ByteArray(300) { 9 })))
    }

    @Test
    fun `a release key ignores case punctuation and platform tags`() {
        assertEquals(ArchiveFiles.releaseKey("Cool Game-1.2"), ArchiveFiles.releaseKey("cool_game 1.2"))
        assertEquals(ArchiveFiles.releaseKey("CoolGame"), ArchiveFiles.releaseKey("CoolGame-win64"))
        assertEquals("Cool Game", ArchiveFiles.stem("Cool Game.tar.gz"))
        assertEquals("Cool Game", ArchiveFiles.stem("Cool Game.7z.001"))
        assertEquals("Cool Game", ArchiveFiles.stem("Cool Game.part1.rar"))
    }
}
