package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayHistoryTest {
    @Test
    fun aSessionAddsItsLengthUnlessItFailedToStart() {
        assertEquals(60_000L, PlayHistory.countedMs(1_000L, 61_000L))
        assertEquals(0L, PlayHistory.countedMs(1_000L, 3_000L))
        assertEquals(0L, PlayHistory.countedMs(0L, 61_000L))
    }

    @Test
    fun playtimeSortsByTheStoredColumn() {
        assertEquals("play_ms DESC, name_key ASC, path ASC", LibraryQuery.build(LibraryFilter(sort = SortOrder.PLAYTIME)).orderBy)
    }
}
