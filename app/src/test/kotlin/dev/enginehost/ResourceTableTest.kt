package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The parse that decides whether a bundle's resource table collides with
 * the host's. Exercised against hand-built `resources.arsc` bytes so it
 * runs on a plain JVM, with no APK and no Context.
 */
class ResourceTableTest {
    /** A minimal but structurally real table: header, string pool, packages. */
    private fun table(
        packageIds: List<Int>,
        chunkType: Int = 0x0002,
        truncate: Int = 0,
    ): ByteArray {
        val headerSize = 12
        val poolSize = 28
        val packageSize = 288
        val total = headerSize + poolSize + packageSize * packageIds.size
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(chunkType.toShort())
        buffer.putShort(headerSize.toShort())
        buffer.putInt(total)
        buffer.putInt(packageIds.size)

        buffer.putShort(0x0001.toShort())
        buffer.putShort(poolSize.toShort())
        buffer.putInt(poolSize)
        repeat(poolSize - 8) { buffer.put(0.toByte()) }

        packageIds.forEach { id ->
            buffer.putShort(0x0200.toShort())
            buffer.putShort(0x011c.toShort())
            buffer.putInt(packageSize)
            buffer.putInt(id)
            repeat(packageSize - 12) { buffer.put(0.toByte()) }
        }
        val bytes = buffer.array()
        return if (truncate == 0) bytes else bytes.copyOf(bytes.size - truncate)
    }

    @Test
    fun `reads the package id an ordinary application table is built at`() {
        assertEquals(listOf(0x7f), ResourceTable.packageIds(table(listOf(0x7f))))
    }

    @Test
    fun `reads the relocated id a plugin bundle should be built at`() {
        assertEquals(listOf(0x80), ResourceTable.packageIds(table(listOf(0x80))))
    }

    @Test
    fun `reads every package in a multi package table`() {
        assertEquals(listOf(0x80, 0x81), ResourceTable.packageIds(table(listOf(0x80, 0x81))))
    }

    @Test
    fun `a table with no package chunk yields nothing`() {
        assertEquals(emptyList<Int>(), ResourceTable.packageIds(table(emptyList())))
    }

    @Test
    fun `a file that is not a resource table is not guessed at`() {
        assertEquals(emptyList<Int>(), ResourceTable.packageIds(table(listOf(0x7f), chunkType = 0x0001)))
        assertEquals(emptyList<Int>(), ResourceTable.packageIds(ByteArray(4)))
        assertEquals(emptyList<Int>(), ResourceTable.packageIds(ByteArray(0)))
    }

    @Test
    fun `a truncated chunk is dropped rather than read past the end`() {
        assertEquals(emptyList<Int>(), ResourceTable.packageIds(table(listOf(0x7f), truncate = 4)))
    }
}
