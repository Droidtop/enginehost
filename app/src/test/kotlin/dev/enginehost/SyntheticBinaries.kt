package dev.enginehost

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte headers for the scan tests: just enough of a PE image, an ELF
 * binary, an AppImage or an archive for the sniffers to read. No real game
 * or program file is used anywhere in these tests.
 */
internal object SyntheticBinaries {
    /**
     * A PE image [PeImage] accepts: DOS header, COFF header, an optional header of the
     * real size for its magic (so the data directories and subsystem exist), one section.
     */
    fun pe(machine: Int, pe32Plus: Boolean = true, dotNet: Boolean = false, console: Boolean = false): ByteArray {
        val optionalSize = if (pe32Plus) 240 else 224
        val directories = if (pe32Plus) 112 else 96
        val image = ByteBuffer.allocate(0x58 + optionalSize + 40).order(ByteOrder.LITTLE_ENDIAN)
        image.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte())
        image.putInt(0x3C, 0x40)
        image.putInt(0x40, 0x4550)
        image.putShort(0x44, machine.toShort())
        image.putShort(0x46, 1)
        image.putShort(0x54, optionalSize.toShort())
        image.putShort(0x58, (if (pe32Plus) 0x20b else 0x10b).toShort())
        image.putShort(0x58 + 68, (if (console) 3 else 2).toShort())
        if (dotNet) image.putInt(0x58 + directories + 14 * 8, 0x2000)
        return image.array()
    }

    /** A DOS program: an MZ header with nothing behind it. */
    fun dos(): ByteArray = ByteArray(64).also {
        it[0] = 'M'.code.toByte()
        it[1] = 'Z'.code.toByte()
    }

    fun elf(machine: Int, type: Int = 2, appImage: Boolean = false): ByteArray {
        val head = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        head.put(0, 0x7f).put(1, 'E'.code.toByte()).put(2, 'L'.code.toByte()).put(3, 'F'.code.toByte())
        head.put(4, 2).put(5, 1).put(6, 1)
        if (appImage) head.put(8, 'A'.code.toByte()).put(9, 'I'.code.toByte()).put(10, 2)
        head.putShort(16, type.toShort())
        head.putShort(18, machine.toShort())
        return head.array()
    }

    fun script(): ByteArray = "#!/bin/sh\nexec ./game \"\$@\"\n".toByteArray()

    /** The first bytes of a zip, followed by padding up to [size] without writing it (a sparse file). */
    fun archive(file: File, size: Long, magic: ByteArray = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)) {
        RandomAccessFile(file, "rw").use {
            it.write(magic)
            it.setLength(size)
        }
    }

    /** A file of [size] bytes beginning with [head], without writing the rest. */
    fun file(file: File, head: ByteArray, size: Long = head.size.toLong()) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use {
            it.write(head)
            if (size > head.size) it.setLength(size)
        }
    }
}
