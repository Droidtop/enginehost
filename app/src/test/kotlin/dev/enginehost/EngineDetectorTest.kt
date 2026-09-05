package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Classification comes from the SHIPPED engines-database seed — the
 * same file droidtop bundles — so these tests hold the two apps to the
 * same answers: a registry edit that would make enginehost disagree
 * with droidtop's scan fails here before it ships.
 */
class EngineDetectorTest {
    private val rows = EngineRegistryParser.parse(
        File("src/main/assets/engines-database.json").readText(),
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
    fun `unity is recognized but flagged as unhosted, with its scripting backend`() {
        val root = tempRoot()
        File(root, "MyGame_Data/il2cpp_data").mkdirs()
        File(root, "MyGame_Data/il2cpp_data/meta.dat").writeBytes(byteArrayOf(1))
        File(root, "UnityPlayer.dll").writeBytes(byteArrayOf(0x4d, 0x5a))

        val detection = detect(root)!!
        assertEquals("unity", detection.engine)
        assertEquals("il2cpp", detection.engineContext)
    }

    @Test
    fun `no evidence means no detection`() {
        val root = tempRoot()
        File(root, "readme.txt").writeText("nothing here")

        assertNull(detect(root))
    }
}
