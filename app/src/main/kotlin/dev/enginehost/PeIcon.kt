package dev.enginehost

import java.io.RandomAccessFile

/**
 * Bounded random access over a file the reader must never load whole: a
 * Godot export or a Ren'Py launcher can be hundreds of megabytes with the
 * game packed behind the executable, while the icon it carries is a few
 * kilobytes in a table near the front.
 */
fun interface ReadAt {
    /** Bytes at [offset]; shorter than [size] only at end of input. */
    fun read(offset: Long, size: Int): ByteArray
}

class ByteArrayReadAt(private val bytes: ByteArray) : ReadAt {
    override fun read(offset: Long, size: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size || size <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + size).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

class RandomAccessFileReadAt(private val file: RandomAccessFile) : ReadAt {
    override fun read(offset: Long, size: Int): ByteArray {
        if (offset < 0 || offset >= file.length() || size <= 0) return ByteArray(0)
        file.seek(offset)
        val buffer = ByteArray(minOf(size.toLong(), file.length() - offset).toInt())
        var filled = 0
        while (filled < buffer.size) {
            val n = file.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        return if (filled == buffer.size) buffer else buffer.copyOf(filled)
    }
}

/**
 * One image out of a Windows icon: either a PNG stream (Vista-era icons
 * store the 256px image that way) or a DIB decoded to straight ARGB.
 */
sealed class IconImage {
    abstract val width: Int
    abstract val height: Int

    class Png(override val width: Int, override val height: Int, val bytes: ByteArray) : IconImage()
    class Argb(override val width: Int, override val height: Int, val pixels: IntArray) : IconImage()
}

/**
 * Reads the application icon out of a Windows PE executable: the first
 * RT_GROUP_ICON in resource-table order, which is the icon Explorer shows
 * for the file. Every engine droidtop launches through enginehost ships a
 * Windows build with one, so a game that has configured nothing else still
 * has a face for the launch screen.
 *
 * Only what the icon needs is parsed. Sizes are bounded so a corrupt or
 * hostile header cannot turn into a giant allocation; on anything
 * unexpected the answer is `null`, never an exception, because a missing
 * icon is a cosmetic gap and a crash on the launch screen is not.
 */
object PeIcon {
    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14
    private const val MAX_RESOURCE_BYTES = 4 shl 20
    private const val MAX_GROUP_ENTRIES = 64

    /** All images of the executable's application icon, or null when it has none. */
    fun extract(source: ReadAt): List<IconImage>? = try {
        Parser(source).applicationIcon()
    } catch (_: Exception) {
        null
    }

    /** The image best suited to a launch screen: the largest, and among equals the PNG one. */
    fun best(images: List<IconImage>): IconImage? =
        images.maxWithOrNull(compareBy<IconImage>({ it.width * it.height }, { it is IconImage.Png }))

    private class Parser(private val source: ReadAt) {
        private lateinit var sections: List<Section>
        private var resourceBase = 0L

        private class Section(val virtualAddress: Long, val virtualSize: Long, val rawPointer: Long, val rawSize: Long)

