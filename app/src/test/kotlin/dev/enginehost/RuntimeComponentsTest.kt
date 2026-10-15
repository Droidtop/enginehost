package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Separately installed runtime components (docs/engine-bundle-format.md,
 * "Runtime components (subplugins)"): how a component bundle's manifest is
 * read, and how resolution picks the component a game needs.
 */
class RuntimeComponentsTest {
    @Test
    fun `a component manifest parses with its licence and no code of its own`() {
        val parsed = EngineBundleManifestReader.parse(componentManifest())

        val component = requireNotNull(parsed.component)
        assertEquals("godot", component.engine)
        assertEquals("spine-godot", component.name)
        assertEquals(Version.parse("4.2"), component.version)
        assertEquals(setOf("4.2", "4.5"), component.supportedSeries.map { it.toString() }.toSet())
        assertEquals(BundleLicense("Spine Runtimes License", "LICENSE-spine-runtimes.txt"), parsed.license)
        assertTrue(parsed.dexFiles.isEmpty())
        assertTrue(parsed.info.capabilities.isEmpty())
        assertEquals(listOf("arm64-v8a", "x86_64"), parsed.carriedAbis)
    }

    @Test
    fun `a component bundle must carry its licence in the signed payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineBundleManifestReader.parse(componentManifest(license = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineBundleManifestReader.parse(componentManifest(licenseFile = "NOT-IN-PAYLOAD.txt"))
        }
    }

    @Test
    fun `a component bundle carries no dex and serves no game`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineBundleManifestReader.parse(componentManifest(dex = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineBundleManifestReader.parse(componentManifest(capability = true))
        }
    }

    @Test
    fun `the install record round-trips the component and its licence`() {
        val parsed = EngineBundleManifestReader.parse(componentManifest())
        val signer = BundleSigner("B".repeat(64), SignerKind.OFFICIAL, format = 1, anchorKeySha256 = "C".repeat(64))
        val record = parsed.installedRecord("A".repeat(64), signer)

        assertEquals(BUNDLE_KIND_COMPONENT, record.getString("kind"))
        // The signer verified at install travels with the record; every launch checks it again.
        assertEquals("B".repeat(64), record.getString("signingKeySha256"))
        assertEquals(signer, BundleSigner.fromJson(record.getJSONObject("signer")))
        assertEquals("spine-godot", record.getJSONObject("component").getString("name"))
        assertEquals("LICENSE-spine-runtimes.txt", record.getJSONObject("license").getString("file"))
    }

    @Test
    fun `a game needing Spine resolves only once the component is installed`() {
        val engine = engine()
        val requirements = mapOf("spine-godot" to Version.parse("4.2"))

        assertNull(resolve(listOf(engine), requirements, components = emptyList()))

        val spine = component("4.2", setOf("4.5"))
        val resolved = requireNotNull(resolve(listOf(engine), requirements, components = listOf(spine)))
        assertEquals(listOf(spine), resolved.components)
        assertEquals(mapOf("spine-godot" to Version.parse("4.2")), resolved.runtimeComponents)
    }

    @Test
    fun `a component built for other engine versions does not serve this one`() {
        val spine = component("4.2", setOf("4.2", "4.3"))
        val requirements = mapOf("spine-godot" to Version.parse("4.2"))

        assertNull(resolve(listOf(engine()), requirements, components = listOf(spine)))
        assertEquals(
            requirements,
            ComponentSelection.missing("godot", engine().info.capabilities.single(), requirements, listOf(spine)),
        )
    }

    @Test
    fun `a game that needs nothing loads no component even when one is installed`() {
        val resolved = requireNotNull(resolve(listOf(engine()), emptyMap(), components = listOf(component("4.2", setOf("4.5")))))

        assertTrue(resolved.components.isEmpty())
        assertTrue(resolved.runtimeComponents.isEmpty())
    }

    @Test
    fun `a component bundle is never resolved as an engine`() {
        val spine = component("4.2", setOf("4.5"))

        assertNull(resolve(listOf(spine), emptyMap(), components = emptyList()))
    }

    @Test
    fun `a capability that cannot load the component is not satisfied by installing it`() {
        val plain = engine(loads = emptySet())
        val requirements = mapOf("spine-godot" to Version.parse("4.2"))

        assertNull(resolve(listOf(plain), requirements, components = listOf(component("4.2", setOf("4.5")))))
        assertTrue(ComponentSelection.missing("godot", plain.info.capabilities.single(), requirements, emptyList()).isEmpty())
    }

    @Test
    fun `loadsComponents is read from the capability document`() {
        val capability = PluginCapabilitiesReader.parse(
            JSONObject().put(
                "capabilities",
                JSONArray().put(
                    JSONObject().put("id", "godot-4.5-standard-v2").put("engineContext", "standard")
                        .put("runtimeVersion", "4.5.1").put("supportedSeries", JSONArray().put("4.5"))
                        .put("loadsComponents", JSONArray().put("spine-godot")),
                ),
            ).toString(),
        ).single()

        assertEquals(setOf("spine-godot"), capability.loadableComponents)
        assertTrue(capability.canServe(mapOf("spine-godot" to Version.parse("4.2"))))
        assertTrue(!capability.satisfies(mapOf("spine-godot" to Version.parse("4.2"))))
    }

    private fun resolve(
        plugins: List<InstalledPlugin>,
        requirements: Map<String, Version>,
        components: List<InstalledPlugin>,
    ): ResolvedPlugin? = PluginResolver.resolve(
        plugins, "godot", "standard", Version.parse("4.5.1"), requirements, null, components = components,
    )

    private fun engine(loads: Set<String> = setOf("spine-godot")) = InstalledPlugin(
        info = PluginInfo(
            engine = "godot",
            pluginVersion = Version.parse("1.1.0"),
            capabilities = listOf(
                EngineCapability(
                    id = "godot-4.5-standard-v2",
                    engineContext = "standard",
                    runtimeVersion = Version.parse("4.5.1"),
                    supportedVersions = emptySet(),
                    supportedSeries = setOf(VersionSeries.parse("4.5")),
                    supportedRanges = emptyList(),
                    loadableComponents = loads,
                ),
            ),
        ),
        bundleId = "dev.enginehost.godot.v45.v2",
        entrypointClass = "dev.enginehost.plugin.godot.GodotEnginePlugin",
        signerFingerprints = setOf("A".repeat(64)),
    )

    private fun component(version: String, series: Set<String>) = InstalledPlugin(
        info = PluginInfo("godot", Version.parse("1.0.0"), emptyList()),
        bundleId = "dev.enginehost.godot.component.spine-godot.v1",
        entrypointClass = "",
        signerFingerprints = setOf("A".repeat(64)),
        component = ComponentInfo("godot", "spine-godot", Version.parse(version), series.map(VersionSeries::parse).toSet()),
        license = BundleLicense("Spine Runtimes License", "LICENSE-spine-runtimes.txt"),
    )

    private fun componentManifest(
        license: String? = "Spine Runtimes License",
        licenseFile: String = "LICENSE-spine-runtimes.txt",
        dex: Boolean = false,
        capability: Boolean = false,
    ): ByteArray {
        val paths = listOf(
            "LICENSE-spine-runtimes.txt",
            "godot-4.5/spine-godot.gdextension",
            "lib/arm64-v8a/godot-4.5/libspine_godot.so",
            "lib/x86_64/godot-4.5/libspine_godot.so",
        )
        val payloadDigest = java.security.MessageDigest.getInstance("SHA-256").run {
            paths.forEach { path ->
                update(path.toByteArray())
                update(0)
                update("0".toByteArray())
                update(0)
            }
            digest().joinToString("") { "%02X".format(it) }
        }
        val publicKey = java.security.KeyPairGenerator.getInstance("EC").run {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }.public.encoded
        return JSONObject()
            .put("formatVersion", 1)
            .put("kind", "component")
            .put("assetName", "dev.enginehost.godot.component.spine-godot.v1.enginehost.tar.xz")
            .put("bundleId", "dev.enginehost.godot.component.spine-godot.v1")
            .put("engine", "godot")
            .put("pluginVersion", "1.0.0")
            .put("apiVersion", 1)
            .put("origin", "https://github.com/droidtop/enginehost-godot-plugin")
            .put(
                "signing",
                JSONObject()
                    .put("algorithm", "SHA256withECDSA")
                    .put("publicKeySpki", Base64.getEncoder().encodeToString(publicKey))
                    .put("keySha256", sha256(publicKey)),
            )
            .put(
                "component",
                JSONObject().put("engine", "godot").put("name", "spine-godot").put("version", "4.2")
                    .put("supportedSeries", JSONArray().put("4.2").put("4.5")),
            )
            .apply { license?.let { put("license", JSONObject().put("name", it).put("file", licenseFile)) } }
            .apply { if (dex) put("dexFiles", JSONArray().put("LICENSE-spine-runtimes.txt")) }
            .apply {
                if (capability) {
                    put(
                        "capabilities",
                        JSONArray().put(JSONObject().put("id", "x").put("runtimeVersion", "4.5.1")),
                    )
                }
            }
            .put("payloadSha256", payloadDigest)
            .put(
                "files",
                JSONArray().apply {
                    paths.forEach { put(JSONObject().put("path", it).put("size", 0).put("sha256", "0".repeat(64))) }
                },
            )
            .toString()
            .toByteArray()
    }
}
