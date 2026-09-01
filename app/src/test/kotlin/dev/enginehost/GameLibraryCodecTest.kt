package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLibraryCodecTest {
    @Test
    fun `round trips ordered paths and removes duplicates`() {
        val encoded = GameLibraryCodec.encode(listOf("/games/A", "/games/B", "/games/A"))
        assertEquals(listOf("/games/A", "/games/B"), GameLibraryCodec.decode(encoded))
    }

    @Test
    fun `malformed storage is treated as an empty library`() {
        assertEquals(emptyList<String>(), GameLibraryCodec.decode("not-json"))
    }

    @Test
    fun `blank entries are ignored`() {
        assertEquals(listOf("/games/A"), GameLibraryCodec.decode("[\"\",\" /games/A \"]"))
    }
}
