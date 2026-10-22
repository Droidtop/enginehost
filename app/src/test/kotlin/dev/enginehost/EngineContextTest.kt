package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a config that names no engineContext matches (owner, 2026-10-22). */
class EngineContextTest {
    @Test
    fun `a named context is used as is`() {
        assertEquals("mz", effectiveEngineContext("mz", listOf("mv", "mz")))
        assertEquals("default", effectiveEngineContext("default", listOf("standard")))
    }

    @Test
    fun `none named means the engine's one line when no core offers default`() {
        assertEquals("standard", effectiveEngineContext(null, listOf("standard", "standard")))
    }

    @Test
    fun `none named stays default when a core offers it or the engine has several lines`() {
        assertEquals("default", effectiveEngineContext(null, listOf("default", "standard")))
        assertEquals("default", effectiveEngineContext(null, listOf("mv", "mz")))
        assertEquals("default", effectiveEngineContext(null, emptyList()))
    }
}
