package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCapabilitiesTest {
    @Test
    fun versionAgnosticCapabilityAcceptsEveryNumericEngineVersion() {
        val capability = PluginCapabilitiesReader.parse(
            """{"capabilities":[{"id":"kirikiri","runtimeVersion":"0.1.0","acceptsAnyEngineVersion":true}]}""",
        ).single()

        assertTrue(capability.supports(Version.parse("1")))
        assertTrue(capability.supports(Version.parse("999.123.456")))
    }

    @Test
    fun `capability document parses exact versions series and ranges`() {
        val capabilities = PluginCapabilitiesReader.parse(
            """{
                "schemaVersion": 1,
                "capabilities": [{
                    "id": "mz-1.8",
                    "engineContext": "mz",
                    "runtimeVersion": "1.8.0",
                    "runtimeComponents": {"ruby":"1.9.2"},
                    "supportedVersions": ["1.7.0"],
                    "supportedSeries": ["8.2"],
                    "supportedRanges": [{"min":"1.7.1","max":"1.8.0"}]
                }]
            }""".trimIndent(),
        )

        val capability = capabilities.single()
        assertEquals("mz", capability.engineContext)
        assertEquals(true, capability.supports(Version.parse("1.7.0")))
        assertEquals(true, capability.supports(Version.parse("1.7.5")))
        assertEquals(false, capability.supports(Version.parse("1.6.2")))
        assertEquals(true, capability.supports(Version.parse("8.2.999.24090902")))
        assertEquals(false, capability.supports(Version.parse("8.3.0")))
        assertEquals(Version.parse("1.9.2"), capability.runtimeComponents["ruby"])
    }

    @Test
    fun `duplicate capability ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginCapabilitiesReader.parse(
                """{"capabilities":[
                    {"id":"same","runtimeVersion":"1.0"},
                    {"id":"same","runtimeVersion":"2.0"}
                ]}""",
            )
        }
    }

    @Test
    fun `resolver matches family and context and prefers exact bundled runtime`() {
        val compatibleNewer = plugin(
            packageName = "plugin.newer",
            pluginVersion = "3.0",
            capability = capability("mz-compatible", "mz", "1.8", "1.7", "1.8"),
        )
        val exactOlder = plugin(
            packageName = "plugin.older",
            pluginVersion = "2.0",
            capability = capability("mz-exact", "mz", "1.7"),
        )

        val result = PluginResolver.resolve(
            listOf(compatibleNewer, exactOlder),
            "rpgmaker",
            "mz",
            Version.parse("1.7.0"),
            emptyMap(),
            null,
        )

        assertEquals("plugin.older", result!!.plugin.bundleId)
        assertEquals("mz-exact", result.capability.id)
    }

    @Test
    fun `resolver uses newest allowed plugin build and never crosses context`() {
        val older = plugin("plugin.older", "2.0", capability("mv", "mv", "1.6.2"))
        val newer = plugin("plugin.newer", "3.0", capability("mv", "mv", "1.6.2"))
        val wrongContext = plugin("plugin.mz", "4.0", capability("mz", "mz", "1.6.2"))

        val allowed = PluginResolver.resolve(
            listOf(older, newer, wrongContext),
            "rpgmaker",
            "mv",
            Version.parse("1.6.2"),
            emptyMap(),
            VersionConstraint.parse("2.0"),
        )

        assertEquals("plugin.older", allowed!!.plugin.bundleId)
        assertNull(
            PluginResolver.resolve(
                listOf(wrongContext), "rpgmaker", "mv", Version.parse("1.6.2"), emptyMap(), null,
            ),
        )
    }

    @Test
    fun `resolver requires an exact requested embedded runtime`() {
        val ruby19 = plugin(
            "plugin.ruby19",
            "1.0",
            capability("vxace-ruby19", "vxace", "3.0", components = mapOf("ruby" to "1.9.2")),
        )
        val ruby31 = plugin(
            "plugin.ruby31",
            "2.0",
            capability("vxace-ruby31", "vxace", "3.0", components = mapOf("ruby" to "3.1.3")),
        )

        val result = PluginResolver.resolve(
            listOf(ruby31, ruby19),
            "rpgmaker",
            "vxace",
            Version.parse("3.0"),
            mapOf("ruby" to Version.parse("1.9.2")),
            null,
        )

        assertEquals("plugin.ruby19", result!!.plugin.bundleId)
    }

    @Test
    fun `versions with trailing zeroes are equal`() {
        assertEquals(Version.parse("1"), Version.parse("1.0.0"))
    }

    private fun plugin(
        packageName: String,
        pluginVersion: String,
        capability: EngineCapability,
    ) = InstalledPlugin(
        PluginInfo("rpgmaker", Version.parse(pluginVersion), listOf(capability)),
        packageName,
        "RunActivity",
    )

    private fun capability(
        id: String,
        context: String,
        runtimeVersion: String,
        rangeMin: String? = null,
        rangeMax: String? = null,
        components: Map<String, String> = emptyMap(),
    ) = EngineCapability(
        id,
        null,
        context,
        Version.parse(runtimeVersion),
        emptySet(),
        emptySet(),
        if (rangeMin != null && rangeMax != null) {
            listOf(VersionRange(Version.parse(rangeMin), Version.parse(rangeMax)))
        } else {
            emptyList()
        },
        components.mapValues { Version.parse(it.value) },
    )
}