        fun applicationIcon(): List<IconImage>? {
            val dos = source.read(0, 0x40)
            if (dos.size < 0x40 || dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
            val peOffset = dos.u32(0x3C)
            val coff = source.read(peOffset, 24)
            if (coff.size < 24 || coff.u32(0) != 0x4550L) return null
            val sectionCount = coff.u16(6)
            val optionalSize = coff.u16(20)
            val optional = source.read(peOffset + 24, optionalSize)
            if (optional.size < optionalSize) return null
            val directoriesAt = when (optional.u16(0)) {
                0x10b -> 96
                0x20b -> 112
                else -> return null
            }
            // Data directory 2 is the resource table.
            val resourceEntry = directoriesAt + 2 * 8
            if (optional.size < resourceEntry + 8) return null
            val resourceRva = optional.u32(resourceEntry)
            if (resourceRva == 0L) return null

            val tableAt = peOffset + 24 + optionalSize
            val table = source.read(tableAt, sectionCount * 40)
            if (table.size < sectionCount * 40) return null
            sections = (0 until sectionCount).map { i ->
                val at = i * 40
                Section(table.u32(at + 12), table.u32(at + 8), table.u32(at + 20), table.u32(at + 16))
            }
            resourceBase = fileOffset(resourceRva) ?: return null

            val groupDirectory = subdirectories(0)[RT_GROUP_ICON] ?: return null
            val group = firstLeafData(groupDirectory) ?: return null
            if (group.size < 6) return null
            val count = group.u16(4).coerceAtMost(MAX_GROUP_ENTRIES)
            if (group.size < 6 + count * 14) return null

            val iconDirectory = subdirectories(0)[RT_ICON] ?: return null
            val iconsById = subdirectories(iconDirectory)
            val images = ArrayList<IconImage>(count)
            for (i in 0 until count) {
                val at = 6 + i * 14
                val width = group[at].toInt() and 0xFF
                val height = group[at + 1].toInt() and 0xFF
                val id = group.u16(at + 12)
                val subdir = iconsById[id] ?: continue
                val data = firstLeafData(subdir) ?: continue
                IcoImageDecoder.decode(data, if (width == 0) 256 else width, if (height == 0) 256 else height)
                    ?.let(images::add)
            }
            return images.takeIf { it.isNotEmpty() }
        }

        private fun fileOffset(rva: Long): Long? {
            val section = sections.firstOrNull {
                rva >= it.virtualAddress && rva < it.virtualAddress + maxOf(it.virtualSize, it.rawSize)
            } ?: return null
            return rva - section.virtualAddress + section.rawPointer
        }

        private data class Entry(val id: Int?, val offset: Long, val isDirectory: Boolean)

        /** Numbered subdirectory entries of the directory at [directoryOffset], by id. */
        private fun subdirectories(directoryOffset: Long): Map<Int, Long> =
            entries(directoryOffset).mapNotNull { e -> if (e.isDirectory && e.id != null) e.id to e.offset else null }.toMap()

        private fun entries(directoryOffset: Long): List<Entry> {
            val header = source.read(resourceBase + directoryOffset, 16)
            if (header.size < 16) return emptyList()
            val total = (header.u16(12) + header.u16(14)).coerceAtMost(256)
            val raw = source.read(resourceBase + directoryOffset + 16, total * 8)
            if (raw.size < total * 8) return emptyList()
            return (0 until total).map { i ->
                val name = raw.u32(i * 8)
                val data = raw.u32(i * 8 + 4)
                Entry(
                    id = if (name and 0x80000000L != 0L) null else name.toInt(),
                    offset = data and 0x7FFFFFFFL,
                    isDirectory = data and 0x80000000L != 0L,
                )
            }
        }

        /** Descend from [directoryOffset] through the language level to the first data entry and read it. */
        private fun firstLeafData(directoryOffset: Long): ByteArray? {
            var offset = directoryOffset
            var isDirectory = true
            var depth = 0
            while (isDirectory && depth < 3) {
                val first = entries(offset).firstOrNull() ?: return null
                offset = first.offset
                isDirectory = first.isDirectory
                depth++
            }
            if (isDirectory) return null
            val entry = source.read(resourceBase + offset, 16)
            if (entry.size < 16) return null
            val size = entry.u32(4)
            if (size <= 0 || size > MAX_RESOURCE_BYTES) return null
            val at = fileOffset(entry.u32(0)) ?: return null
            val data = source.read(at, size.toInt())
            return data.takeIf { it.size == size.toInt() }
        }
    }
}

/**
 * Decodes one icon image as stored in an RT_ICON resource (or an .ico
 * file entry): a PNG stream is passed through, a DIB is unpacked to ARGB
 * with the 1-bit AND mask applied. Handles the depths real icons use
 * (1, 4, 8, 24, 32 bpp, uncompressed or 32 bpp bitfields).
 */
object IcoImageDecoder {
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private const val MAX_EDGE = 1024

    fun decode(data: ByteArray, hintWidth: Int, hintHeight: Int): IconImage? {
        if (data.size >= 8 && data.copyOfRange(0, 4).contentEquals(PNG_MAGIC)) {
            // IHDR is the first chunk: width and height are big-endian at 16 and 20.
            val width = if (data.size >= 24) data.be32(16) else hintWidth
            val height = if (data.size >= 24) data.be32(20) else hintHeight
            if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE) return null
            return IconImage.Png(width, height, data)
        }
        return decodeDib(data)
    }

