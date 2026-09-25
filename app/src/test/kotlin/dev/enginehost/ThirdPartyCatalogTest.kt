package dev.enginehost

import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may say an origin is official, and what a third-party listing needs
 * before its key is pinned (docs/security/2026-09-25-third-party-catalog.md).
 */
class ThirdPartyCatalogTest {
    private val root = JSONObject(File("src/main/res/raw/official_plugin_root_key.json").readText())

    /** A fresh P-256 key's document for [origin]; generated per run, never a stored secret. */
    private fun keyDocument(origin: String): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val der = generator.generateKeyPair().public.encoded
        return JSONObject()
            .put("formatVersion", 1)
            .put("origin", origin)
            .put("algorithm", "SHA256withECDSA")
            .put("publicKeySpki", Base64.getEncoder().encodeToString(der))
            .put("keySha256", sha256(der))
            .toString()
    }

    private fun listing(origin: String, key: String, trust: IndexedTrust = IndexedTrust.THIRD_PARTY) = IndexedOrigin(
        origin = origin,
        repo = origin.removePrefix("https://github.com/"),
        releases = emptyList(),
        trust = trust,
        keyDocument = key,
        maintainer = IndexedMaintainer("someone", "Some One"),
    )

    @Test
    fun `every compiled-in key carries the root's certificate for its own origin`() {
        val keys = JSONObject(File("src/main/res/raw/default_plugin_keys.json").readText()).getJSONArray("keys")
        for (index in 0 until keys.length()) {
            val raw = keys.getJSONObject(index).toString()
            assertEquals(OriginKeyDocuments.parse(raw).origin, OriginKeyDocuments.parseCertified(raw, root).origin)
        }
    }

    @Test
    fun `a certificate names one origin, so it cannot be carried to another`() {
        val official = JSONObject(File("src/main/res/raw/default_plugin_keys.json").readText())
            .getJSONArray("keys").getJSONObject(0)
        val moved = JSONObject(official.toString()).put("origin", "https://github.com/someone/enginehost-renpy-plugin")
        assertTrue(runCatching { OriginKeyDocuments.parseCertified(moved.toString(), root) }.isFailure)
    }

    @Test
    fun `a key with no certificate is never official, whatever the index calls it`() {
        val key = keyDocument("https://github.com/droidtop/enginehost-new-plugin")
        assertTrue(runCatching { OriginKeyDocuments.parseCertified(key, root) }.isFailure)
        // An issuer block it wrote itself changes nothing.
        val claimed = JSONObject(key).put(
            "issuer",
            JSONObject().put("id", root.getString("id")).put("algorithm", "SHA256withECDSA")
                .put("keySha256", root.getString("keySha256")).put("signature", "MEUCIQ=="),
        )
        assertTrue(runCatching { OriginKeyDocuments.parseCertified(claimed.toString(), root) }.isFailure)
    }

    @Test
    fun `a listed repository is added only when its own key is the listed key`() {
        val origin = "https://github.com/someone/enginehost-example-plugin"
        val key = keyDocument(origin)
        val accepted = ThirdPartyListing.accept(listing(origin, key), key)
        assertEquals(origin, accepted.origin)
        assertEquals("Some One", accepted.maintainerName)
        assertEquals(OriginKeyDocuments.parse(key).fingerprint, accepted.keySha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a repository publishing another key than the list names is refused`() {
        val origin = "https://github.com/someone/enginehost-example-plugin"
        ThirdPartyListing.accept(listing(origin, keyDocument(origin)), keyDocument(origin))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a key for another repository is refused`() {
        val origin = "https://github.com/someone/enginehost-example-plugin"
        val elsewhere = keyDocument("https://github.com/someone/other-plugin")
        ThirdPartyListing.accept(listing(origin, elsewhere), elsewhere)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an official entry is not quick-added as third party`() {
        val origin = "https://github.com/droidtop/enginehost-example-plugin"
        val key = keyDocument(origin)
        ThirdPartyListing.accept(listing(origin, key, IndexedTrust.OFFICIAL), key)
    }

    @Test
    fun `the index says who publishes each origin and with which key`() {
        val origin = "https://github.com/someone/enginehost-example-plugin"
        val key = keyDocument(origin)
        val parsed = PluginCatalogIndex.parse(
            """
            {"schemaVersion": 1, "generatedAt": "2026-09-25T12:00:00Z", "origins": [
             {"repo": "someone/enginehost-example-plugin", "origin": "$origin", "trust": "third-party",
              "maintainer": {"id": "someone", "name": "Some One"}, "key": $key, "releases": []},
             {"repo": "Droidtop/enginehost-cmvs-plugin", "releases": []}
            ]}
            """.trimIndent(),
        )
        val listed = parsed.origins.getValue(origin)
        assertEquals(IndexedTrust.THIRD_PARTY, listed.trust)
        assertEquals(IndexedMaintainer("someone", "Some One"), listed.maintainer)
        assertEquals(OriginKeyDocuments.parse(key).fingerprint, OriginKeyDocuments.parse(listed.keyDocument!!).fingerprint)
        // An index from before trust levels existed still reads.
        val older = parsed.origins.getValue("https://github.com/droidtop/enginehost-cmvs-plugin")
        assertNull(older.trust)
        assertNull(older.keyDocument)
        assertNotNull(older.releases)
    }
}
