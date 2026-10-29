package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date

/**
 * Which signature decides a bundle at each moment, and that the legacy
 * format is refused at every one (docs/engine-bundle-format.md "Signatures"). Certificates
 * are this run's own ([TestPki]).
 */
class BundleVerifierTest {
    private val pki = TestPki.Hierarchy()
    private val origin = "https://github.com/droidtop/enginehost-example-plugin"
    private val bundleId = "dev.enginehost.example"
    private val manifest = """{"bundleId":"$bundleId"}""".toByteArray()

    private class Sources(
        override val root: TrustAnchor.Root?,
        val custom: Map<String, ByteArray> = emptyMap(),
        val lists: Map<String, RevokedSet> = emptyMap(),
    ) : BundleTrustSources {
        override fun customKey(origin: String) = custom[origin]
        override fun revoked(catalog: String) = lists[catalog] ?: RevokedSet.NONE
    }

    private fun verify(
        signatures: BundleSignatures,
        moment: VerificationMoment,
        sources: BundleTrustSources,
        forOrigin: String = origin,
    ) = BundleVerifier.verify(manifest, forOrigin, bundleId, signatures, moment, sources)

    private fun refused(why: String, block: () -> Unit) {
        try {
            block()
            fail("accepted, but should be refused: $why")
        } catch (expected: IllegalArgumentException) {
        } catch (expected: java.security.GeneralSecurityException) {
        }
    }

    private fun officialJws(leaf: TestPki.Issued = pki.leaf()) = TestPki.jws(manifest, leaf)

    @Test
    fun `a root-issued certificate for the origin is official`() {
        val leaf = pki.leaf()
        val signer = verify(BundleSignatures(officialJws(leaf)), VerificationMoment.CATALOG_INSTALL, Sources(pki.anchor))
        assertEquals(SignerKind.OFFICIAL, signer.kind)
        assertEquals(1, signer.format)
        assertEquals(sha256(leaf.keys.public.encoded), signer.keySha256)
        assertEquals(pki.anchor.keySha256, signer.anchorKeySha256)
    }

    @Test
    fun `a legacy-only bundle is refused at every moment, a launch included, root pinned or not`() {
        val legacyOnly = BundleSignatures(null)
        for (sources in listOf(Sources(pki.anchor), Sources(root = null))) {
            for (moment in VerificationMoment.values()) {
                try {
                    verify(legacyOnly, moment, sources)
                    fail("a legacy-only bundle was accepted at $moment")
                } catch (refusal: IllegalArgumentException) {
                    // At launch the reason tells the person what to do.
                    if (moment == VerificationMoment.LAUNCH) assertEquals(BundleVerifier.LEGACY_INSTALLED, refusal.message)
                }
            }
        }
    }

    @Test
    fun `with nothing pinned, a v1 bundle has nothing to verify it to`() {
        refused("a v1-only official bundle with nothing to verify it to") {
            verify(BundleSignatures(officialJws()), VerificationMoment.CATALOG_INSTALL, Sources(root = null))
        }
    }

    @Test
    fun `a bad v1 signature is refused`() {
        val sources = Sources(pki.anchor)
        val other = TestPki.jws("""{"bundleId":"dev.enginehost.other"}""".toByteArray(), pki.leaf())
        for (moment in VerificationMoment.values()) {
            refused("bad JWS at $moment") { verify(BundleSignatures(other), moment, sources) }
        }
    }

    @Test
    fun `the certificate must name the bundle's origin and cover its ID`() {
        val sources = Sources(pki.anchor)
        refused("a certificate for another origin") {
            verify(BundleSignatures(officialJws(pki.leaf(origin = "droidtop/enginehost-other-plugin"))), VerificationMoment.CATALOG, sources)
        }
        val narrowed = pki.leaf(extraUris = listOf("urn:droidtop:plugin:dev.enginehost.godot"))
        refused("a certificate for another plugin of the origin") {
            verify(BundleSignatures(officialJws(narrowed)), VerificationMoment.CATALOG, sources)
        }
    }

