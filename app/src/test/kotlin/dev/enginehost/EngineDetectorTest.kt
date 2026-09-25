package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Classification comes from the SHIPPED engines-database seed — the
 * same file droidtop bundles — so these tests hold the two apps to the
 * same answers: a registry edit that would make enginehost disagree
 * with droidtop's scan fails here before it ships.
 */
class EngineDetectorTest {
    private val rows = EngineRegistryParser.parse(
        SeedAssets.read("engines-database.json"),
    )

    private fun tempRoot(): File = createTempDir(prefix = "detect-test").also { it.deleteOnExit() }

    private fun detect(root: File): EngineDetection? = EngineDetector.detect(rows, root)

    @Test
    fun `Game ini RGSS line classifies the exact generation and version`() {
        val root = tempRoot()
        File(root, "Game.ini").writeText("[Game]\r\nLibrary=System\\RGSS301.dll\r\n")
        File(root, "Game.exe").writeBytes(byteArrayOf(0x4d, 0x5a))
        File(root, "Game.rgss3a").writeBytes(byteArrayOf(1))

        val detection = detect(root)!!
        assertEquals("rpgmaker", detection.engine)
        assertEquals("vxace", detection.engineContext)
        assertEquals("3.1", detection.engineVersion)
        assertEquals("Game.exe", detection.execFile)
        // The database row's declared prefill -- a prefill, not a pin:
        // mkxp-z ships vxace against both 1.9.2 and 3.1.3 and the config
        // editor keeps this editable.
        assertEquals(mapOf("ruby" to "1.9.2"), detection.runtimeRequirements)
    }

    @Test
    fun `an rgss2a archive alone classifies VX`() {
        val root = tempRoot()
        File(root, "Game.rgss2a").writeBytes(byteArrayOf(1))

        val detection = detect(root)!!
        assertEquals("rpgmaker", detection.engine)
        assertEquals("vx", detection.engineContext)
    }

    @Test
    fun `RPG Maker MZ core script yields version and index entry`() {
        val root = tempRoot()
        File(root, "js").mkdirs()
        File(root, "index.html").writeText("<html></html>")
        File(root, "js/rmmz_core.js").writeText("Utils.RPGMAKER_VERSION = \"1.6.0\";\n")

        val detection = detect(root)!!
        assertEquals("rpgmaker", detection.engine)
        assertEquals("mz", detection.engineContext)
        assertEquals("1.6.0", detection.engineVersion)
        assertEquals("index.html", detection.execFile)
    }

    @Test
    fun `an MV-MZ web runtime without its core script leaves the context open`() {
        val root = tempRoot()
        File(root, "js").mkdirs()
        File(root, "index.html").writeText("<html></html>")
        File(root, "js/main.js").writeText("// bootstrap")

        val detection = detect(root)!!
        assertEquals("rpgmaker", detection.engine)
        assertNull(detection.engineContext)
    }

    @Test
    fun `RPG_RT database needs its exe or map tree, and an unreadable database proves the family only`() {
        val root = tempRoot()
        File(root, "RPG_RT.ldb").writeBytes(byteArrayOf(1))
        assertNull(detect(root))
        File(root, "RPG_RT.lmt").writeBytes(byteArrayOf(1))
        val detection = detect(root)!!
        assertEquals("rpgmaker", detection.engine)
        assertNull(detection.engineContext)
    }

    @Test
    fun `RPG_RT database identifies RPG Maker 2003 from the canonical ldb id`() {
        val root = tempRoot()
        File(root, "RPG_RT.lmt").writeBytes(byteArrayOf(1))
        File(root, "RPG_RT.ldb").writeBytes(
            byteArrayOf(11) + "LcfDataBase".toByteArray() + byteArrayOf(
                0x16, 0x04, // System chunk, four payload bytes.
                0x0a, 0x02, 0x8f.toByte(), 0x53, // ldb_id = BER(2003).
                0,
            ),
        )

        val detection = detect(root)!!
        assertEquals("2003", detection.engineContext)
        assertEquals("2003", detection.engineVersion)
    }

    @Test
    fun `RPG_RT database without an ldb id identifies RPG Maker 2000`() {
        val root = tempRoot()
        File(root, "RPG_RT.lmt").writeBytes(byteArrayOf(1))
        File(root, "RPG_RT.ldb").writeBytes(
            byteArrayOf(11) + "LcfDataBase".toByteArray() + byteArrayOf(
                0x16, 0x01, 0, // Empty System chunk: RPG Maker 2000.
                0,
            ),
        )

        val detection = detect(root)!!
        assertEquals("2000", detection.engineContext)
        assertEquals("2000", detection.engineVersion)
    }

