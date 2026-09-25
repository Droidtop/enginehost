package dev.enginehost

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An official origin's key changes only by the root's certificate with a
 * higher serial (docs/security/2026-09-25-third-party-catalog.md, T7, T10).
 * The root here is generated per run: the real one never leaves droidtop-dev.
 */
class OriginKeyRotationTest {
    private val origin = "https://github.com/droidtop/enginehost-example-plugin"

    private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val rootPair = p256()
    private val root = JSONObject()
        .put("formatVersion", 1)
        .put("id", "enginehost-official-v1")
        .put("algorithm", "SHA256withECDSA")
        .put("publicKeySpki", Base64.getEncoder().encodeToString(rootPair.public.encoded))
        .put("keySha256", sha256(rootPair.public.encoded))

    /** What scripts/certify-repository-key.py writes, signed by this run's root. */
    private fun certified(serial: Int, key: KeyPair = p256(), forOrigin: String = origin): JSONObject {
        val spki = Base64.getEncoder().encodeToString(key.public.encoded)
        val fingerprint = sha256(key.public.encoded)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(rootPair.private)
        signer.update(OriginKeyDocuments.signedIdentity(forOrigin, "SHA256withECDSA", spki, fingerprint, serial))
        val issuer = JSONObject()
            .put("id", "enginehost-official-v1")
            .put("algorithm", "SHA256withECDSA")
            .put("keySha256", root.getString("keySha256"))
            .put("signature", Base64.getEncoder().encodeToString(signer.sign()))
        if (serial > 0) issuer.put("serial", serial)
        return JSONObject()
            .put("formatVersion", 1)
            .put("origin", forOrigin)
            .put("algorithm", "SHA256withECDSA")
            .put("publicKeySpki", spki)
            .put("keySha256", fingerprint)
            .put("issuer", issuer)
    }

    @Test
    fun `a first key is serial 0 and a replacement carries its generation`() {
        assertEquals(0, OriginKeyDocuments.parseCertified(certified(0).toString(), root).serial)
        assertEquals(2, OriginKeyDocuments.parseCertified(certified(2).toString(), root).serial)
    }

    @Test
    fun `every compiled-in key reads as serial 0`() {
        val real = JSONObject(File("src/main/res/raw/official_plugin_root_key.json").readText())
        val keys = JSONObject(File("src/main/res/raw/default_plugin_keys.json").readText()).getJSONArray("keys")
        for (index in 0 until keys.length()) {
            assertEquals(0, OriginKeyDocuments.parseCertified(keys.getJSONObject(index).toString(), real).serial)
        }
    }

    @Test
    fun `the serial is signed, so raising it breaks the certificate`() {
        val document = certified(1)
        document.getJSONObject("issuer").put("serial", 5)
        assertTrue(runCatching { OriginKeyDocuments.parseCertified(document.toString(), root) }.isFailure)
        // Nor can a serial be added to a first-key certificate.
        val first = certified(0)
        first.getJSONObject("issuer").put("serial", 1)
        assertTrue(runCatching { OriginKeyDocuments.parseCertified(first.toString(), root) }.isFailure)
    }

    @Test
    fun `a replacement certificate is for one origin only`() {
        val elsewhere = certified(3, forOrigin = "https://github.com/droidtop/enginehost-other-plugin")
        elsewhere.put("origin", origin)
        assertTrue(runCatching { OriginKeyDocuments.parseCertified(elsewhere.toString(), root) }.isFailure)
    }

    @Test
    fun `only a higher serial moves an origin to a new key`() {
        val current = OriginKeyDocuments.parseCertified(certified(1).toString(), root)
        val decide = { offered: PluginOriginKey -> OfficialKeyRotation.decide(current, offered) }
        assertEquals(OfficialKeyRotation.Decision.NEW_ORIGIN, OfficialKeyRotation.decide(null, current))
        assertEquals(OfficialKeyRotation.Decision.UNCHANGED, decide(current))
        assertEquals(
            OfficialKeyRotation.Decision.ROTATE,
            decide(OriginKeyDocuments.parseCertified(certified(2).toString(), root)),
        )
        // The same serial with another key, and any older serial -- an old
        // or leaked key replayed into the index -- are refused.
        assertEquals(
            OfficialKeyRotation.Decision.REFUSE,
            decide(OriginKeyDocuments.parseCertified(certified(1).toString(), root)),
        )
        assertEquals(
            OfficialKeyRotation.Decision.REFUSE,
            decide(OriginKeyDocuments.parseCertified(certified(0).toString(), root)),
        )
    }

    @Test
    fun `a signer the device has not pinned is reported as a changed key`() {
        val failure = CatalogFailures.of(RuntimeException(SignerChangedException(origin, "ab12")))
        assertEquals(CatalogFailure.KeyChanged("AB12"), failure)
    }
}
