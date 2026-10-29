package dev.enginehost

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The `metadata_props` of an ONNX model, read without loading the model (docs/text-services.md,
 * "Voices on the device"). A sherpa-onnx voice model records there what it is (`model_type`,
 * `sample_rate`) and, for a multi-speaker model, its voices by name (`speaker_names`), from which
 * the voices' genders are known.
 *
 * An ONNX file is a protobuf `ModelProto`; `metadata_props` is its field 14, a repeated
 * `StringStringEntryProto` (key 1, value 2). The top-level fields are walked and every other one is
 * skipped by its length, so the graph (nearly all of the file) is never read. Bounded: a field or
 * entry larger than the caps is refused, so a hostile file cannot make it allocate much.
 */
object OnnxMetadata {
    private const val METADATA_PROPS = 14
    private const val MAX_ENTRY = 256 * 1024
    private const val MAX_ENTRIES = 256

    /** The model's metadata, key to value; empty when the file has none. Throws [IOException] for a file that is not ONNX. */
    fun read(file: File): Map<String, String> = RandomAccessFile(file, "r").use { read(it) }

    fun read(input: RandomAccessFile): Map<String, String> {
        val metadata = LinkedHashMap<String, String>()
        val end = input.length()
        while (input.filePointer < end) {
            val key = varint(input)
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varint(input)
                1 -> skip(input, 8, end)
                5 -> skip(input, 4, end)
                2 -> {
                    val length = varint(input)
                    if (length < 0 || length > end - input.filePointer) throw IOException("A field runs past the end of the file")
                    if (field == METADATA_PROPS) {
                        if (length > MAX_ENTRY || metadata.size >= MAX_ENTRIES) throw IOException("The model's metadata is too large")
                        val entry = ByteArray(length.toInt())
                        input.readFully(entry)
                        entry(entry)?.let { (k, v) -> metadata[k] = v }
                    } else {
                        input.seek(input.filePointer + length)
                    }
                }
                else -> throw IOException("Not an ONNX model")
            }
        }
        return metadata
    }

    /** One `StringStringEntryProto`, as (key, value); null when it lacks either. */
    internal fun entry(bytes: ByteArray): Pair<String, String>? {
        var at = 0
        var key: String? = null
        var value: String? = null
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (at >= bytes.size || shift > 63) throw IOException("A metadata entry is cut short")
                val b = bytes[at++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        while (at < bytes.size) {
            val tag = next()
            if ((tag and 7) != 2L) throw IOException("A metadata entry is not two strings")
            val length = next().toInt()
            if (length < 0 || length > bytes.size - at) throw IOException("A metadata entry is cut short")
            val text = String(bytes, at, length, Charsets.UTF_8)
            at += length
            when ((tag ushr 3).toInt()) {
                1 -> key = text
                2 -> value = text
            }
        }
        return if (key != null && value != null) key!! to value!! else null
    }

    private fun varint(input: RandomAccessFile): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (shift > 63) throw IOException("Not an ONNX model")
            val b = input.read()
            if (b < 0) throw EOFException("Not an ONNX model")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    private fun skip(input: RandomAccessFile, bytes: Int, end: Long) {
        if (input.filePointer + bytes > end) throw EOFException("Not an ONNX model")
        input.seek(input.filePointer + bytes)
    }
}