    @Test
    fun `renpy version falls back to the runtime version_tuple`() {
        val root = tempRoot()
        File(root, "renpy").mkdirs()
        File(root, "game").mkdirs()
        File(root, "renpy/vc_version.py").writeText("vc_version = 22090809\n")
        File(root, "renpy/__init__.py").writeText("version_tuple = (7, 5, 3, vc_version)\n")

        val detection = detect(root)!!
        assertEquals("renpy", detection.engine)
        assertEquals("standard", detection.engineContext)
        assertEquals("7.5.3", detection.engineVersion)
    }

    @Test
    fun `compiled-only renpy is caught by the registry fallback row`() {
        val root = tempRoot()
        File(root, "game").mkdirs()
        File(root, "game/archive.rpa").writeBytes(byteArrayOf(1))

        val detection = detect(root)!!
        assertEquals("renpy", detection.engine)
        assertEquals("Found compiled Ren'Py game files", detection.evidence)
    }

    @Test
    fun `a wrapped distribution is found one level down, name-ordered`() {
        val root = tempRoot()
        val inner = File(root, "SomeVN-1.2-pc").apply { mkdirs() }
        File(inner, "renpy").mkdirs()
        File(inner, "game").mkdirs()

        val detection = detect(root)!!
        assertEquals("renpy", detection.engine)
    }

    @Test
    fun `kirikiri detects from startup tjs with no archive`() {
        val root = tempRoot()
        File(root, "startup.tjs").writeText(";startup")

        assertEquals("kirikiri2", detect(root)!!.engine)
    }

    @Test
    fun `an xp3 archive beats a stray swf asset because file order is the only precedence`() {
        val root = tempRoot()
        File(root, "data.xp3").writeBytes(byteArrayOf(1))
        File(root, "intro.swf").writeBytes("FWS".toByteArray() + byteArrayOf(9))

        assertEquals("kirikiri2", detect(root)!!.engine)
    }

    @Test
    fun `a bare swf classifies through the enginehost-only flash row`() {
        val root = tempRoot()
        File(root, "movie.swf").writeBytes("CWS".toByteArray() + byteArrayOf(7))

        val detection = detect(root)!!
        assertEquals("flash_air", detection.engine)
        assertEquals("swf", detection.engineContext)
        assertEquals("7.0", detection.engineVersion)
        assertEquals("movie.swf", detection.execFile)
    }

    @Test
    fun `a python 2 renpy build writes its version with a u prefix`() {
        val root = tempRoot()
        File(root, "renpy").mkdirs()
        File(root, "renpy/vc_version.py").writeText("version = u'7.6.3.23091805'\nofficial = True\n")
        File(root, "game").mkdirs()
        File(root, "game/script.rpyc").writeBytes(byteArrayOf(1))

        val detection = detect(root)!!
        assertEquals("renpy", detection.engine)
        assertEquals("7.6.3.23091805", detection.engineVersion)
    }

    @Test
    fun `a godot 3 pack is named with its version even though nothing runs it`() {
        val root = tempRoot()
        val header = java.nio.ByteBuffer.allocate(88).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("GDPC".toByteArray(Charsets.US_ASCII))
        header.putInt(1)
        header.putInt(3)
        header.putInt(5)
        header.putInt(3)
        File(root, "game.pck").writeBytes(header.array())

        val detection = detect(root)!!
        assertEquals("godot", detection.engine)
        assertEquals("3.5.3", detection.engineVersion)
    }

    @Test
    fun `a cmvs script names its generation`() {
        val root = tempRoot()
        File(root, "scene.ps3").writeBytes(byteArrayOf(1))

        val detection = detect(root)!!
        assertEquals("cmvs", detection.engine)
        assertEquals("ps3", detection.engineContext)
        assertEquals("scene.ps3", detection.execFile)
    }

    @Test
    fun `godot pack header carries the engine version`() {
        val root = tempRoot()
        val header = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("GDPC".toByteArray(Charsets.US_ASCII))
        header.putInt(2)
        header.putInt(4)
        header.putInt(5)
        header.putInt(1)
        File(root, "game.pck").writeBytes(header.array())

        val detection = detect(root)!!
        assertEquals("godot", detection.engine)
        assertEquals("4.5.1", detection.engineVersion)
    }