    private fun decodeDib(d: ByteArray): IconImage? {
        if (d.size < 40) return null
        val headerSize = d.u32(0).toInt()
        if (headerSize < 40 || headerSize > d.size) return null
        val width = d.s32(4)
        val storedHeight = d.s32(8)
        val bpp = d.u16(14)
        val compression = d.u32(16)
        if (width <= 0 || width > MAX_EDGE || storedHeight <= 0 || storedHeight > MAX_EDGE * 2) return null
        if (compression != 0L && !(compression == 3L && bpp == 32)) return null
        if (bpp != 1 && bpp != 4 && bpp != 8 && bpp != 24 && bpp != 32) return null

        val paletteCount = if (bpp <= 8) {
            val used = d.u32(32).toInt()
            if (used in 1..(1 shl bpp)) used else 1 shl bpp
        } else {
            0
        }
        // BI_BITFIELDS puts three colour masks after a 40-byte header.
        val paletteAt = headerSize + if (compression == 3L && headerSize == 40) 12 else 0
        val pixelsAt = paletteAt + paletteCount * 4
        if (d.size < pixelsAt) return null

        val xorStride = ((width * bpp + 31) / 32) * 4
        val andStride = ((width + 31) / 32) * 4
        // The stored height covers the XOR image and the AND mask stacked;
        // some writers store only the image. Decide by what fits.
        val height = when {
            storedHeight % 2 == 0 && d.size >= pixelsAt + (xorStride + andStride) * (storedHeight / 2) -> storedHeight / 2
            d.size >= pixelsAt + xorStride * storedHeight -> storedHeight
            else -> return null
        }
        val maskAt = pixelsAt + xorStride * height
        val hasMask = d.size >= maskAt + andStride * height

        val palette = IntArray(paletteCount) { i ->
            val at = paletteAt + i * 4
            (0xFF shl 24) or ((d[at + 2].toInt() and 0xFF) shl 16) or
                ((d[at + 1].toInt() and 0xFF) shl 8) or (d[at].toInt() and 0xFF)
        }
        val out = IntArray(width * height)
        var anyAlpha = false
        for (y in 0 until height) {
            val row = pixelsAt + (height - 1 - y) * xorStride
            for (x in 0 until width) {
                out[y * width + x] = when (bpp) {
                    32 -> {
                        val at = row + x * 4
                        val a = d[at + 3].toInt() and 0xFF
                        if (a != 0) anyAlpha = true
                        (a shl 24) or ((d[at + 2].toInt() and 0xFF) shl 16) or
                            ((d[at + 1].toInt() and 0xFF) shl 8) or (d[at].toInt() and 0xFF)
                    }
                    24 -> {
                        val at = row + x * 3
                        (0xFF shl 24) or ((d[at + 2].toInt() and 0xFF) shl 16) or
                            ((d[at + 1].toInt() and 0xFF) shl 8) or (d[at].toInt() and 0xFF)
                    }
                    8 -> palette.getOrElse(d[row + x].toInt() and 0xFF) { 0 }
                    4 -> {
                        val b = d[row + x / 2].toInt() and 0xFF
                        palette.getOrElse(if (x % 2 == 0) b shr 4 else b and 0x0F) { 0 }
                    }
                    else -> {
                        val b = d[row + x / 8].toInt() and 0xFF
                        palette.getOrElse((b shr (7 - x % 8)) and 1) { 0 }
                    }
                }
            }
        }
        // A 32bpp icon with a real alpha channel is authoritative; anything
        // else takes its transparency from the AND mask (set bit = clear).
        val useAlphaChannel = bpp == 32 && anyAlpha
        if (!useAlphaChannel) {
            for (y in 0 until height) {
                val row = maskAt + (height - 1 - y) * andStride
                for (x in 0 until width) {
                    val i = y * width + x
                    val transparent = hasMask && (d[row + x / 8].toInt() shr (7 - x % 8)) and 1 == 1
                    out[i] = if (transparent) 0 else out[i] or (0xFF shl 24)
                }
            }
        }
        return IconImage.Argb(width, height, out)
    }
}

internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32(at: Int): Long =
    (this[at].toLong() and 0xFF) or ((this[at + 1].toLong() and 0xFF) shl 8) or
        ((this[at + 2].toLong() and 0xFF) shl 16) or ((this[at + 3].toLong() and 0xFF) shl 24)

internal fun ByteArray.s32(at: Int): Int = u32(at).toInt()

internal fun ByteArray.be32(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)
