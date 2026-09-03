package dev.enginehost

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reading a Godot export's pack well enough to answer two questions
 * without launching anything: which engine built it, and what its assets
 * need from the engine.
 *
 * FORMAT, read from the Godot 4.5.1 sources this bundle is built from --
 * `core/io/file_access_pack.cpp` (`PackedSourcePCK::try_open_pack`) and
 * the flag constants in `file_access_pack.h`:
 *
 * A pack begins with the ASCII magic `GDPC`, a 32-bit pack format version
 * (2 and 3 are loadable), the engine major/minor/patch as three more
 * 32-bit values, 32-bit pack flags, then a 64-bit file base. Everything is
 * little-endian. In V3 a 64-bit directory offset follows, relative to the
 * pack start; in V2 the directory follows the header after 16 reserved
 * 32-bit words. The directory is a 32-bit file count then, per file, a
 * 32-bit name length, that many name bytes (NUL-padded to a 4-byte
 * boundary), a 64-bit offset, a 64-bit size, a 16-byte MD5 and 32-bit
 * per-file flags. A file's real offset is `fileBase + offset`, and for V3
 * `fileBase` is itself relative to the pack start.
 *
 * A pack appended to a self-contained export is found through a 12-byte
 * trailer at EOF: a 64-bit pack size then `GDPC` again. Godot also looks
 * for a pack in a dedicated executable section; that form is not read
 * here, so a game shipping it yields no answer rather than a wrong one.
 */
object GodotPack {
    private const val MAGIC = "GDPC"
    private const val TRAILER_BYTES = 12
    private const val HEADER_BYTES = 40
    private const val PACK_DIR_ENCRYPTED = 1
    private const val PACK_REL_FILEBASE = 1 shl 1
    private const val PACK_FILE_ENCRYPTED = 1
    private const val MAX_DIRECTORY_ENTRIES = 2_000_000
    private const val MAX_NAME_BYTES = 4096
    private const val MAX_BINARY_PROBES = 4
    private val EXPORT_BINARY_SUFFIXES = listOf(".exe", ".x86_64", ".x86_32")
    private const val NUL = '\u0000'

    /** Where a pack starts inside a file, and what its header declares. */
    data class Header(
        val packStart: Long,
        val formatVersion: Int,
        val engineVersion: String,
        val directoryOffset: Long,
        val fileBase: Long,
        val directoryEncrypted: Boolean,
    )

    /** One directory entry, with [offset] already absolute within the carrier file. */
    data class Entry(val path: String, val offset: Long, val size: Long, val encrypted: Boolean)

    /**
     * Root-level files in [tree] worth probing for a pack: a standalone
     * .pck, then the export binaries Godot's own templates produce.
     * Capped so a folder full of executables cannot turn a probe into a
     * scan of all of them.
     */
    fun candidates(tree: GameTree): List<String> {
        val root = tree.filePaths.filter { '/' !in it }
        val packs = root.filter { it.lowercase().endsWith(".pck") }
        val binaries = root.filter { path -> EXPORT_BINARY_SUFFIXES.any { path.lowercase().endsWith(it) } }
        return packs.sorted() + binaries.sorted().take(MAX_BINARY_PROBES)
    }

    /** The pack at offset 0, or the one a self-contained export appends to itself. */
    fun open(tree: GameTree, path: String): Header? {
        headerAt(tree, path, 0L)?.let { return it }
        val start = embeddedStart(tree, path) ?: return null
        return headerAt(tree, path, start)
    }

    private fun embeddedStart(tree: GameTree, path: String): Long? {
        val size = tree.length(path)
        if (size <= TRAILER_BYTES) return null
        val trailer = tree.read(path, size - TRAILER_BYTES, TRAILER_BYTES)
        if (trailer.size != TRAILER_BYTES) return null
        if (String(trailer, 8, 4, Charsets.US_ASCII) != MAGIC) return null
        val packSize = little(trailer).long
        val start = size - TRAILER_BYTES - packSize
        return start.takeIf { packSize > 0 && it >= 0 }
    }

