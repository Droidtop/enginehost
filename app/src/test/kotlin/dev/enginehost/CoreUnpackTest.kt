package dev.enginehost

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class CoreUnpackTest {
    private fun tarGz(vararg entries: TarArchiveEntry, contents: Map<String, ByteArray> = emptyMap()): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            entries.forEach { entry ->
                val data = contents[entry.name]
                if (data != null) entry.size = data.size.toLong()
                tar.putArchiveEntry(entry)
                data?.let(tar::write)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun folder(): File = Files.createTempDirectory("unpack").toFile()

    @Test
    fun `files and folders unpack where they say`() {
        val into = folder()
        val archive = tarGz(
            TarArchiveEntry("lib/"), TarArchiveEntry("lib/python3.12/"), TarArchiveEntry("main.py"),
            TarArchiveEntry("lib/python3.12/os.pyc"),
            contents = mapOf("main.py" to "print(1)".toByteArray(), "lib/python3.12/os.pyc" to byteArrayOf(1, 2, 3)),
        )
        CoreUnpacker.unpackTarGz(ByteArrayInputStream(archive), into)
        assertEquals("print(1)", File(into, "main.py").readText())
        assertEquals(3L, File(into, "lib/python3.12/os.pyc").length())
        assertTrue(File(into, "lib/python3.12").isDirectory)
    }

    @Test
    fun `a path out of the folder refuses the archive`() {
        val archive = tarGz(TarArchiveEntry("../escaped"), contents = mapOf("../escaped" to byteArrayOf(1)))
        val into = folder()
        assertThrows(IllegalArgumentException::class.java) { CoreUnpacker.unpackTarGz(ByteArrayInputStream(archive), into) }
        assertFalse(File(into.parentFile, "escaped").exists())
    }

    @Test
    fun `a link refuses the archive`() {
        val link = TarArchiveEntry("evil", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/data/data" }
        assertThrows(IllegalArgumentException::class.java) {
            CoreUnpacker.unpackTarGz(ByteArrayInputStream(tarGz(link)), folder())
        }
    }

    @Test
    fun `the spec takes only paths inside the bundle and a one-line stamp`() {
        val good = JSONObject().put("apk", "runtime.apk").put("asset", "assets/private.mp3")
            .put("stampFile", "private.version").put("stamp", "1.0.11-1")
        assertEquals("private.version", CoreUnpackSpec.parse(good)?.stampFile)
        assertEquals(null, CoreUnpackSpec.parse(null))
        listOf(
            JSONObject(good.toString()).put("apk", "../runtime.apk"),
            JSONObject(good.toString()).put("asset", "/assets/private.mp3"),
            JSONObject(good.toString()).put("stampFile", "sub/private.version"),
            JSONObject(good.toString()).put("stamp", "two\nlines"),
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { CoreUnpackSpec.parse(bad) } }
    }
}
