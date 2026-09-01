package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginDirectoryTest {
    private fun identity(
        name: String,
        engine: String,
        contexts: List<String>,
    ) = OriginIdentity(
        origin = "https://github.com/example/$name",
        engine = engine,
        implementationId = name,
        implementationName = name,
        upstream = null,
        engineContexts = contexts,
        description = "",
    )

    private val mkxpZ = identity("mkxp-z", "rpgmaker", listOf("xp", "vx", "vxace"))
    private val easyRpg = identity("easyrpg", "rpgmaker", listOf("2000", "2003"))
    private val mvMz = identity("mv-mz", "rpgmaker", listOf("mv", "mz"))
    private val known = listOf(mkxpZ, easyRpg, mvMz)

    @Test
    fun `sharing an engine name is not being an alternative`() {
        // RGSS, 2000/2003 and MV/MZ are different engines wearing one brand.
        // Exactly one can run a given game, so none of them is a choice.
        assertEquals(listOf(mkxpZ), OriginDirectory.alternatives("rpgmaker", "xp", known))
        assertEquals(listOf(easyRpg), OriginDirectory.alternatives("rpgmaker", "2000", known))
        assertEquals(listOf(mvMz), OriginDirectory.alternatives("rpgmaker", "mz", known))
    }

    @Test
    fun `a plugin may cover only part of an engine`() {
        // Someone else's XP-only runtime competes with mkxp-z for "xp" alone.
        val xpOnly = identity("third-party-xp", "rpgmaker", listOf("xp"))
        val withThirdParty = known + xpOnly

        assertEquals(
            listOf(mkxpZ, xpOnly),
            OriginDirectory.alternatives("rpgmaker", "xp", withThirdParty),
        )
        assertEquals(
            listOf(mkxpZ),
            OriginDirectory.alternatives("rpgmaker", "vxace", withThirdParty),
        )
    }

    @Test
    fun `an unknown context matches nothing`() {
        assertEquals(emptyList<OriginIdentity>(), OriginDirectory.alternatives("rpgmaker", "xp3", known))
    }
}
