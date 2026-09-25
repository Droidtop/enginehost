package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DetectedConfigTest {
    private val detection = EngineDetection(
        engine = "rpgmaker",
        engineContext = "vxace",
        engineVersion = "1.0",
        execFile = "Game.exe",
        evidence = "test",
    )

    @Test
    fun `a caller's identifying facts win over detection`() {
        val inline = JSONObject()
            .put("engine", "rpgmaker").put("engineContext", "vx").put("engineVersion", "1.2")
            .put("title", "Named by the launcher")
            .put("runtimeRequirements", JSONObject().put("ruby", "1.9.2"))
        val document = DetectedConfig.documentFor(detection, "Folder", inline.toString())!!
        assertEquals("vx", document.getString("engineContext"))
        assertEquals("1.2", document.getString("engineVersion"))
        assertEquals("Named by the launcher", document.getString("title"))
        assertEquals("1.9.2", document.getJSONObject("runtimeRequirements").getString("ruby"))
    }

    @Test
    fun `the detected document keeps a caller's options and file choices out`() {
        val inline = JSONObject()
            .put("options", JSONObject().put("customScript", "/storage/emulated/0/Download/x.rb"))
            .put("execFile", "Other.exe")
            .put("saveFolder", "Elsewhere")
            .put("pluginVersion", "0.0.1")
        val document = DetectedConfig.documentFor(detection, "Folder", inline.toString())!!
        assertFalse(document.has("options"))
        assertFalse(document.has("pluginVersion"))
        assertEquals("Game.exe", document.getString("execFile"))
        assertFalse(document.optString("saveFolder") == "Elsewhere")
    }

    @Test
    fun `a launch runs on the detected document with the caller's options for that launch only`() {
        val inline = JSONObject()
            .put("options", JSONObject().put("customScript", "/storage/emulated/0/Download/x.rb"))
            .put("execFile", "Other.exe")
        val launch = JSONObject(DetectedConfig.launchDocument(detection, "Folder", inline.toString())!!)
        assertEquals("rpgmaker", launch.getString("engine"))
        assertEquals("1.0", launch.getString("engineVersion"))
        // Detection answers what it read; the caller fills what it left open.
        assertEquals("Game.exe", launch.getString("execFile"))
        assertEquals("/storage/emulated/0/Download/x.rb", launch.getJSONObject("options").getString("customScript"))
    }

    @Test
    fun `a folder that leaves a required field open has no launch document`() {
        val unversioned = detection.copy(engineVersion = null)
        assertEquals(null, DetectedConfig.launchDocument(unversioned, "Folder", null))
    }
}
