package dev.enginehost

import dev.droidtop.shared.version.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * The Enginehost root rotation of 2026-11-04 (Droidtop/tracker#876, #550): the new root is trusted with no end and the old
 * one until [EXPIRY] (owner: "Pin the old key for 7 days, then expire it"). Both roots here are this run's own throwaway
 * keys ([TestPki]); no real key or certificate is used.
 */
class KeyRotationTest {
    private val oldPki = TestPki.Hierarchy()
    private val newPki = TestPki.Hierarchy()
    private val origin = "https://github.com/droidtop/enginehost-example-plugin"
    private val bundleId = "dev.enginehost.example"
    private val manifest = """{"bundleId":"$bundleId"}""".toByteArray()

    private companion object {
        const val EXPIRY = "2026-11-11T23:59:59Z"
        val expirySecond: Long = Instant.parse(EXPIRY).epochSecond
        val atExpiry: Long = expirySecond * 1000
        val aSecondLater: Long = (expirySecond + 1) * 1000
    }

    private val oldRoot = oldPki.anchorUntil(expirySecond)
    private val newRoot = newPki.anchor

    private class Sources(
        override val roots: List<TrustAnchor.Root>,
        private val clock: Long,
        val lists: Map<String, RevokedSet> = emptyMap(),
    ) : BundleTrustSources {
        override fun now() = clock
        override fun customKey(origin: String): ByteArray? = null
        override fun revoked(catalog: String) = lists[catalog] ?: RevokedSet.NONE
    }

    private fun sources(now: Long, lists: Map<String, RevokedSet> = emptyMap()) = Sources(listOf(newRoot, oldRoot), now, lists)

    private fun verify(jws: String, moment: VerificationMoment, sources: BundleTrustSources) =
        BundleVerifier.verify(manifest, origin, bundleId, BundleSignatures(jws), moment, sources)

    private fun refused(why: String, block: () -> Unit) {
        try {
            block()
            fail("accepted, but should be refused: $why")
        } catch (expected: IllegalArgumentException) {
        } catch (expected: java.security.GeneralSecurityException) {
        }
    }

    @Test
    fun `an old-root chain is accepted through the last second and refused one second later, at every moment`() {
        val jws = TestPki.jws(manifest, oldPki.leaf())
        for (moment in VerificationMoment.values()) {
            assertEquals(oldRoot.keySha256, verify(jws, moment, sources(atExpiry)).anchorKeySha256)
            assertEquals(oldRoot.keySha256, verify(jws, moment, sources(atExpiry + 999)).anchorKeySha256)
            refused("an old-root chain after the expiry at $moment") { verify(jws, moment, sources(aSecondLater)) }
            refused("an old-root chain long after the expiry at $moment") { verify(jws, moment, sources(aSecondLater + 400L * 86_400_000)) }
        }
    }

    @Test
    fun `a new-root chain is accepted before and after the old root expires`() {
        val jws = TestPki.jws(manifest, newPki.leaf())
        for (now in listOf(atExpiry - 86_400_000, atExpiry, aSecondLater, aSecondLater + 400L * 86_400_000)) {
            assertEquals(newRoot.keySha256, verify(jws, VerificationMoment.LAUNCH, sources(now)).anchorKeySha256)
        }
    }

    @Test
    fun `the retired root's chain is refused though it is the only root pinned`() {
        val jws = TestPki.jws(manifest, oldPki.leaf())
        refused("only a retired root pinned") { verify(jws, VerificationMoment.CATALOG, Sources(listOf(oldRoot), aSecondLater)) }
    }

    @Test
    fun `an index certificate chain follows the same expiry`() {
        val chain = listOf(oldPki.leaf().certificate)
        CertificateSignatures.verifyChain(chain, SignaturePurpose.PLUGIN, oldRoot, now = atExpiry)
        refused("an old-root index chain after the expiry") {
            CertificateSignatures.verifyChain(chain, SignaturePurpose.PLUGIN, oldRoot, now = aSecondLater)
        }
        CertificateSignatures.verifyChain(listOf(newPki.leaf().certificate), SignaturePurpose.PLUGIN, newRoot, now = aSecondLater)
    }

    @Test
    fun `a revocation list signed under the new root revokes an old-root leaf while both roots are in force`() {
        val oldLeaf = oldPki.leaf()
        val signer = newPki.leaf(purpose = SignaturePurpose.REVOCATIONS, extraUris = listOf("urn:droidtop:catalog:enginehost"))
        val compact = TestPki.revocationList(signer, listOf(signer), "enginehost", 1, certSha256 = listOf(sha256(oldLeaf.der).lowercase()))
        val list = RevocationList.verifyUnderAny(compact, listOf(newRoot, oldRoot), "enginehost", now = atExpiry)
        assertTrue(list.revoked.revokesCertificate(oldLeaf.der))
        // Held by the store as the catalog's one revoked set, it stops the old leaf's bundle.
        val jws = TestPki.jws(manifest, oldLeaf)
        refused("an old-root leaf the new-root list revokes") {
            verify(jws, VerificationMoment.LAUNCH, sources(atExpiry, mapOf("enginehost" to list.revoked)))
        }
        // A list under the retired root is taken while it is in force and refused after it; the new root's list always is.
        val oldSigner = oldPki.leaf(purpose = SignaturePurpose.REVOCATIONS, extraUris = listOf("urn:droidtop:catalog:enginehost"))
        val oldList = TestPki.revocationList(oldSigner, listOf(oldSigner), "enginehost", 2)
        assertEquals(2, RevocationList.verifyUnderAny(oldList, listOf(newRoot, oldRoot), "enginehost", now = atExpiry).sequence)
        refused("a revocation list under the retired root after the expiry") {
            RevocationList.verifyUnderAny(oldList, listOf(newRoot, oldRoot), "enginehost", now = aSecondLater)
        }
        assertEquals(1, RevocationList.verifyUnderAny(compact, listOf(newRoot, oldRoot), "enginehost", now = aSecondLater).sequence)
    }

    @Test
    fun `a revocation list also revokes a new-root leaf`() {
        val newLeaf = newPki.leaf()
        val revoked = RevokedSet.of(listOf(sha256(newLeaf.der)), emptyList())
        refused("a revoked new-root leaf") {
            verify(TestPki.jws(manifest, newLeaf), VerificationMoment.CATALOG, sources(atExpiry, mapOf("enginehost" to revoked)))
        }
    }

    @Test
    fun `the pinned list reads its roots and their end`() {
        val keys = mapOf(
            "enginehost-root.pub" to pem(newRoot),
            "enginehost-root-2026-10.pub" to pem(oldRoot),
        )
        val roots = EnginehostRoots.parse(
            """{"roots":[{"key":"enginehost-root.pub"},{"key":"enginehost-root-2026-10.pub","notAfter":"$EXPIRY"}]}""",
        ) { keys.getValue(it) }
        assertEquals(listOf(newRoot.keySha256, oldRoot.keySha256), roots.map { it.keySha256 })
        assertEquals(null, roots[0].notAfterEpochSecond)
        assertEquals(expirySecond, roots[1].notAfterEpochSecond)
        assertTrue(roots[1].inForceAt(atExpiry))
        assertFalse(roots[1].inForceAt(aSecondLater))
        assertTrue(roots[0].inForceAt(Long.MAX_VALUE / 2))
        refused("a key file outside the trust directory") {
            EnginehostRoots.parse("""{"roots":[{"key":"../x.pub"}]}""") { pem(newRoot) }
        }
    }

    private fun pem(root: TrustAnchor.Root): String =
        "-----BEGIN PUBLIC KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(root.spki) +
            "\n-----END PUBLIC KEY-----\n"

    private fun plugin(signer: BundleSigner, origin: String = this.origin) = InstalledPlugin(
        info = PluginInfo("renpy", Version.parse("1.0.0-1"), emptyList()),
        bundleId = bundleId,
        entrypointClass = "X",
        origin = origin,
        signerFingerprints = setOf(signer.keySha256),
        signer = signer,
    )

    private fun signedUnder(pki: TestPki.Hierarchy, root: TrustAnchor.Root) =
        verify(TestPki.jws(manifest, pki.leaf()), VerificationMoment.CATALOG_INSTALL, sources(atExpiry)).also {
            assertEquals(root.keySha256, it.anchorKeySha256)
        }

    @Test
    fun `an approval carries from a build under the old root to one under the new root`() {
        val before = plugin(signedUnder(oldPki, oldRoot))
        val after = plugin(signedUnder(newPki, newRoot))
        val pinned = setOf(oldRoot.keySha256, newRoot.keySha256)
        assertTrue(before.signerIdentity != after.signerIdentity)
        assertTrue(PluginTrustStore.shouldCarry("approved", before, after, pinned))
        // Only an approval, only the same origin, and only between roots this build pins.
        assertFalse(PluginTrustStore.shouldCarry("denied", before, after, pinned))
        assertFalse(PluginTrustStore.shouldCarry(null, before, after, pinned))
        assertFalse(PluginTrustStore.shouldCarry("approved", before, plugin(after.signer!!, "https://github.com/droidtop/other"), pinned))
        assertFalse(PluginTrustStore.shouldCarry("approved", before, after, setOf(newRoot.keySha256)))
        assertFalse(PluginTrustStore.shouldCarry("approved", before, after))
        // A new leaf key under the same root still asks, as before.
        val sameRootNewKey = plugin(signedUnder(newPki, newRoot))
        assertFalse(PluginTrustStore.shouldCarry("approved", after, sameRootNewKey, pinned))
    }

    @Test
    fun `a superseded marker carries an approval to the key it names, once the old root is no longer pinned`() {
        val before = plugin(signedUnder(oldPki, oldRoot))
        val after = plugin(signedUnder(newPki, newRoot))
        val onlyNew = setOf(newRoot.keySha256)
        val marker = mapOf(oldRoot.keySha256.uppercase() to newRoot.keySha256.uppercase())
        // The old root's file can go: the marker alone links the two (Droidtop/tracker#887).
        assertFalse(PluginTrustStore.shouldCarry("approved", before, after, onlyNew))
        assertTrue(PluginTrustStore.shouldCarry("approved", before, after, onlyNew, marker))
        // Only the direction it names, only an approval, only the same origin.
        assertFalse(PluginTrustStore.shouldCarry("approved", after, before, onlyNew, marker))
        assertFalse(PluginTrustStore.shouldCarry(null, before, after, onlyNew, marker))
        assertFalse(PluginTrustStore.shouldCarry("approved", before, plugin(after.signer!!, "https://github.com/droidtop/other"), onlyNew, marker))
        // A marker naming another key does not link these two.
        assertFalse(PluginTrustStore.shouldCarry("approved", before, after, onlyNew, mapOf(oldRoot.keySha256.uppercase() to "AB".repeat(32))))
        // Never for a repository the person added: the root set does not speak for it.
        val custom = { s: BundleSigner -> plugin(s.copy(kind = SignerKind.CUSTOM)) }
        assertFalse(PluginTrustStore.shouldCarry("approved", custom(before.signer!!), custom(after.signer!!), onlyNew, marker))
    }

    @Test
    fun `the pinned list reads its superseded markers`() {
        val old = oldRoot.keySha256
        val new = newRoot.keySha256
        val markers = EnginehostRoots.parseSuperseded(
            """{"roots":[{"key":"enginehost-root.pub","superseded":[
               {"keySha256":"${old.lowercase()}","by":"${new.lowercase()}"},
               {"keySha256":"not a fingerprint","by":"$new"},
               {"keySha256":"$new","by":"$new"}]},
              {"key":"enginehost-root-2026-10.pub","notAfter":"$EXPIRY"}]}""",
        )
        assertEquals(mapOf(old.uppercase() to new.uppercase()), markers)
        assertEquals(emptyMap<String, String>(), EnginehostRoots.parseSuperseded("""{"roots":[{"key":"enginehost-root.pub"}]}"""))
        // The roots themselves read as before, the marker ignored by the root parser.
        val keys = mapOf("enginehost-root.pub" to pem(newRoot), "enginehost-root-2026-10.pub" to pem(oldRoot))
        val roots = EnginehostRoots.parse(
            """{"roots":[{"key":"enginehost-root.pub","superseded":[{"keySha256":"$old","by":"$new"}]},
              {"key":"enginehost-root-2026-10.pub","notAfter":"$EXPIRY"}]}""",
        ) { keys.getValue(it) }
        assertEquals(listOf(new, old), roots.map { it.keySha256 })
    }
}
