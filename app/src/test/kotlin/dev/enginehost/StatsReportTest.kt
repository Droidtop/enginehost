package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StatsReportTest {
    private fun report(endpoint: String = "https://api.openai.com/v1", rating: Int = 4, model: String = "gpt-4o-mini", comment: String? = null) =
        StatsReport.of(
            StatsReport.Kind.TRANSLATION, model, null, StatsReport.backendOf(endpoint), "English",
            StatsReport.Purpose.VISUAL_NOVELS, rating, comment, "8", "qualcomm", "1-3s",
        )

    @Test
    fun `a report says which kind of service, never its address`() {
        assertEquals("openai", StatsReport.backendOf("https://api.openai.com/v1"))
        assertEquals("openrouter", StatsReport.backendOf("https://openrouter.ai/api/v1/chat/completions"))
        assertEquals("local-network", StatsReport.backendOf("http://192.168.1.20:11434/v1"))
        assertEquals("local-network", StatsReport.backendOf("http://10.0.0.5/v1"))
        assertEquals("local-network", StatsReport.backendOf("http://172.16.4.2:1234"))
        assertEquals("local-network", StatsReport.backendOf("http://localhost:1234/v1"))
        assertEquals("local-network", StatsReport.backendOf("http://[::1]:8080"))
        assertEquals("local-network", StatsReport.backendOf("http://[fd12:3456::1]:8080"))
        assertEquals("local-network", StatsReport.backendOf("http://llm-box.local:8080"))
        assertEquals("other-service", StatsReport.backendOf("https://my-own-server.example.com/v1"))
        assertEquals("other-service", StatsReport.backendOf("https://8.8.8.8/v1"))
        assertEquals("other-service", StatsReport.backendOf("not a url"))
        // A look-alike is not the service it imitates.
        assertEquals("other-service", StatsReport.backendOf("https://api.openai.com.evil.example/v1"))

        val json = report("http://192.168.1.20:11434/v1")!!.toJson()
        assertFalse(json.contains("192.168"))
        assertEquals("local-network", JSONObject(json).getString("backend"))
    }

    @Test
    fun `the person's text is cleaned and cut`() {
        assertEquals("a b c", StatsReport.clean("  a\u0000b\n\tc  ", 50))
        assertNull(StatsReport.clean(" \n ", 50))
        assertEquals(StatsReport.MAX_COMMENT, report(comment = "x".repeat(500))!!.comment!!.length)
        assertNull(report(rating = 0))
        assertNull(report(rating = 6))
        assertNull(report(model = "   "))
    }

    @Test
    fun `the device is described only in coarse classes`() {
        val gb = 1024L * 1024 * 1024
        assertEquals("<=4", StatsReport.ramTier(3 * gb))
        assertEquals("6", StatsReport.ramTier(5 * gb + gb / 2))
        assertEquals("8", StatsReport.ramTier(7 * gb + gb / 2))
        assertEquals("12+", StatsReport.ramTier(16 * gb))
        assertEquals("qualcomm", StatsReport.socFamily(" Qualcomm "))
        assertEquals("unknown", StatsReport.socFamily(null))
        assertEquals("unknown", StatsReport.socFamily("unknown"))
        assertNull(StatsReport.speedBucket(null))
        assertEquals("<1s", StatsReport.speedBucket(400))
        assertEquals("3-10s", StatsReport.speedBucket(4_000))
        assertEquals(">10s", StatsReport.speedBucket(60_000))
    }

    @Test
    fun `the document follows schema 1`() {
        val json = JSONObject(report(comment = "Good with names")!!.toJson())
        assertEquals(1, json.getInt("schema"))
        assertEquals("translation", json.getString("kind"))
        assertEquals("gpt-4o-mini", json.getJSONObject("model").getString("id"))
        assertEquals("visual_novels", json.getString("purpose"))
        assertEquals(4, json.getInt("rating"))
        assertEquals("8", json.getJSONObject("device").getString("ramTier"))
        assertEquals("1-3s", json.getString("speed"))
        assertEquals(
            setOf("schema", "kind", "model", "backend", "language", "purpose", "rating", "comment", "device", "speed"),
            json.keys().asSequence().toSet(),
        )
    }
}
