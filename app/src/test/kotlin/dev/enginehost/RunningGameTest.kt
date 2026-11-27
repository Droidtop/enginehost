package dev.enginehost

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A launch floor may be recreated while its separately hosted runtime keeps running. */
class RunningGameTest {
    private val first = File("games/first")
    private val second = File("games/second")

    @After
    fun reset() { RunningGame.folder = null }

    @Test
    fun `recreating the floor keeps the same game eligible to be brought forward`() {
        RunningGame.folder = first
        RunningGame.ownerDestroyed(first, changingConfigurations = true)
        RunningGame.restore(first, runtimeStarted = true)
        assertTrue(RunningGame.isRunning(first))
        assertFalse(RunningGame.isRunning(second))
    }

    @Test
    fun `restoring a running floor reattaches an empty registry but planning does not`() {
        RunningGame.folder = null
        RunningGame.restore(first, runtimeStarted = false)
        assertNull(RunningGame.folder)
        RunningGame.restore(first, runtimeStarted = true)
        assertTrue(RunningGame.isRunning(first))
    }

    @Test
    fun `a stale owner cannot replace or clear the newer game`() {
        RunningGame.folder = second
        RunningGame.restore(first, runtimeStarted = true)
        RunningGame.ownerDestroyed(first, changingConfigurations = false)
        assertTrue(RunningGame.isRunning(second))
        RunningGame.ownerDestroyed(second, changingConfigurations = false)
        assertNull(RunningGame.folder)
    }
}
