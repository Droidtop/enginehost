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
        assertEquals("saves.locations", points.getJSONObject(0).getString("point"))
    }

    @Test
    fun withNoBrokerThereIsNothingToCall() {
        assertFalse(DroidtopLink.isConnected())
        assertEquals(null, DroidtopLink.call("saves", "where", JSONObject()))
    }
}
