package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLibraryCodecTest {
    @Test
    fun `an earlier library keeps its order and drops duplicates`() {
        assertEquals(
            listOf("/games/A", "/games/B"),
            GameLibraryCodec.decode("[\"/games/A\",\"/games/B\",\"/games/A\"]"),
        )
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
