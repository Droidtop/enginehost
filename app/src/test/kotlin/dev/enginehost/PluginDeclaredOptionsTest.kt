package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a bundle manifest's advisory `declaredOptions` array. */
class PluginDeclaredOptionsTest {
    private fun manifest(vararg entries: JSONObject) = JSONObject().put(
        "declaredOptions",
        JSONArray().apply { entries.forEach(::put) },
    )

    @Test
    fun `a file option carries its optional mime hint`() {
        val parsed = DeclaredOptionsReader.parse(
            manifest(
                JSONObject()
                    .put("key", "midiSoundFont")
                    .put("label", "SoundFont")
                    .put("type", "file")
                    .put("mimeTypes", JSONArray().put("application/octet-stream")),
            ),
        ).single()

        assertEquals("file", parsed.type)
        assertEquals(listOf("application/octet-stream"), parsed.mimeTypes)
    }

    @Test
    fun `the mime hint stays optional`() {
        val parsed = DeclaredOptionsReader.parse(
            manifest(JSONObject().put("key", "customScript").put("type", "file")),
        ).single()

        assertEquals("file", parsed.type)
        assertEquals(emptyList<String>(), parsed.mimeTypes)
        // No label declared, so the key stands in for one.
        assertEquals("customScript", parsed.label)
    }

    @Test
    fun `declarations written before the file type keep meaning what they meant`() {
        val parsed = DeclaredOptionsReader.parse(
            manifest(
                JSONObject().put("key", "rtpPaths").put("type", "path").put("repeats", true),
                JSONObject().put("key", "soundfont"),
                JSONObject()
                    .put("key", "rgssVersion")
                    .put("type", "choice")
                    .put("choices", JSONArray().put(JSONObject().put("value", "1").put("label", "RGSS1"))),
            ),
        ).associateBy { it.key }

        assertEquals("path", parsed.getValue("rtpPaths").type)
        assertTrue(parsed.getValue("rtpPaths").repeats)
        assertEquals("string", parsed.getValue("soundfont").type)
        assertEquals(listOf("1" to "RGSS1"), parsed.getValue("rgssVersion").choices)
        assertTrue(parsed.values.all { it.mimeTypes.isEmpty() })
    }

    @Test
    fun `an unrecognised type is kept rather than rejected`() {
        val parsed = DeclaredOptionsReader.parse(
            manifest(JSONObject().put("key", "somethingNewer").put("type", "colour")),
        ).single()

        assertEquals("colour", parsed.type)
    }

    @Test
    fun `a manifest declaring nothing declares nothing`() {
        assertTrue(DeclaredOptionsReader.parse(JSONObject()).isEmpty())
    }
}
