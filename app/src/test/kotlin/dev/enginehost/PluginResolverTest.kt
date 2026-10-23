package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PluginResolver.resolve's trust ordering (docs/security/2026-09-24-launch-
 * trust-sandbox.md L3: "a denied bundle still resolves"). These build
 * InstalledPlugin/EngineCapability directly rather than through
 * PluginRegistry's JSON install record, since resolution itself has
 * nothing to do with how a bundle got discovered.
 */
class PluginResolverTest {
    @Test
    fun `detected version selects its installed major minor line`() {
        val selection = PluginLineSelector.select("7.8.2", listOf("7.3", "7.8", "8.1").map { VersionSeries.parse(it) })

        assertEquals(VersionSeries.parse("7.8"), selection.detectedLine)
        assertEquals(VersionSeries.parse("7.8"), selection.selectedLine)
        assertEquals("Detected game version", selection.reason)
        assertEquals(emptyList<VersionSeries>(), selection.alternatives)
    }

    @Test
    fun `uninstalled detected line is never silently replaced by nearest`() {
        val selection = PluginLineSelector.select("7.9.4", listOf("7.3", "8.1").map { VersionSeries.parse(it) })

        assertNull(selection.selectedLine)
        assertEquals(listOf("7.3", "8.1"), selection.alternatives.map { it.toString() })
        assertEquals("Detected line is not installed", selection.reason)
    }

    @Test
    fun `missing and malformed detections offer installed lines without selecting`() {
        val installed = listOf("7.3", "8.1").map { VersionSeries.parse(it) }

        listOf(null, "unknown", "7").forEach { detected ->
            val selection = PluginLineSelector.select(detected, installed)
            assertNull(selection.detectedLine)
            assertNull(selection.selectedLine)
            assertEquals(installed, selection.alternatives)
            assertEquals("Detection is missing or ambiguous", selection.reason)
        }
    }

    private fun plugin(bundleId: String, pluginVersion: String = "1.0"): InstalledPlugin = InstalledPlugin(
        info = PluginInfo(
            engine = "godot",
            pluginVersion = Version.parse(pluginVersion),
            capabilities = listOf(
                EngineCapability(
                    id = "run",
                    engineContext = DEFAULT_ENGINE_CONTEXT,
                    runtimeVersion = Version.parse("1.0"),
                    supportedVersions = setOf(Version.parse("1.0")),
                    supportedSeries = emptySet(),
                    supportedRanges = emptyList(),
                ),
            ),
        ),
        bundleId = bundleId,
        entrypointClass = "dev.example.Entry",
        signerFingerprints = setOf("A".repeat(64)),
    )

    private fun resolve(
        plugins: List<InstalledPlugin>,
        trust: Map<String, PluginTrustState>,
    ): ResolvedPlugin? = PluginResolver.resolve(
        plugins, "godot", null, Version.parse("1.0"), emptyMap(), null,
        trustOf = { trust[it.bundleId] ?: PluginTrustState.PENDING },
    )

    @Test
    fun `a denied bundle never resolves, even as the only candidate`() {
        val denied = plugin("godot-denied")

        val resolved = resolve(listOf(denied), mapOf("godot-denied" to PluginTrustState.DENIED))

        assertNull(resolved)
    }

    @Test
    fun `an approved bundle outranks a pending one with a newer version`() {
        val approved = plugin("godot-approved", pluginVersion = "1.0")
        val pendingButNewer = plugin("godot-pending", pluginVersion = "2.0")

        val resolved = resolve(
            listOf(approved, pendingButNewer),
            mapOf("godot-approved" to PluginTrustState.APPROVED, "godot-pending" to PluginTrustState.PENDING),
        )

        assertEquals("godot-approved", resolved?.plugin?.bundleId)
    }

    @Test
    fun `a denied bundle does not shadow an approved one that also matches`() {
        val approved = plugin("godot-approved")
        val denied = plugin("godot-denied", pluginVersion = "9.0")

        val resolved = resolve(
            listOf(denied, approved),
            mapOf("godot-approved" to PluginTrustState.APPROVED, "godot-denied" to PluginTrustState.DENIED),
        )

        assertEquals("godot-approved", resolved?.plugin?.bundleId)
    }

    @Test
    fun `pending still resolves when nothing approved matches, so the trust screen has something to ask about`() {
        val pending = plugin("godot-pending")

        val resolved = resolve(listOf(pending), mapOf("godot-pending" to PluginTrustState.PENDING))

        assertEquals("godot-pending", resolved?.plugin?.bundleId)
    }

    @Test
    fun `the default trust function keeps every existing caller's behaviour unchanged`() {
        val a = plugin("godot-a", pluginVersion = "1.0")
        val b = plugin("godot-b", pluginVersion = "2.0")

        val resolved = PluginResolver.resolve(listOf(a, b), "godot", null, Version.parse("1.0"), emptyMap(), null)

        // Untouched by trust (both count as approved by default): the newer
        // pluginVersion wins, exactly as before this change.
        assertEquals("godot-b", resolved?.plugin?.bundleId)
    }

