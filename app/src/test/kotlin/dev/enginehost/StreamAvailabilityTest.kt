package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counts and switch offers behind "No plugins on the Stable channel
 * yet. Testing has N, Unstable has M." A fresh install defaults to Stable,
 * and nothing is published there before 1.0 -- this is what tells the
 * screen there is somewhere else to look instead of just an empty store.
 */
class StreamAvailabilityTest {
    private fun manifest(bundleId: String): EngineBundleManifest = EngineBundleManifest(
        rawBytes = ByteArray(0),
        assetName = "$bundleId.enginehost.tar.xz",
        bundleId = bundleId,
        info = PluginInfo("renpy", Version.parse("1"), emptyList()),
        apiVersion = dev.enginehost.api.EnginePluginContract.API_VERSION,
        entrypoint = "dev.enginehost.renpy.Plugin",
        origin = "https://github.com/droidtop/enginehost-renpy-plugin",
        publicKeySpki = ByteArray(0),
        signingKeySha256 = "AB".repeat(32),
        dexFiles = listOf("classes.dex"),
        resourceApks = emptyList(),
        runtimeTransport = RUNTIME_TRANSPORT_PLUGIN,
        payloadSha256 = "CD".repeat(32),
        files = emptyList(),
    )

    private fun plugin(bundleId: String, stream: PluginStream): AvailablePlugin =
        AvailablePlugin(manifest(bundleId), ByteArray(0), "v1", "https://example.invalid/$bundleId", null, stream)

    @Test
    fun `count cascades down to steadier streams`() {
        val all = listOf(plugin("a", PluginStream.STABLE), plugin("b", PluginStream.TESTING))
        assertEquals(1, StreamAvailability.count(all, PluginStream.STABLE))
        assertEquals(2, StreamAvailability.count(all, PluginStream.TESTING))
        assertEquals(2, StreamAvailability.count(all, PluginStream.UNSTABLE))
    }

    @Test
    fun `count is one per bundle, not per build`() {
        val all = listOf(plugin("a", PluginStream.STABLE), plugin("a", PluginStream.STABLE))
        assertEquals(1, StreamAvailability.count(all, PluginStream.STABLE))
    }

    @Test
    fun `a fresh Stable install with only testing releases offers a switch to testing`() {
        val all = listOf(plugin("a", PluginStream.TESTING), plugin("b", PluginStream.TESTING))
        val offer = StreamAvailability.moreAdventurousWithPlugins(all, PluginStream.STABLE)
        assertEquals(listOf(PluginStream.TESTING to 2, PluginStream.UNSTABLE to 2), offer)
    }

    @Test
    fun `unstable-only releases still offer a switch, skipping the empty testing stream`() {
        val all = listOf(plugin("a", PluginStream.UNSTABLE))
        val offer = StreamAvailability.moreAdventurousWithPlugins(all, PluginStream.STABLE)
        assertEquals(listOf(PluginStream.UNSTABLE to 1), offer)
    }

    @Test
    fun `nothing on any stream offers nothing to switch to`() {
        assertTrue(StreamAvailability.moreAdventurousWithPlugins(emptyList(), PluginStream.STABLE).isEmpty())
    }

    @Test
    fun `a stream that already shows something has nothing to offer`() {
        val all = listOf(plugin("a", PluginStream.STABLE), plugin("b", PluginStream.UNSTABLE))
        assertTrue(StreamAvailability.moreAdventurousWithPlugins(all, PluginStream.STABLE).isEmpty())
    }

    @Test
    fun `unstable chosen has nothing more adventurous to offer even when empty`() {
        assertTrue(StreamAvailability.moreAdventurousWithPlugins(emptyList(), PluginStream.UNSTABLE).isEmpty())
    }
}
