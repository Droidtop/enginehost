package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComponentRequestsTest {
    private fun component(bundleId: String, version: String, series: List<String>, build: String = "1.0.0-1") = InstalledPlugin(
        info = PluginInfo(engine = "godot", pluginVersion = Version.parsePlugin(build), capabilities = emptyList()),
        bundleId = bundleId,
        entrypointClass = "",
        component = ComponentInfo("godot", "spine-godot", Version.parse(version), series.map(VersionSeries::parse).toSet()),
    )

    private val v42 = component("spine-42", "4.2", listOf("4.2", "4.3", "4.4", "4.5"))
    private val v41 = component("spine-41", "4.1", listOf("4.2", "4.3"))

    @Test
    fun theVersionTheGameCallsForIsTheOneGiven() {
        val chosen = ComponentRequests.pick(listOf(v42, v41), { true }, "godot", "spine-godot", Version.parse("4.1"), Version.parse("4.3.0"))
        assertEquals("spine-41", chosen?.bundleId)
    }

    @Test
    fun withNoVersionSaidTheNewestForThisEngineIsGiven() {
        val chosen = ComponentRequests.pick(listOf(v41, v42), { true }, "godot", "spine-godot", null, Version.parse("4.3.0"))
        assertEquals("spine-42", chosen?.bundleId)
    }

    @Test
    fun nothingOutsideTheEnginesSeriesOrNotApproved() {
        assertNull(ComponentRequests.pick(listOf(v42), { true }, "godot", "spine-godot", null, Version.parse("4.6.3")))
        assertNull(ComponentRequests.pick(listOf(v42), { false }, "godot", "spine-godot", null, Version.parse("4.5.1")))
        assertNull(ComponentRequests.pick(listOf(v42), { true }, "godot", "other", null, Version.parse("4.5.1")))
    }
}