    @Test
    fun `an older build of a line never runs because the newest cannot serve the game`() {
        // #604: v2 1.1.4 carried Spine itself; v2 1.2.2 loads it from a component not approved
        // yet. v1 carries Spine and is a different line, so it is the one that runs.
        val olderWithSpine = line("godot-v2", "1.1.4-1", capability("spine", mapOf("spine-godot" to "4.2")))
        val newestLoadsIt = line("godot-v2", "1.2.2-1", capability("stock", emptyMap()))
        val otherLine = line("godot-v1", "1.0.7-1", capability("spine", mapOf("spine-godot" to "4.2")))

        val resolved = PluginResolver.resolve(
            listOf(olderWithSpine, newestLoadsIt, otherLine), "godot", null, Version.parse("4.5.1"),
            mapOf("spine-godot" to Version.parse("4.2")), null,
        )

        assertEquals("godot-v1" to "1.0.7-1", resolved?.plugin?.let { it.bundleId to it.info.pluginVersion.toString() })
    }

    @Test
    fun `the allowlist still picks an older build of a line`() {
        val older = line("godot-v2", "1.1.4-1", capability("stock", emptyMap()))
        val newest = line("godot-v2", "1.2.2-1", capability("stock", emptyMap()))

        val resolved = PluginResolver.resolve(
            listOf(older, newest), "godot", null, Version.parse("4.5.1"), emptyMap(), VersionConstraint.parse("1.1.4-1"),
        )

        assertEquals("1.1.4-1", resolved?.plugin?.info?.pluginVersion?.toString())
    }

    @Test
    fun `an approved build keeps playing while its update waits for approval`() {
        val approved = line("godot-v2", "1.1.4-1", capability("stock", emptyMap()))
        val waiting = line("godot-v2", "1.2.2-1", capability("stock", emptyMap()))

        val resolved = PluginResolver.resolve(
            listOf(approved, waiting), "godot", null, Version.parse("4.5.1"), emptyMap(), null,
            trustOf = { if (it.info.pluginVersion.toString() == "1.2.2-1") PluginTrustState.PENDING else PluginTrustState.APPROVED },
        )

        assertEquals("1.1.4-1", resolved?.plugin?.info?.pluginVersion?.toString())
    }

    private fun line(bundleId: String, pluginVersion: String, capability: EngineCapability) = InstalledPlugin(
        info = PluginInfo(engine = "godot", pluginVersion = Version.parsePlugin(pluginVersion), capabilities = listOf(capability)),
        bundleId = bundleId,
        entrypointClass = "dev.example.Entry",
        signerFingerprints = setOf("A".repeat(64)),
    )

    private fun capability(id: String, components: Map<String, String>) = EngineCapability(
        id = id,
        engineContext = DEFAULT_ENGINE_CONTEXT,
        runtimeVersion = Version.parse("4.5.1"),
        supportedVersions = emptySet(),
        supportedSeries = setOf(VersionSeries.parse("4.5")),
        supportedRanges = emptyList(),
        runtimeComponents = components.mapValues { Version.parse(it.value) },
    )

    /** One bundle, one stock engine, one capability per component combination. */
    private val componentBundle = InstalledPlugin(
        info = PluginInfo(
            engine = "godot",
            pluginVersion = Version.parse("1.0"),
            capabilities = listOf(
                // Listed so the ids sort the wrong way round: selection must
                // not fall back on id order.
                capability("a-spine-4.1", mapOf("spine-godot" to "4.1")),
                capability("b-spine-4.2", mapOf("spine-godot" to "4.2")),
                capability("c-stock", emptyMap()),
            ),
        ),
        bundleId = "godot-components",
        entrypointClass = "dev.example.Entry",
        signerFingerprints = setOf("A".repeat(64)),
    )

    private fun resolveComponents(requirements: Map<String, String>): ResolvedPlugin? = PluginResolver.resolve(
        listOf(componentBundle), "godot", null, Version.parse("4.5.1"),
        requirements.mapValues { Version.parse(it.value) }, null,
    )

    @Test
    fun `a game that needs no component gets the capability that loads none`() {
        assertEquals("c-stock", resolveComponents(emptyMap())?.capability?.id)
    }

    @Test
    fun `a component requirement selects the capability carrying that exact version`() {
        assertEquals("b-spine-4.2", resolveComponents(mapOf("spine-godot" to "4.2"))?.capability?.id)
        assertEquals("a-spine-4.1", resolveComponents(mapOf("spine-godot" to "4.1"))?.capability?.id)
    }

    @Test
    fun `a component version nobody carries resolves to nothing`() {
        assertNull(resolveComponents(mapOf("spine-godot" to "4.3")))
    }
}
