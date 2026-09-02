package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginRegistryTest {
    private fun tempRoot(): File = createTempDir(prefix = "registry-test").also { it.deleteOnExit() }

    private fun writeInstallRecord(directory: File, bundleId: String) {
        directory.mkdirs()
        val record = JSONObject()
            .put("formatVersion", 1)
            .put("bundleId", bundleId)
            .put("engine", "godot")
            .put("pluginVersion", "1.0")
            .put("apiVersion", 1)
            .put("entrypoint", "dev.example.Entry")
            .put("origin", "https://github.com/example/repo")
            .put("signingKeySha256", "A".repeat(64))
            .put("archiveSha256", "B".repeat(64))
            .put("dexFiles", JSONArray().put("classes.dex"))
            .put("resourceApks", JSONArray())
            .put("runtimeTransport", RUNTIME_TRANSPORT_PLUGIN)
            .put("capabilities", JSONArray())
        File(directory, PluginRegistry.INSTALL_RECORD).writeText(record.toString())
    }

    @Test
    fun `discoverIn finds a real installed bundle directory`() {
        val root = tempRoot()
        writeInstallRecord(File(root, "godot--abc123"), "godot")

        val discovered = PluginRegistry.discoverIn(root)

        assertEquals(1, discovered.size)
        assertEquals("godot", discovered.single().bundleId)
    }

    @Test
    fun `discoverIn ignores a staging directory even with a fully written install record`() {
        // Regression test for the race this fix closes: EngineBundleInstaller.install
        // writes the install record into its staging directory before the final
        // rename. If the process is killed in that exact window, the leftover
        // staging directory is otherwise indistinguishable from a real install --
        // the staging-prefix filter in discoverIn is what stops it from being
        // loaded as one.
        val root = tempRoot()
        writeInstallRecord(File(root, STAGING_PREFIX + "deadbeef"), "godot")

        val discovered = PluginRegistry.discoverIn(root)

        assertTrue(discovered.isEmpty())
    }

    @Test
    fun `discoverIn still finds a real bundle alongside a leaked staging directory`() {
        val root = tempRoot()
        writeInstallRecord(File(root, "godot--abc123"), "godot")
        writeInstallRecord(File(root, STAGING_PREFIX + "deadbeef"), "renpy")

        val discovered = PluginRegistry.discoverIn(root)

        assertEquals(listOf("godot"), discovered.map { it.bundleId })
    }
}