    @Test
    fun `a windows export keeps its pack in a pe section named pck`() {
        val root = tempRoot()
        // DOS header (0x40) -> PE signature at 0x40 -> COFF (20) -> empty optional header -> one section header.
        val packAt = 0x200
        val exe = java.nio.ByteBuffer.allocate(packAt + 40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        exe.put(0, 'M'.code.toByte()); exe.put(1, 'Z'.code.toByte())
        exe.putInt(0x3C, 0x40)
        exe.putInt(0x40, 0x4550)
        exe.putShort(0x40 + 4 + 2, 1)
        exe.putShort(0x40 + 4 + 16, 2)
        exe.putShort(0x40 + 24, 0x10b)
        val section = 0x40 + 24 + 2
        exe.put(section, "pck".toByteArray(Charsets.US_ASCII))
        exe.putInt(section + 16, 40)
        exe.putInt(section + 20, packAt)
        exe.position(packAt)
        exe.put("GDPC".toByteArray(Charsets.US_ASCII))
        exe.putInt(2); exe.putInt(4); exe.putInt(4); exe.putInt(1)
        File(root, "Another girl.exe").writeBytes(exe.array())

        val detection = detect(root)!!
        assertEquals("godot", detection.engine)
        assertEquals("4.4.1", detection.engineVersion)
    }

    /** The smallest PE image [PeImage] accepts: DOS header, COFF header with [machine], a PE32+ magic, one section. */
    private fun pe(machine: Int, tail: ByteArray = ByteArray(0)): ByteArray {
        val image = ByteBuffer.allocate(0x58 + 2 + 40).order(ByteOrder.LITTLE_ENDIAN)
        image.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte())
        image.putInt(0x3C, 0x40)
        image.putInt(0x40, 0x4550)
        image.putShort(0x44, machine.toShort())
        image.putShort(0x46, 1)
        image.putShort(0x54, 2)
        image.putShort(0x58, 0x20b)
        return image.array() + tail
    }

    private fun unityGame(root: File, backend: String, machine: Int) {
        File(root, "MyGame_Data").mkdirs()
        if (backend == "il2cpp") {
            File(root, "MyGame_Data/il2cpp_data/Metadata").mkdirs()
            File(root, "MyGame_Data/il2cpp_data/Metadata/global-metadata.dat").writeBytes(byteArrayOf(1))
            File(root, "GameAssembly.dll").writeBytes(pe(machine))
        } else {
            File(root, "MyGame_Data/Managed").mkdirs()
            File(root, "MyGame_Data/Managed/Assembly-CSharp.dll").writeBytes(byteArrayOf(1))
        }
        // A serialized file's header: sizes and offsets, then the Unity version as a C string.
        File(root, "MyGame_Data/globalgamemanagers").writeBytes(ByteArray(20) + "2021.3.16f1".toByteArray() + ByteArray(1))
        File(root, "UnityPlayer.dll").writeBytes(pe(machine))
    }

    @Test
    fun `an IL2CPP unity game is recognised as unhosted with its version and machine`() {
        val root = tempRoot()
        unityGame(root, "il2cpp", 0x8664)

        val detection = detect(root)!!
        assertEquals("unity", detection.engine)
        assertEquals("il2cpp", detection.engineContext)
        assertEquals("2021.3.16f1", detection.engineVersion)
        assertEquals("x86_64", detection.architecture)
        assertFalse(detection.hosted)
    }

    @Test
    fun `a mono unity game on a 32-bit player says so`() {
        val root = tempRoot()
        unityGame(root, "mono", 0x014c)

        val detection = detect(root)!!
        assertEquals("mono", detection.engineContext)
        assertEquals("x86", detection.architecture)
    }

    /** An AGS main game data file: its signature, data format 60 (3.6), and the compiling editor's version. */
    private fun agsGameData(version: String): ByteArray {
        val fields = ByteBuffer.allocate(8 + version.length).order(ByteOrder.LITTLE_ENDIAN)
        fields.putInt(60).putInt(version.length).put(version.toByteArray(Charsets.US_ASCII))
        return "Adventure Creator Game File v2".toByteArray(Charsets.US_ASCII) + fields.array()
    }

