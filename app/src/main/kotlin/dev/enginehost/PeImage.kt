package dev.enginehost

/**
 * The headers of a Windows PE executable, parsed once so every reader
 * that needs to find something inside one (the application icon, a
 * Godot pack in its own section) walks the same tables the same way.
 *
 * Only the fixed layout is read: DOS stub -> PE signature -> COFF header
 * -> optional header -> section table. Anything short or malformed yields
 * `null`, never an exception, because these files come off a game folder
 * the user controls and a bad header must not take a screen down.
 */
class PeImage private constructor(
    /** File offset of the four-byte PE signature. */
    val signatureOffset: Long,
    /** The optional header, PE32 (magic 0x10b) or PE32+ (0x20b), in full. */
    val optionalHeader: ByteArray,
    val sections: List<Section>,
) {
    class Section(
        /** Section name, padding removed; at most 8 bytes. */
        val name: String,
        val virtualAddress: Long,
        val virtualSize: Long,
        val rawPointer: Long,
        val rawSize: Long,
    )

    /** Where the data directories start inside [optionalHeader], by PE32/PE32+ magic. */
    val dataDirectoriesAt: Int?
        get() = when (optionalHeader.u16(0)) {
            0x10b -> 96
            0x20b -> 112
            else -> null
        }

    /** The file offset behind a relative virtual address, via the section that maps it. */
    fun fileOffset(rva: Long): Long? {
        val section = sections.firstOrNull {
            rva >= it.virtualAddress && rva < it.virtualAddress + maxOf(it.virtualSize, it.rawSize)
        } ?: return null
        return rva - section.virtualAddress + section.rawPointer
    }

    fun section(name: String): Section? = sections.firstOrNull { it.name == name }

    companion object {
        private const val COFF_BYTES = 24
        private const val SECTION_BYTES = 40
        private const val MAX_SECTIONS = 96
        private const val NAME_PADDING = 0.toChar()

        fun parse(source: ReadAt): PeImage? {
            val dos = source.read(0, 0x40)
            if (dos.size < 0x40 || dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
            val peOffset = dos.u32(0x3C)
            val coff = source.read(peOffset, COFF_BYTES)
            if (coff.size < COFF_BYTES || coff.u32(0) != 0x4550L) return null
            val sectionCount = coff.u16(6)
            if (sectionCount == 0 || sectionCount > MAX_SECTIONS) return null
            val optionalSize = coff.u16(20)
            val optional = source.read(peOffset + COFF_BYTES, optionalSize)
            if (optional.size < optionalSize || optional.size < 2) return null
            val tableAt = peOffset + COFF_BYTES + optionalSize
            val table = source.read(tableAt, sectionCount * SECTION_BYTES)
            if (table.size < sectionCount * SECTION_BYTES) return null
            val sections = (0 until sectionCount).map { i ->
                val at = i * SECTION_BYTES
                Section(
                    name = String(table, at, 8, Charsets.US_ASCII).trimEnd(NAME_PADDING),
                    virtualSize = table.u32(at + 8),
                    virtualAddress = table.u32(at + 12),
                    rawSize = table.u32(at + 16),
                    rawPointer = table.u32(at + 20),
                )
            }
            return PeImage(peOffset, optional, sections)
        }
    }
}
