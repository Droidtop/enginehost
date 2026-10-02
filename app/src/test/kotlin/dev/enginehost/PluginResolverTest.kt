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
}
