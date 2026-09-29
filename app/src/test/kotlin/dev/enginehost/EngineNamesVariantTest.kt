package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineNamesVariantTest {
    @Test
    fun variantsReadWithoutTheEngineName() {
        assertEquals("VX Ace", EngineNames.variant("rpgmaker", "vxace"))
        assertEquals("MZ", EngineNames.variant("rpgmaker", "mz"))
        assertEquals("4.1", EngineNames.variant("godot", "4.1"))
        assertEquals("Flash (SWF)", EngineNames.variant("flash_air", "swf"))
    }

    @Test
    fun defaultAndImplementationContextsHaveNoVariant() {
        assertNull(EngineNames.variant("renpy", "standard"))
        assertNull(EngineNames.variant("renpy", null))
        assertNull(EngineNames.variant("kirikiri2", DEFAULT_ENGINE_CONTEXT))
    }

    @Test
    fun anEngineIsNamedForAPerson() {
        assertEquals("RPG Maker", EngineNames.family("rpgmaker"))
    }
}
