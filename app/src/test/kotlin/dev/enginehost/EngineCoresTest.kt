package dev.enginehost

import dev.droidtop.shared.version.Version
import dev.droidtop.shared.version.VersionSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The core list droidtop reads over the application interface (Droidtop/tracker#752). */
class EngineCoresTest {
    private fun renpy(pluginVersion: String) = EngineCores.Build(
        "dev.enginehost.renpy.8_3.v1",
        PluginInfo(
            "renpy",
            Version.parse(pluginVersion),
            listOf(
                EngineCapability(
                    "renpy-8.3-v1", null, "standard", Version.parse("8.3.7"),
                    emptySet(), setOf(VersionSeries.parse("8.3")), emptyList(),
                ),
            ),
        ),
    )

    private val rows = listOf(
        EngineRow("renpy", emptyList(), "renpy", "standard", emptyMap()),
        EngineRow("godot", emptyList(), "godot", "standard", emptyMap()),
        EngineRow("unity", emptyList(), null, null, emptyMap()),
    )

    @Test
    fun anInstalledCoreWithANewerBuildOnOffer() {
        val cores = EngineCores.entries(listOf(renpy("1.0.14")), listOf(renpy("1.0.15")), rows)
        assertEquals(1, cores.length())
        val core = cores.getJSONObject(0)
        assertEquals("dev.enginehost.renpy.8_3.v1", core.getString("id"))
        assertEquals("renpy", core.getString("engine"))
        assertEquals("standard", core.getJSONArray("contexts").getString(0))
        assertEquals("8.3", core.getJSONObject("engineVersions").getJSONArray("series").getString(0))
        assertFalse(core.getJSONObject("engineVersions").getBoolean("any"))
        // The detection hints: only the database rows this core serves.
        assertEquals(1, core.getJSONArray("rows").length())
        assertEquals("renpy", core.getJSONArray("rows").getString(0))
        assertTrue(core.getBoolean("installed"))
        assertEquals("1.0.14", core.getString("installedVersion"))
        assertEquals("1.0.15", core.getString("availableVersion"))
        assertTrue(core.getBoolean("updateAvailable"))
    }

    @Test
    fun aCoreOnlyInTheCatalogIsInstallable() {
        val core = EngineCores.entries(emptyList(), listOf(renpy("1.0.15")), rows).getJSONObject(0)
        assertFalse(core.getBoolean("installed"))
        assertFalse(core.has("installedVersion"))
        assertEquals("1.0.15", core.getString("availableVersion"))
        assertFalse(core.getBoolean("updateAvailable"))
    }

    @Test
    fun anInstalledCoreTheCatalogNoLongerListsHasNoAvailableVersion() {
        val core = EngineCores.entries(listOf(renpy("1.0.15")), emptyList(), rows).getJSONObject(0)
        assertTrue(core.getBoolean("installed"))
        assertFalse(core.has("availableVersion"))
        assertFalse(core.getBoolean("updateAvailable"))
    }
}