    @Test
    fun `the developer's certificate installs from a file only`() {
        val developer = TestPki.issue("CN=Developer", TestPki.ec(), pki.root, uris = listOf("urn:enginehost:developer"))
        val signatures = BundleSignatures(TestPki.jws(manifest, developer))
        val sources = Sources(pki.anchor)
        assertEquals(SignerKind.DEVELOPER, verify(signatures, VerificationMoment.FILE_INSTALL, sources).kind)
        assertEquals(SignerKind.DEVELOPER, verify(signatures, VerificationMoment.LAUNCH, sources).kind)
        refused("developer build from a catalog") { verify(signatures, VerificationMoment.CATALOG, sources) }
        refused("developer build downloaded for a catalog entry") { verify(signatures, VerificationMoment.CATALOG_INSTALL, sources) }
    }

    @Test
    fun `a repository a person added is anchored at the key they pinned`() {
        val custom = "https://github.com/someone/their-plugin"
        val publisher = TestPki.ec()
        val certificate = TestPki.issue("CN=Publisher", publisher, uris = listOf("urn:droidtop:origin:someone/their-plugin"))
        val signatures = BundleSignatures(TestPki.jws(manifest, certificate))
        val sources = Sources(pki.anchor, custom = mapOf(custom to publisher.public.encoded))
        val signer = verify(signatures, VerificationMoment.CATALOG_INSTALL, sources, forOrigin = custom)
        assertEquals(SignerKind.CUSTOM, signer.kind)
        assertEquals(sha256(publisher.public.encoded), signer.anchorKeySha256)
        // Without the root pinned the person's own pin still anchors it.
        assertEquals(SignerKind.CUSTOM, verify(signatures, VerificationMoment.CATALOG, Sources(null, custom = mapOf(custom to publisher.public.encoded)), forOrigin = custom).kind)
        refused("a pinned key does not reach another origin") {
            verify(signatures, VerificationMoment.CATALOG, Sources(null, custom = mapOf(origin to publisher.public.encoded)))
        }
        // The added repository's own revocation list applies to it.
        refused("revoked by the repository's list") {
            verify(
                signatures, VerificationMoment.LAUNCH,
                Sources(pki.anchor, custom = mapOf(custom to publisher.public.encoded),
                    lists = mapOf("someone/their-plugin" to RevokedSet.of(emptyList(), listOf(sha256(publisher.public.encoded))))),
                forOrigin = custom,
            )
        }
    }

    @Test
    fun `Enginehost's list revokes an official signer, at launch too`() {
        val leaf = pki.leaf()
        val revoked = Sources(pki.anchor, lists = mapOf("enginehost" to RevokedSet.of(listOf(sha256(leaf.der)), emptyList())))
        for (moment in VerificationMoment.values()) {
            refused("revoked at $moment") { verify(BundleSignatures(officialJws(leaf)), moment, revoked) }
        }
    }

    @Test
    fun `an expired leaf verifies at every moment, and a revoked one at none`() {
        val expired = TestPki.issue(
            "CN=expired", TestPki.ec(), pki.root, uris = listOf("urn:droidtop:origin:droidtop/enginehost-example-plugin"),
            notBefore = Date(System.currentTimeMillis() - 10_000_000_000), notAfter = Date(System.currentTimeMillis() - 1_000_000),
        )
        val signatures = BundleSignatures(TestPki.jws(manifest, expired))
        val sources = Sources(pki.anchor)
        val revoked = Sources(pki.anchor, lists = mapOf("enginehost" to RevokedSet.of(listOf(sha256(expired.der)), emptyList())))
        for (moment in VerificationMoment.values()) {
            assertEquals(SignerKind.OFFICIAL, verify(signatures, moment, sources).kind)
            refused("revoked at $moment") { verify(signatures, moment, revoked) }
        }
    }

    @Test
    fun `a signer is revoked by its leaf, its chain, or its anchor`() {
        val signer = verify(BundleSignatures(officialJws()), VerificationMoment.CATALOG, Sources(pki.anchor))
        assertTrue(signer.isRevokedBy(RevokedSet.of(emptyList(), listOf(signer.keySha256.lowercase()))))
        assertTrue(signer.isRevokedBy(RevokedSet.of(signer.chainCertSha256, emptyList())))
        assertTrue(signer.isRevokedBy(RevokedSet.of(emptyList(), listOf(pki.anchor.keySha256))))
        assertEquals(false, signer.isRevokedBy(RevokedSet.of(emptyList(), listOf("00".repeat(32)))))
        assertEquals(signer, BundleSigner.fromJson(signer.toJson()))
    }
}