    private fun headerAt(tree: GameTree, path: String, packStart: Long): Header? {
        val bytes = tree.read(path, packStart, HEADER_BYTES)
        if (bytes.size < HEADER_BYTES) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != MAGIC) return null
        val buffer = little(bytes)
        buffer.int
        val format = buffer.int
        if (format != 2 && format != 3) return null
        val major = buffer.int
        val minor = buffer.int
        val patch = buffer.int
        if (major !in 1..9 || minor !in 0..99 || patch !in 0..999) return null
        val flags = buffer.int
        val rawFileBase = buffer.long
        val fileBase =
            if (format == 3 || (flags and PACK_REL_FILEBASE) != 0) rawFileBase + packStart else rawFileBase
        // V3 stores the directory offset next; V2 puts the directory
        // immediately after 16 reserved words, i.e. 96 bytes into the pack.
        val directory = if (format == 3) buffer.long + packStart else packStart + 96
        return Header(
            packStart,
            format,
            "$major.$minor.$patch",
            directory,
            fileBase,
            (flags and PACK_DIR_ENCRYPTED) != 0,
        )
    }

    /**
     * The pack's directory entries whose path satisfies [wanted].
     *
     * An encrypted directory cannot be walked without the game's script
     * encryption key, which enginehost does not have; that case yields
     * nothing rather than garbage. [limit] caps how many matches come
     * back so a pack holding thousands of them stays cheap.
     */
    fun entries(
        tree: GameTree,
        path: String,
        header: Header,
        limit: Int = Int.MAX_VALUE,
        wanted: (String) -> Boolean,
    ): List<Entry> {
        if (header.directoryEncrypted) return emptyList()
        val reader = WindowReader(tree, path, header.directoryOffset)
        val count = reader.int() ?: return emptyList()
        if (count < 0 || count > MAX_DIRECTORY_ENTRIES) return emptyList()
        val found = mutableListOf<Entry>()
        for (index in 0 until count) {
            val nameLength = reader.int() ?: break
            if (nameLength < 0 || nameLength > MAX_NAME_BYTES) break
            val name = reader.bytes(nameLength) ?: break
            val offset = reader.long() ?: break
            val size = reader.long() ?: break
            if (reader.bytes(16) == null) break
            val flags = reader.int() ?: break
            val entryPath = String(name, Charsets.UTF_8).trimEnd(NUL)
            if (wanted(entryPath)) {
                found += Entry(
                    entryPath,
                    header.fileBase + offset,
                    size,
                    (flags and PACK_FILE_ENCRYPTED) != 0,
                )
                if (found.size >= limit) break
            }
        }
        return found
    }

    private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Sequential reader over a [GameTree] file that refills a fixed
     * window rather than issuing one read per field: a 6,000-entry
     * directory is roughly half a megabyte and should cost a handful of
     * reads, not tens of thousands.
     */
    private class WindowReader(
        private val tree: GameTree,
        private val path: String,
        start: Long,
    ) {
        /** Absolute offset of the first byte not yet pulled into [buffer]. */
        private var nextRead = start
        private var buffer = ByteArray(0)
        private var cursor = 0

        private fun ensure(count: Int): Boolean {
            if (buffer.size - cursor >= count) return true
            val carry = buffer.copyOfRange(cursor, buffer.size)
            val fetched = tree.read(path, nextRead, maxOf(WINDOW, count))
            nextRead += fetched.size
            buffer = if (fetched.isEmpty()) carry else carry + fetched
            cursor = 0
            return buffer.size >= count
        }

        fun bytes(count: Int): ByteArray? {
            if (count == 0) return ByteArray(0)
            if (!ensure(count)) return null
            val slice = buffer.copyOfRange(cursor, cursor + count)
            cursor += count
            return slice
        }

        fun int(): Int? = bytes(4)?.let { little(it).int }

        fun long(): Long? = bytes(8)?.let { little(it).long }

        companion object {
            private const val WINDOW = 128 * 1024
        }
    }
}
