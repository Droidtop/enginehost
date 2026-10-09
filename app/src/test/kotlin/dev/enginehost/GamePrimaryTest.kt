package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePrimaryTest {
    @Test
    fun theButtonFollowsWhetherACoreFitsAndMayRun() {
        assertEquals(GamePrimary.GET_CORE, GamePrimary.forResolved(resolved = false, approved = false))
        assertEquals(GamePrimary.APPROVE_CORE, GamePrimary.forResolved(resolved = true, approved = false))
        assertEquals(GamePrimary.PLAY, GamePrimary.forResolved(resolved = true, approved = true))
    }

    @Test
    fun aButtonThatCannotActIsDisabledAndTheOthersAreNot() {
        assertFalse(GamePrimary.FOLDER_MISSING.enabled)
        assertFalse(GamePrimary.NOT_SUPPORTED.enabled)
        listOf(GamePrimary.PLAY, GamePrimary.GET_CORE, GamePrimary.APPROVE_CORE, GamePrimary.SET_UP)
            .forEach { assertTrue(it.name, it.enabled) }
    }
}
