package dev.enginehost

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameWritesTest {
    @Test
    fun subFoldersOfTheGameAreAccepted() {
        assertEquals(listOf("game/saves", "game/cache"), GameWrites.parse(JSONArray(listOf("game/saves", "game/cache"))))
        assertEquals(emptyList<String>(), GameWrites.parse(null))
    }

    @Test
    fun nothingThatCouldLeaveTheGameFolderIs() {
        listOf("", "/game/saves", "../saves", "game/../../x", "game//saves", "./saves", "game\\saves").forEach {
            assertFalse(it, GameWrites.isSubFolder(it))
        }
        assertTrue(GameWrites.isSubFolder("game/saves"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBundleNamingAPathOutsideItIsRefused() {
        GameWrites.parse(JSONArray(listOf("game/saves", "../elsewhere")))
    }
}
