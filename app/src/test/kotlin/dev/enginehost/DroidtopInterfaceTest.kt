package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidtopInterfaceTest {
    @Test
    fun repliesUseTheBrokerEnvelope() {
        val ok = JSONObject(DroidtopInterfaceService.ok(JSONObject().put("games", 0)))
        assertTrue(ok.getBoolean("ok"))
        assertEquals(0, ok.getJSONObject("data").getInt("games"))

        val error = JSONObject(DroidtopInterfaceService.error("UNSUPPORTED", "no"))
        assertFalse(error.getBoolean("ok"))
        assertEquals("UNSUPPORTED", error.getJSONObject("error").getString("code"))
    }

    @Test
    fun theManifestAssetIsAnAppInterfaceManifestForThisService() {
        val manifest = JSONObject(java.io.File("src/main/assets/droidtop-plugin.json").readText())
        assertEquals("app_interface", manifest.getString("kind"))
        assertEquals("dev.enginehost", manifest.getJSONObject("app").getString("package"))
        assertEquals(DroidtopInterfaceService::class.java.name, manifest.getJSONObject("app").getString("service"))
        assertTrue(manifest.getInt("contractVersion") >= 2)
        val points = manifest.getJSONArray("provides")
        assertEquals("saves.roots", points.getJSONObject(0).getString("point"))
    }

    @Test
    fun rootsNameOnlyWhatDiffersAndHostNamedFolders() {
        val default = java.io.File("/storage/emulated/0/Enginehost/saves")
        val reply = SaveRoots.answer(
            default = default,
            families = mapOf(
                "renpy" to java.io.File("/storage/emulated/0/Enginehost/saves"),
                "godot" to java.io.File("/storage/ABCD-1234/Games/saves"),
            ),
            games = listOf(
                "html-1" to java.io.File("/storage/emulated/0/Enginehost/saves/html/My Game"),
                "renpy-1" to null,
            ),
        )
        assertEquals(default.absolutePath, reply.getString("default"))
        val families = reply.getJSONObject("families")
        assertEquals(1, families.length())
        assertEquals("/storage/ABCD-1234/Games/saves", families.getString("godot"))
        val games = reply.getJSONArray("games")
        assertEquals("html-1", games.getJSONObject(0).getString("entryId"))
        assertEquals("/storage/emulated/0/Enginehost/saves/html/My Game", games.getJSONObject(0).getString("folder"))
        assertFalse(games.getJSONObject(1).has("folder"))
    }

    @Test
    fun withNoBrokerThereIsNothingToCall() {
        assertFalse(DroidtopLink.isConnected())
        assertEquals(null, DroidtopLink.call("saves", "where", JSONObject()))
    }
}