    @Test
    fun `an ags data file names the editor that compiled it`() {
        val root = tempRoot()
        File(root, "acsetup.cfg").writeText("[misc]\n")
        File(root, "game.ags").writeBytes("CLIB\u001a".toByteArray(Charsets.US_ASCII) + ByteArray(64) + agsGameData("3.6.1.14"))

        val detection = detect(root)!!
        assertEquals("ags", detection.engine)
        assertEquals("3.6.1.14", detection.engineVersion)
        assertEquals("game.ags", detection.execFile)
    }

    @Test
    fun `an older ags game carries its data inside the exe, found from the tail`() {
        val root = tempRoot()
        File(root, "acsetup.cfg").writeText("[misc]\n")
        File(root, "winsetup.exe").writeBytes(pe(0x014c))
        val engine = pe(0x014c, ByteArray(200))
        val library = "CLIB\u001a".toByteArray(Charsets.US_ASCII) + ByteArray(32) + agsGameData("3.2.1")
        val offset = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(engine.size.toLong()).array()
        File(root, "Adventure.exe").writeBytes(
            engine + library + offset + "CLIB\u0001\u0002\u0003\u0004SIGE".toByteArray(Charsets.US_ASCII),
        )

        val detection = detect(root)!!
        assertEquals("ags", detection.engine)
        assertEquals("3.2.1", detection.engineVersion)
        assertEquals("Adventure.exe", detection.execFile)
    }

    @Test
    fun `an unpacked love game states its version in conf lua`() {
        val root = tempRoot()
        File(root, "main.lua").writeText("function love.draw() end\n")
        File(root, "conf.lua").writeText("function love.conf(t)\n    t.identity = \"mygame\"\n    t.version = \"11.4\"\nend\n")

        val detection = detect(root)!!
        assertEquals("love2d", detection.engine)
        assertEquals("11.4", detection.engineVersion)
        assertNull(detection.execFile)
    }

    @Test
    fun `a fused love game is read from the zip appended to its exe`() {
        val root = tempRoot()
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("main.lua"))
            out.write("function love.draw() end\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("conf.lua"))
            out.write("function love.conf(c)\n  c.window.title = \"x\"\n  c.version = \"11.5\"\nend\n".toByteArray())
            out.closeEntry()
        }
        File(root, "love.dll").writeBytes(pe(0x8664))
        File(root, "lua51.dll").writeBytes(pe(0x8664))
        File(root, "MyGame.exe").writeBytes(pe(0x8664, ByteArray(300)) + zip.toByteArray())

        val detection = detect(root)!!
        assertEquals("love2d", detection.engine)
        assertEquals("11.5", detection.engineVersion)
        assertEquals("MyGame.exe", detection.execFile)
    }

    @Test
    fun `a gamemaker data file carries its bytecode version`() {
        val root = tempRoot()
        val data = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        data.put("FORM".toByteArray(Charsets.US_ASCII)).putInt(88)
        data.put("GEN8".toByteArray(Charsets.US_ASCII)).putInt(80)
        data.put(16 + 1, 17)
        data.putInt(16 + 44, 2)
        File(root, "data.win").writeBytes(data.array())

        val detection = detect(root)!!
        assertEquals("gamemaker", detection.engine)
        assertEquals("2.0.0.0", detection.engineVersion)
        assertEquals("data.win: GameMaker bytecode 17, IDE 2.0.0.0", detection.evidence)
        assertFalse(detection.hosted)
    }

    @Test
    fun `a compiled-only renpy build states its version in script_version`() {
        val root = tempRoot()
        File(root, "game").mkdirs()
        File(root, "game/archive.rpa").writeBytes(byteArrayOf(1))
        File(root, "game/script_version.txt").writeText("(7, 4, 11)")

        val detection = detect(root)!!
        assertEquals("renpy", detection.engine)
        assertEquals("7.4.11", detection.engineVersion)
    }

    @Test
    fun `a godot 3 project file names its major line`() {
        val root = tempRoot()
        File(root, "project.godot").writeText("config_version=4\n\n[application]\nconfig/name=\"x\"\n")

        val detection = detect(root)!!
        assertEquals("godot", detection.engine)
        assertEquals("3", detection.engineVersion)
    }

    @Test
    fun `no evidence means no detection`() {
        val root = tempRoot()
        File(root, "readme.txt").writeText("nothing here")

        assertNull(detect(root))
    }
}
