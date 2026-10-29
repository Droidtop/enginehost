package dev.enginehost

import dev.droidtop.shared.version.Version
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
        BundleSignatures(null),
        BundleSigner("AB".repeat(32), SignerKind.OFFICIAL, format = 0, anchorKeySha256 = "AB".repeat(32)),
        "renpy-8.2-v1",
        "https://example.invalid/bundle",
        null,
        stream = PluginStream.STABLE,
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
    fun `with several builds of a line installed, an update is measured against the newest`() {
        // 1 and 3 installed side by side: 2 is older than the newest, so it
        // is not an update; 4 is, and it is offered once, not once per build.
        val updates = PluginUpdates.updatesFor(
            listOf(installed(pluginVersion = "1"), installed(pluginVersion = "3")),
            listOf(available(manifest(pluginVersion = "2")), available(manifest(pluginVersion = "4"))),
        )
        assertEquals(1, updates.size)
        assertEquals(Version.parse("4"), updates.values.single().info.pluginVersion)
        assertTrue(
            PluginUpdates.updatesFor(
                listOf(installed(pluginVersion = "1"), installed(pluginVersion = "3")),
                listOf(available(manifest(pluginVersion = "2"))),
            ).isEmpty(),
        )
    }

    @Test
    fun `newestPerLine keeps one build per bundle and repository`() {
        val newest = PluginUpdates.newestPerLine(
            listOf(
                installed(pluginVersion = "1"), installed(pluginVersion = "3"), installed(pluginVersion = "2"),
                installed(bundleId = "dev.enginehost.renpy.8_5.v1", pluginVersion = "1"),
            ),
        )
        assertEquals(2, newest.size)
        assertEquals(Version.parse("3"), newest.single { it.bundleId == "dev.enginehost.renpy.8_2.v1" }.info.pluginVersion)
    }

    @Test
    fun `the same build number of the same line is the same build, whatever its archive`() {
        assertTrue(PluginUpdates.isSameBuild(installed(pluginVersion = "2"), manifest(pluginVersion = "2")))
        assertFalse(PluginUpdates.isSameBuild(installed(pluginVersion = "1"), manifest(pluginVersion = "2")))
        assertFalse(
            PluginUpdates.isSameBuild(
                installed(pluginVersion = "2"),
                manifest(pluginVersion = "2", origin = "https://github.com/someone/fork"),
            ),
        )
        assertTrue(PluginUpdates.isSameLine(installed(pluginVersion = "9"), manifest(pluginVersion = "1")))
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

    @Test
    fun `a core with only the old signature is replaced by its line's newest build, the same version included`() {
        // Droidtop/tracker#774: the AVD's cores, installed with the old signature, need one press that fixes them.
        val legacy = installed(pluginVersion = "2").copy(directory = java.io.File("/bundles/renpy-legacy"))
        val replacements = PluginUpdates.replacementsFor(
            listOf(legacy),
            listOf(available(manifest(pluginVersion = "2")), available(manifest(pluginVersion = "1"))),
        )
        assertEquals("2", replacements.getValue("/bundles/renpy-legacy").info.pluginVersion.toString())
    }

    @Test
    fun `a v1-signed core needs no replacement, and another line is never one`() {
        val signed = installed().copy(
            signer = BundleSigner("AB".repeat(32), SignerKind.OFFICIAL, format = 1, anchorKeySha256 = "AB".repeat(32)),
        )
        assertTrue(PluginUpdates.replacementsFor(listOf(signed), listOf(available(manifest(pluginVersion = "3")))).isEmpty())
        assertTrue(
            PluginUpdates.replacementsFor(listOf(installed()), listOf(available(manifest(bundleId = "dev.enginehost.renpy.8_5.v1")))).isEmpty(),
        )
    }
}
