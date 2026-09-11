package dev.enginehost

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugins index is droidtop-platforms' one-file answer to "what has
 * every plugin repository published", fetched from raw.githubusercontent.com
 * so the usual refresh spends none of GitHub's 60-requests-an-hour API
 * allowance.
 */
class PluginCatalogIndexTest {
    private val index = """
        {
         "schemaVersion": 1,
         "generatedAt": "2026-09-11T04:00:00Z",
         "origins": [
          {
           "repo": "Droidtop/enginehost-renpy-plugin",
           "origin": "https://github.com/Droidtop/enginehost-renpy-plugin",
           "releases": [
            {
             "tag": "renpy-8_3-v1-unstable",
             "stream": "unstable",
             "published_at": "2026-09-10T22:13:59Z",
             "assets": [
              {
               "name": "enginehost-release.json",
               "url": "https://github.com/Droidtop/enginehost-renpy-plugin/releases/download/renpy-8_3-v1-unstable/enginehost-release.json",
               "size": 4096,
               "sha256": "AA11"
              },
              {
               "name": "dev.enginehost.renpy.8_3.v1.enginehost.tar.xz",
               "url": "https://github.com/Droidtop/enginehost-renpy-plugin/releases/download/renpy-8_3-v1-unstable/dev.enginehost.renpy.8_3.v1.enginehost.tar.xz",
               "size": 41000000,
               "sha256": "sha256:BB22"
              }
             ]
            },
            {
             "tag": "renpy-8_3-v1",
             "published_at": "2026-09-01T10:00:00Z",
             "assets": []
            }
           ]
          },
          {
           "repo": "Droidtop/enginehost-cmvs-plugin",
           "releases": []
          }
         ]
        }
    """.trimIndent()

    @Test
    fun `origins are keyed the way every other origin here is keyed`() {
        val parsed = PluginCatalogIndex.parse(index)
        // Lower-cased, no trailing slash: the same normalization the cache,
        // the key store and the origin list use, or an index entry would
        // never match the origin it describes.
        assertTrue("https://github.com/droidtop/enginehost-renpy-plugin" in parsed.origins)
        // An entry may leave the URL out; the repository name says it.
        assertEquals(
            "https://github.com/droidtop/enginehost-cmvs-plugin",
            parsed.origins["https://github.com/droidtop/enginehost-cmvs-plugin"]?.origin,
        )
        assertEquals(Instant.parse("2026-09-11T04:00:00Z"), parsed.generatedAt)
    }

    @Test
    fun `a release carries its stream, its time and its assets`() {
        val releases = PluginCatalogIndex.parse(index)
            .origins.getValue("https://github.com/droidtop/enginehost-renpy-plugin").releases
        assertEquals(2, releases.size)
        val newest = releases.first()
        assertEquals("renpy-8_3-v1-unstable", newest.tag)
        assertEquals(PluginStream.UNSTABLE, newest.stream)
        assertEquals("2026-09-10T22:13:59Z", newest.publishedAt)
        assertEquals(2, newest.assets.size)
        val envelope = newest.assets.first { it.name == "enginehost-release.json" }
        assertEquals(4096L, envelope.size)
        assertEquals("aa11", envelope.sha256)
        // GitHub writes its own digests as "sha256:<hex>"; both spellings mean one thing.
        assertEquals("bb22", newest.assets.first { it.name.endsWith(".tar.xz") }.sha256)
    }

    @Test
    fun `a release without a stream is read as stable, like an envelope without a channel`() {
        val older = PluginCatalogIndex.parse(index)
            .origins.getValue("https://github.com/droidtop/enginehost-renpy-plugin").releases[1]
        assertEquals(PluginStream.STABLE, older.stream)
        assertTrue(older.assets.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an index from a newer schema is refused rather than guessed at`() {
        PluginCatalogIndex.parse("""{"schemaVersion": 2, "origins": []}""")
    }

    @Test
    fun `an index with no timestamp cannot be shown to be current`() {
        // fetch() treats that as no index at all and the origins fall back to
        // the API; parse() still reads it, because the two are separate jobs.
        assertNull(PluginCatalogIndex.parse("""{"schemaVersion": 1, "origins": []}""").generatedAt)
    }
}
