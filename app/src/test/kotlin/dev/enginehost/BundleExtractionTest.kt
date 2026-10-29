package dev.enginehost

import dev.droidtop.shared.trust.BundleTarException
import dev.enginehost.api.EnginePluginContract
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Reading a bundle archive: the manifest first, then its signatures, then exactly the signed payload, in the signed order.
 * The signer decides before a payload byte is written. The container itself (unsafe paths, links, repeats, size and memory
 * caps) is the shared BundleTar's, tested in Droidtop/droidtop-shared.
 */
class BundleExtractionTest {
    private class Item(
        val name: String,
        val bytes: ByteArray = ByteArray(0),
        val type: Byte = '0'.code.toByte(),
        val mode: Int = 0b100_100_100,
        val link: String? = null,
    )

    private val signer = BundleSigner("A".repeat(64), SignerKind.OFFICIAL, format = 1, anchorKeySha256 = "B".repeat(64))

    private fun payloadDigest(files: List<Pair<String, ByteArray>>): String = MessageDigest.getInstance("SHA-256").run {
        files.forEach { (path, bytes) ->
            update(path.toByteArray())
            update(0)
            update(bytes.size.toString().toByteArray())
            update(0)
            update(bytes)
        }
        digest().joinToString("") { "%02X".format(it) }
    }

    /** A manifest for [files] in order, each of mode 0444. */
    private fun manifest(files: List<Pair<String, ByteArray>>): ByteArray = JSONObject()
        .put("formatVersion", 1)
        .put("assetName", "renpy-8.3-v1.enginehost.tar.xz")
        .put("bundleId", "dev.enginehost.renpy.8_3.v1")
        .put("engine", "renpy")
        .put("pluginVersion", "1.0.0")
        .put("apiVersion", 1)
        .put("entrypoint", "dev.enginehost.renpy.Plugin")
        .put("origin", "https://github.com/droidtop/enginehost-renpy-plugin")
        .put("dexFiles", JSONArray().put("classes.dex"))
        .put("payloadSha256", payloadDigest(files))
        .put(
            "capabilities",
            JSONArray().put(JSONObject().put("id", "renpy-8.3.2").put("engineContext", "python3").put("runtimeVersion", "8.3.2")),
        )
        .put(
            "files",
            JSONArray().apply {
                files.forEach { (path, bytes) ->
                    put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", sha256(bytes)).put("mode", 0b100_100_100))
                }
            },
        )
        .toString()
        .toByteArray()

