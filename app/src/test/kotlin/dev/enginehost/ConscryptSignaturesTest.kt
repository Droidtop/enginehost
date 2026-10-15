package dev.enginehost

import org.conscrypt.Conscrypt
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The verifier under Conscrypt, Android's own provider (BoringSSL). Its
 * X509Certificate.getExtendedKeyUsage throws on the purpose OIDs' UUID arc,
 * which is wider than 64 bits, so a verifier that called it would refuse every
 * Droidtop certificate on a device while passing every test on the JDK
 * (Droidtop/tracker#550, found in droidtop). Conscrypt is installed first here
 * so CertificateFactory hands out its certificates, as on Android.
 */
class ConscryptSignaturesTest {
    companion object {
        private var installed = false

        @BeforeClass @JvmStatic
        fun installConscrypt() {
            installed = Security.insertProviderAt(Conscrypt.newProvider(), 1) == 1
        }

        @AfterClass @JvmStatic
        fun removeConscrypt() {
            if (installed) Security.removeProvider(Security.getProviders().first().name)
        }
    }

    private val pki = TestPki.Hierarchy()
    private val manifest = """{"bundleId":"dev.enginehost.example"}""".toByteArray()

    @Test
    fun `certificates come from Conscrypt, whose getExtendedKeyUsage refuses the purpose OIDs`() {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(pki.leaf().der.inputStream()) as X509Certificate
        assertTrue(certificate.javaClass.name, certificate.javaClass.name.startsWith("org.conscrypt"))
        try {
            certificate.extendedKeyUsage
            fail("Conscrypt read the UUID-arc EKU; the reason for this test no longer holds")
        } catch (expected: Exception) {
            // The device behaviour the verifier must not depend on.
        }
    }

    @Test
    fun `a Core-anchored bundle signature verifies under Conscrypt`() {
        val verified = CertificateSignatures.verify(
            TestPki.jws(manifest, pki.leaf()), SignaturePurpose.PLUGIN, pki.anchor, manifest, Date(),
        )
        assertTrue(verified.namesOrigin("https://github.com/droidtop/enginehost-example-plugin"))
    }

    @Test
    fun `the purpose is still enforced under Conscrypt`() {
        val catalogLeaf = pki.leaf(purpose = SignaturePurpose.CATALOG)
        try {
            CertificateSignatures.verify(TestPki.jws(manifest, catalogLeaf), SignaturePurpose.PLUGIN, pki.anchor, manifest, Date())
            fail("a catalog certificate signed a plugin under Conscrypt")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `the extendedKeyUsage DER reads the same OIDs on both providers`() {
        val der = pki.leaf().der
        val oids = CertificateSignatures.extendedKeyUsage(
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate,
        )!!
        assertEquals(1, oids.size)
        assertTrue(oids.single().contentEquals(CertificateSignatures.encodeOid(SignaturePurpose.PLUGIN.oid)))
        // And the encoding matches an independent DER encoder's.
        val reference = org.bouncycastle.asn1.ASN1ObjectIdentifier(SignaturePurpose.PLUGIN.oid).encoded
        assertTrue(reference.copyOfRange(2, reference.size).contentEquals(CertificateSignatures.encodeOid(SignaturePurpose.PLUGIN.oid)))
    }
}
