package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class EngineBundleManifestTest {
    @Test
    fun `signed P-256 manifest verifies and exposes compatibility`() {
        val keys = keyPair("secp256r1")
        val raw = manifest(keys.public.encoded)
        val parsed = EngineBundleManifestReader.parse(raw)
        EngineBundleManifestReader.verifySignature(parsed, sign(keys.private, raw))

        assertEquals("dev.enginehost.renpy.8_3.v1", parsed.bundleId)
        assertEquals("renpy", parsed.info.engine)
        assertEquals(Version.parse("8.3.2"), parsed.info.capabilities.single().runtimeVersion)
    }

    @Test
    fun `signature does not survive a manifest change`() {
        val keys = keyPair("secp256r1")
        val raw = manifest(keys.public.encoded)
        val signature = sign(keys.private, raw)
        val changed = raw.toString(Charsets.UTF_8).replace("8.3.2", "8.3.3").toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            EngineBundleManifestReader.verifySignature(EngineBundleManifestReader.parse(changed), signature)
        }
    }

    @Test
    fun `non P-256 signer is rejected`() {
        val keys = keyPair("secp384r1")
        val raw = manifest(keys.public.encoded)

        assertThrows(IllegalArgumentException::class.java) {
            EngineBundleManifestReader.verifySignature(EngineBundleManifestReader.parse(raw), sign(keys.private, raw))
        }
    }

    private fun keyPair(curve: String) = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(curve))
        generateKeyPair()
    }

    private fun sign(privateKey: java.security.PrivateKey, bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }

    private fun manifest(publicKey: ByteArray): ByteArray {
        val payloadDigest = java.security.MessageDigest.getInstance("SHA-256").run {
            update("classes.dex".toByteArray())
            update(0)
            update("0".toByteArray())
            update(0)
            digest().joinToString("") { "%02X".format(it) }
        }
        return JSONObject()
            .put("formatVersion", 1)
            .put("assetName", "renpy-8.3-v1.enginehost.tar.xz")
            .put("bundleId", "dev.enginehost.renpy.8_3.v1")
            .put("engine", "renpy")
            .put("pluginVersion", "1.0.0")
            .put("apiVersion", 1)
            .put("entrypoint", "dev.enginehost.renpy.Plugin")
            .put("origin", "https://github.com/bi0shacker001/enginehost-renpy-plugin")
            .put(
                "signing",
                JSONObject()
                    .put("algorithm", "SHA256withECDSA")
                    .put("publicKeySpki", Base64.getEncoder().encodeToString(publicKey))
                    .put("keySha256", sha256(publicKey)),
            )
            .put("dexFiles", JSONArray().put("classes.dex"))
            .put("payloadSha256", payloadDigest)
            .put(
                "capabilities",
                JSONArray().put(
                    JSONObject()
                        .put("id", "renpy-8.3.2")
                        .put("engineContext", "python3")
                        .put("runtimeVersion", "8.3.2"),
                ),
            )
            .put(
                "files",
                JSONArray().put(
                    JSONObject()
                        .put("path", "classes.dex")
                        .put("size", 0)
                        .put("sha256", sha256(ByteArray(0)))
                        .put("mode", 292),
                ),
            )
            .toString()
            .toByteArray()
    }
}
