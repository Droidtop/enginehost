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
        LegacyBundleSignatures.verifyRaw(raw, parsed.legacyKeySpki!!, sign(keys.private, raw))

        assertEquals("dev.enginehost.renpy.8_3.v1", parsed.bundleId)
        assertEquals("renpy", parsed.info.engine)
        assertEquals(Version.parse("8.3.2"), parsed.info.capabilities.single().runtimeVersion)
        assertEquals(RUNTIME_TRANSPORT_PLUGIN, parsed.runtimeTransport)
    }

    @Test
    fun `lockdown is read from the manifest, on either transport, and counts as sandboxed`() {
        // docs/engine-bundle-format.md "lockdown"; owner, 2026-10-17.
        val keys = keyPair("secp256r1")
        val plain = EngineBundleManifestReader.parse(manifest(keys.public.encoded))
        assertEquals(false, plain.lockdown)
        assertEquals(false, plain.sandboxed)
        for (transport in listOf(RUNTIME_TRANSPORT_PLUGIN, RUNTIME_TRANSPORT_ACTIVITY)) {
            val raw = JSONObject(manifest(keys.public.encoded).toString(Charsets.UTF_8))
                .put("lockdown", true).put("runtimeTransport", transport).toString().toByteArray()
            val parsed = EngineBundleManifestReader.parse(raw)
            assertEquals(true, parsed.lockdown)
            assertEquals(false, parsed.isolatable)
            assertEquals(true, parsed.sandboxed)
        }
    }

    @Test
    fun `gameWrites travels from the manifest into the install record`() {
        val raw = JSONObject(manifest(keyPair("secp256r1").public.encoded).toString(Charsets.UTF_8))
            .put("gameWrites", JSONArray(listOf("game/saves", "game/cache"))).toString().toByteArray()
        val parsed = EngineBundleManifestReader.parse(raw)
        assertEquals(listOf("game/saves", "game/cache"), parsed.gameWrites)

        val signer = BundleSigner("B".repeat(64), SignerKind.OFFICIAL, format = 1, anchorKeySha256 = "C".repeat(64))
        val record = parsed.installedRecord("A".repeat(64), signer)
        assertEquals(listOf("game/saves", "game/cache"), GameWrites.parse(record.getJSONArray("gameWrites")))
    }

    @Test
    fun `signature does not survive a manifest change`() {
        val keys = keyPair("secp256r1")
        val raw = manifest(keys.public.encoded)
        val signature = sign(keys.private, raw)
        val changed = raw.toString(Charsets.UTF_8).replace("8.3.2", "8.3.3").toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            LegacyBundleSignatures.verifyRaw(changed, EngineBundleManifestReader.parse(changed).legacyKeySpki!!, signature)
        }
    }

    @Test
    fun `a manifest signed only in format v1 names no legacy key`() {
        // Its signer is the certificate in its JWS (docs/engine-bundle-format.md "Signatures").
        val raw = JSONObject(manifest(keyPair("secp256r1").public.encoded).toString(Charsets.UTF_8))
            .apply { remove("signing") }.toString().toByteArray()
        val parsed = EngineBundleManifestReader.parse(raw)
        assertEquals(null, parsed.legacyKeySpki)
        assertEquals(null, parsed.legacyKeySha256)
    }

    @Test
    fun `non P-256 signer is rejected`() {
        val keys = keyPair("secp384r1")
        val raw = manifest(keys.public.encoded)

        assertThrows(IllegalArgumentException::class.java) {
            LegacyBundleSignatures.verifyRaw(raw, EngineBundleManifestReader.parse(raw).legacyKeySpki!!, sign(keys.private, raw))
        }
    }

    @Test
    fun `carriedAbis counts only the payload's top-level lib ABI directories`() {
        // The check at install refuses what the runtime's own loader could
        // not resolve, so the derivation has to match that loader exactly:
        // dex and resource APKs are not ABIs, and libraries nested under
        // components/<id>/<version>/lib/<abi>/ are resolved by the engine
        // wrapper at launch, not by the host's native library path -- the
        // x86_64 directory inside the spine component here must not make
        // this an x86_64-capable bundle.
        val keys = keyPair("secp256r1")
        val parsed = EngineBundleManifestReader.parse(
            manifest(
                keys.public.encoded,
                listOf(
                    "classes.dex",
                    "lib/arm64-v8a/libengine.so",
                    "lib/arm64-v8a/libSDL2.so",
                    "runtime.apk",
                    "components/spine/1.2/lib/x86_64/libspine.so",
                ),
            ),
        )

        assertEquals(listOf("arm64-v8a"), parsed.carriedAbis)
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

    private fun manifest(publicKey: ByteArray): ByteArray = manifest(publicKey, listOf("classes.dex"))

    private fun manifest(publicKey: ByteArray, paths: List<String>): ByteArray {
        val payloadDigest = java.security.MessageDigest.getInstance("SHA-256").run {
            paths.forEach { path ->
                update(path.toByteArray())
                update(0)
                update("0".toByteArray())
                update(0)
            }
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
            .put("origin", "https://github.com/droidtop/enginehost-renpy-plugin")
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
                JSONArray().apply {
                    paths.forEach { path ->
                        put(
                            JSONObject()
                                .put("path", path)
                                .put("size", 0)
                                .put("sha256", sha256(ByteArray(0)))
                                .put("mode", 292),
                        )
                    }
                },
            )
            .toString()
            .toByteArray()
    }
}
