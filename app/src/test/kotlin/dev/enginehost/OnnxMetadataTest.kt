package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class OnnxMetadataTest {
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun bytesField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        varint(out, (field.toLong() shl 3) or 2)
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun entry(key: String, value: String): ByteArray = ByteArrayOutputStream().also {
        bytesField(it, 1, key.toByteArray())
        bytesField(it, 2, value.toByteArray())
    }.toByteArray()

    private fun model(build: (ByteArrayOutputStream) -> Unit): File =
        File.createTempFile("model", ".onnx").apply { deleteOnExit(); writeBytes(ByteArrayOutputStream().also(build).toByteArray()) }

    @Test
    fun `metadata is read past a large graph`() {
        val file = model { out ->
            varint(out, (1L shl 3) or 0); varint(out, 8) // ir_version
            bytesField(out, 2, "pytorch".toByteArray()) // producer_name
            bytesField(out, 7, ByteArray(300_000) { 7 }) // graph, skipped unread
            bytesField(out, 14, entry("model_type", "kokoro"))
            bytesField(out, 14, entry("speaker_names", "af_bella,am_adam"))
        }
        val metadata = OnnxMetadata.read(file)
        assertEquals("kokoro", metadata["model_type"])
        assertEquals("af_bella,am_adam", metadata["speaker_names"])
    }

    @Test
    fun `a file that is not ONNX is refused, not misread`() {
        val text = File.createTempFile("not", ".onnx").apply { deleteOnExit(); writeText("tokens are not a model\n") }
        assertTrue(runCatching { OnnxMetadata.read(text) }.exceptionOrNull() is IOException)
        // A length past the end of the file.
        val cut = model { out -> varint(out, (7L shl 3) or 2); varint(out, 1_000_000) }
        assertTrue(runCatching { OnnxMetadata.read(cut) }.exceptionOrNull() is IOException)
    }
}