    private fun archive(vararg items: Item): File {
        val raw = ByteArrayOutputStream()
        TarArchiveOutputStream(raw).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (item in items) {
                val entry = TarArchiveEntry(item.name, item.type)
                if (item.type == '0'.code.toByte()) entry.size = item.bytes.size.toLong()
                entry.mode = item.mode
                if (item.link != null) entry.linkName = item.link
                tar.putArchiveEntry(entry)
                if (item.type == '0'.code.toByte()) tar.write(item.bytes)
                tar.closeArchiveEntry()
            }
        }
        val file = File.createTempFile("bundle", ".enginehost.tar.xz").apply { deleteOnExit() }
        file.outputStream().use { out -> XZCompressorOutputStream(out).use { it.write(raw.toByteArray()) } }
        return file
    }

    private fun staging(): File = createTempDir(prefix = "extract-test").also { it.deleteOnExit() }

    private val dex = "classes.dex" to byteArrayOf(1, 2, 3)
    private val lib = "lib/arm64-v8a/libgame.so" to byteArrayOf(9, 8, 7, 6)
    private val jwsItem = Item(ENGINE_BUNDLE_JWS, "header.payload.signature".toByteArray())

    private fun manifestItem(files: List<Pair<String, ByteArray>>) = Item(EnginePluginContract.ENGINE_BUNDLE_MANIFEST, manifest(files))

    private fun fileItems(files: List<Pair<String, ByteArray>>) = files.map { Item(it.first, it.second) }

    private fun extract(archive: File, staging: File = staging(), seenAtVerify: MutableList<List<String>> = mutableListOf()): ExtractedBundle =
        EngineBundleInstaller.extractVerified(archive, staging) { _, _ ->
            seenAtVerify += staging.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            signer
        }

    private fun refused(archive: File, block: (File) -> ExtractedBundle = { extract(it) }): String {
        try {
            block(archive)
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        } catch (e: BundleTarException) {
            return e.message.orEmpty()
        }
        throw AssertionError("the bundle should have been refused")
    }

    @Test
    fun `a signed bundle extracts its payload, with the signer deciding before a byte is written`() {
        val files = listOf(dex, lib)
        val staging = staging()
        val atVerify = mutableListOf<List<String>>()
        val extracted = extract(
            archive(Item(EnginePluginContract.ENGINE_BUNDLE_MANIFEST, manifest(files)), jwsItem, Item("lib/", type = '5'.code.toByte()), *fileItems(files).toTypedArray()),
            staging,
            atVerify,
        )
        assertEquals(listOf(emptyList<String>()), atVerify)
        assertEquals(signer, extracted.signer)
        assertEquals("header.payload.signature", extracted.signatures.jws)
        assertTrue(File(staging, "classes.dex").readBytes().contentEquals(dex.second))
        assertTrue(File(staging, "lib/arm64-v8a/libgame.so").readBytes().contentEquals(lib.second))
    }

    @Test
    fun `a legacy signature may precede the v1 one and is passed over, and alone it leaves no JWS for the verifier`() {
        val files = listOf(dex)
        val legacy = Item(EnginePluginContract.ENGINE_BUNDLE_SIGNATURE, java.util.Base64.getEncoder().encode(byteArrayOf(5, 6, 7)))
        val both = extract(archive(manifestItem(files), legacy, jwsItem, *fileItems(files).toTypedArray()))
        assertEquals("header.payload.signature", both.signatures.jws)
        // The verifier (BundleVerifier) is what refuses a bundle that carries only the legacy one.
        val only = extract(archive(manifestItem(files), legacy, *fileItems(files).toTypedArray()))
        assertEquals(null, only.signatures.jws)
        // The other order is not the format: the v1 signature then reads as an unsigned payload file.
        assertTrue(refused(archive(manifestItem(files), jwsItem, legacy, *fileItems(files).toTypedArray())).contains("Unsigned tar entry"))
    }

    @Test
    fun `a bundle with no signature, or no manifest first, is refused`() {
        val files = listOf(dex)
        assertTrue(refused(archive(manifestItem(files), *fileItems(files).toTypedArray())).contains("signature is missing"))
        assertTrue(refused(archive(manifestItem(files))).contains("signature is missing"))
        assertTrue(refused(archive(jwsItem, manifestItem(files), *fileItems(files).toTypedArray())).contains("Invalid ${EnginePluginContract.ENGINE_BUNDLE_MANIFEST} header"))
    }

    @Test
    fun `when the signer refuses nothing is written`() {
        val files = listOf(dex)
        val staging = staging()
        val error = assertThrows(IllegalArgumentException::class.java) {
            EngineBundleInstaller.extractVerified(archive(manifestItem(files), jwsItem, *fileItems(files).toTypedArray()), staging) { _, _ ->
                throw IllegalArgumentException("not signed by anyone you trust")
            }
        }
        assertEquals("not signed by anyone you trust", error.message)
        assertTrue(staging.walkTopDown().none { it.isFile })
    }

    @Test
    fun `the payload is exactly the signed files, in the signed order`() {
        val files = listOf(dex, lib)
        assertTrue(refused(archive(manifestItem(files), jwsItem, *fileItems(files).toTypedArray(), Item("extra.bin", byteArrayOf(1)))).contains("Unsigned tar entry"))
        assertTrue(refused(archive(manifestItem(files), jwsItem, *fileItems(files).reversed().toTypedArray())).contains("order"))
        assertTrue(refused(archive(manifestItem(files), jwsItem, Item("classes.dex", dex.second))).contains("missing signed files"))
        assertTrue(refused(archive(manifestItem(files), jwsItem, Item("lib/", type = '5'.code.toByte()), Item("other/", type = '5'.code.toByte()), *fileItems(files).toTypedArray())).contains("Unsigned tar directory"))
    }

    @Test
    fun `a size, digest or mode that differs from the manifest is refused`() {
        val files = listOf(dex)
        assertTrue(refused(archive(manifestItem(files), jwsItem, Item("classes.dex", byteArrayOf(1, 2, 3, 4)))).contains("Size mismatch"))
        assertTrue(refused(archive(manifestItem(files), jwsItem, Item("classes.dex", byteArrayOf(1, 2, 4)))).contains("Digest mismatch"))
        assertTrue(refused(archive(manifestItem(files), jwsItem, Item("classes.dex", dex.second, mode = 0b111_111_111))).contains("Mode mismatch"))
    }

    @Test
    fun `a link, an unsafe path or a repeated entry is refused by the container reader`() {
        val files = listOf(dex)
        assertTrue(refused(archive(manifestItem(files), jwsItem, Item("classes.dex", type = '2'.code.toByte(), link = "/etc/passwd"))).contains("link"))
        assertTrue(refused(archive(manifestItem(files), jwsItem, Item("../classes.dex", dex.second))).contains("bundle path"))
        assertTrue(refused(archive(manifestItem(files), jwsItem, *fileItems(files).toTypedArray(), *fileItems(files).toTypedArray())).contains("twice"))
    }
}
