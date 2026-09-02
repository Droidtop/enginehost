package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUpdatesTest {
    private fun manifest(
        bundleId: String = "dev.enginehost.renpy.8_2.v1",
        origin: String = "https://github.com/droidtop/enginehost-renpy-plugin",
        pluginVersion: String = "2",
        apiVersion: Int = dev.enginehost.api.EnginePluginContract.API_VERSION,
    ): EngineBundleManifest = EngineBundleManifest(
        rawBytes = ByteArray(0),
        assetName = "$bundleId.enginehost.tar.xz",
        bundleId = bundleId,
        info = PluginInfo("renpy", Version.parse(pluginVersion), emptyList()),
        apiVersion = apiVersion,
        entrypoint = "dev.enginehost.renpy.Plugin",
        origin = origin,
        publicKeySpki = ByteArray(0),
        signingKeySha256 = "AB".repeat(32),
        dexFiles = listOf("classes.dex"),
        resourceApks = emptyList(),
        runtimeTransport = RUNTIME_TRANSPORT_PLUGIN,
        payloadSha256 = "CD".repeat(32),
        files = emptyList(),
    )

    private fun installed(
        bundleId: String = "dev.enginehost.renpy.8_2.v1",
        origin: String = "https://github.com/droidtop/enginehost-renpy-plugin",
        pluginVersion: String = "1",
    ): InstalledPlugin = InstalledPlugin(
        PluginInfo("renpy", Version.parse(pluginVersion), emptyList()),
        bundleId,
        "dev.enginehost.renpy.Plugin",
        origin,
    )

    private fun available(manifest: EngineBundleManifest): AvailablePlugin = AvailablePlugin(
        manifest,
        ByteArray(0),
        "renpy-8.2-v1",
        "https://example.invalid/bundle",
        null,
        prerelease = false,
    )

    @Test
    fun `a strictly newer build of the same bundle from the same origin is an update`() {
        assertTrue(PluginUpdates.isNewerBuildOf(installed(pluginVersion = "1"), manifest(pluginVersion = "2")))
    }

    @Test
    fun `the same or an older build is not an update`() {
        assertFalse(PluginUpdates.isNewerBuildOf(installed(pluginVersion = "2"), manifest(pluginVersion = "2")))
        assertFalse(PluginUpdates.isNewerBuildOf(installed(pluginVersion = "3"), manifest(pluginVersion = "2")))
    }

    @Test
    fun `a different bundle id is never an update, even for the same engine`() {
        assertFalse(
            PluginUpdates.isNewerBuildOf(
                installed(bundleId = "dev.enginehost.renpy.8_2.v1", pluginVersion = "1"),
                manifest(bundleId = "dev.enginehost.renpy.8_3.v1", pluginVersion = "2"),
            ),
        )
    }

    @Test
    fun `a different origin publishing the same bundle id is not an update`() {
        assertFalse(
            PluginUpdates.isNewerBuildOf(
                installed(pluginVersion = "1"),
                manifest(origin = "https://github.com/somebody-else/enginehost-renpy-plugin", pluginVersion = "2"),
            ),
        )
    }

    @Test
    fun `updatesFor returns the newest build per installed bundle and skips other api versions`() {
        val plugin = installed(pluginVersion = "1")
        val updates = PluginUpdates.updatesFor(
            listOf(plugin),
            listOf(
                available(manifest(pluginVersion = "2")),
                available(manifest(pluginVersion = "4")),
                available(manifest(pluginVersion = "3")),
                available(manifest(pluginVersion = "9", apiVersion = 999)),
            ),
        )
        assertEquals(setOf(plugin.bundleId), updates.keys)
        assertEquals(Version.parse("4"), updates.getValue(plugin.bundleId).info.pluginVersion)
    }

    @Test
    fun `updatesFor is empty when nothing newer is published`() {
        assertTrue(
            PluginUpdates.updatesFor(
                listOf(installed(pluginVersion = "2")),
                listOf(available(manifest(pluginVersion = "2")), available(manifest(pluginVersion = "1"))),
            ).isEmpty(),
        )
    }
}
