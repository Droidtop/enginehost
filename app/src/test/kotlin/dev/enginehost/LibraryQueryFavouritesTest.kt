package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibraryQueryFavouritesTest {
    @Test
    fun favouritesIsOneIndexableColumnPredicateAndNarrowsTheList() {
        val sql = LibraryQuery.build(LibraryFilter(favourites = true))
        assertEquals("added = 1 AND favourite = 1", sql.where)
        assertFalse(LibraryFilter(favourites = true).unfiltered)
    }
}
