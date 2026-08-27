package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginCapabilitiesTest {
    @Test
    fun `capability document parses exact versions and ranges`() {
        val capabilities = PluginCapabilitiesReader.parse(
            """{
                "schemaVersion": 1,
                "capabilities": [{
                    "id": "mz-1.8",
                    "engineContext": "mz",
                    "runtimeVersion": "1.8.0",
                    "supportedVersions": ["1.7.0"],
                    "supportedRanges": [{"min":"1.7.1","max":"1.8.0"}]
                }]
            }""".trimIndent(),
        )

        val capability = capabilities.single()
        assertEquals("mz", capability.engineContext)
        assertEquals(true, capability.supports(Version.parse("1.7.0")))
        assertEquals(true, capability.supports(Version.parse("1.7.5")))
        assertEquals(false, capability.supports(Version.parse("1.6.2")))
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
            null,
        )

        assertEquals("plugin.older", result!!.plugin.packageName)
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
            VersionConstraint.parse("2.0"),
        )

        assertEquals("plugin.older", allowed!!.plugin.packageName)
        assertNull(
            PluginResolver.resolve(
                listOf(wrongContext), "rpgmaker", "mv", Version.parse("1.6.2"), null,
            ),
        )
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
    ) = EngineCapability(
        id,
        context,
        Version.parse(runtimeVersion),
        emptySet(),
        if (rangeMin != null && rangeMax != null) {
            listOf(VersionRange(Version.parse(rangeMin), Version.parse(rangeMax)))
        } else {
            emptyList()
        },
    )
