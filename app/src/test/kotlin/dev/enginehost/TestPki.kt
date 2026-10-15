package dev.enginehost

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * Certificates and signatures for tests, generated per run. No real key or
 * certificate is ever used in a test (Droidtop/tracker#550): the "Core" and
 * the signing root here are this run's own.
 */
object TestPki {
    private val serials = AtomicLong(1000)
    private const val DAY = 24L * 60 * 60 * 1000

    fun ec(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun rsa(bits: Int = 2048): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    class Issued(val certificate: X509Certificate, val keys: KeyPair) {
        val der: ByteArray get() = certificate.encoded
        val name: String get() = certificate.subjectX500Principal.name
    }

    /**
     * One certificate. [issuer] null makes it self-signed. [ca] null leaves
     * basicConstraints out; [keyUsage] null leaves keyUsage out; [eku] null
     * leaves extendedKeyUsage out.
     */
    fun issue(
        subject: String,
        keys: KeyPair,
        issuer: Issued? = null,
        ca: Boolean? = false,
        pathLength: Int? = null,
        keyUsage: Int? = KeyUsage.digitalSignature,
        eku: List<String>? = listOf(SignaturePurpose.PLUGIN.oid),
        uris: List<String> = emptyList(),
        notBefore: Date = Date(System.currentTimeMillis() - DAY),
        notAfter: Date = Date(System.currentTimeMillis() + 365 * DAY),
        extraCritical: String? = null,
        signatureAlgorithm: String? = null,
    ): Issued {
        val issuerName = X500Name(issuer?.name ?: subject)
        val signingKey: PrivateKey = issuer?.keys?.private ?: keys.private
        val builder = JcaX509v3CertificateBuilder(
            issuerName, BigInteger.valueOf(serials.incrementAndGet()), notBefore, notAfter, X500Name(subject), keys.public,
        )
        if (ca != null) {
            builder.addExtension(
                Extension.basicConstraints, true,
                if (ca && pathLength != null) BasicConstraints(pathLength) else BasicConstraints(ca),
            )
        }
        keyUsage?.let { builder.addExtension(Extension.keyUsage, true, KeyUsage(it)) }
        eku?.let { oids ->
            builder.addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(oids.map { KeyPurposeId.getInstance(ASN1ObjectIdentifier(it)) }.toTypedArray()),
            )
        }
        if (uris.isNotEmpty()) {
            builder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(uris.map { GeneralName(GeneralName.uniformResourceIdentifier, it) }.toTypedArray()),
            )
        }
        extraCritical?.let { builder.addExtension(ASN1ObjectIdentifier(it), true, DERSequence()) }
        val algorithm = signatureAlgorithm ?: if (signingKey is ECPrivateKey) "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(algorithm).build(signingKey)
        return Issued(JcaX509CertificateConverter().getCertificate(builder.build(signer)), keys)
    }

    /**
     * A stand-in for Droidtop Core (RSA, as the real one is) and for the
     * Enginehost signing root the owner derives from it (P-256, self-signed,
     * as the real one is). The root is not certified by Core: it is derived
     * from Core's private key, so nothing public links them, and Core's own
     * certificates must never verify.
     */
    class Hierarchy {
        val core = issue("CN=Test Droidtop Core", rsa(), ca = true, keyUsage = KeyUsage.keyCertSign or KeyUsage.cRLSign, eku = null)
        val root = issue(
            "CN=Test Enginehost Signing Root", ec(), ca = true, pathLength = 1,
            keyUsage = KeyUsage.keyCertSign or KeyUsage.cRLSign, eku = null,
        )
        val anchor = TrustAnchor.Root(root.keys.public.encoded)

        fun leaf(
            origin: String = "droidtop/enginehost-example-plugin",
            purpose: SignaturePurpose = SignaturePurpose.PLUGIN,
            extraUris: List<String> = emptyList(),
            issuer: Issued = root,
            keys: KeyPair = ec(),
        ) = issue(
            "CN=$origin", keys, issuer = issuer, eku = listOf(purpose.oid),
            uris = listOf("urn:droidtop:origin:$origin") + extraUris,
        )
    }

    /** A compact JWS signed by [signer], carrying [chain] (leaf first) in x5c. */
    fun jws(
        content: ByteArray,
        signer: Issued,
        chain: List<Issued> = listOf(signer),
        purpose: SignaturePurpose = SignaturePurpose.PLUGIN,
        detached: Boolean = true,
        algorithm: String? = null,
        headerExtra: Map<String, Any> = emptyMap(),
    ): String {
        val alg = algorithm ?: if (signer.keys.private is ECPrivateKey) "ES256" else "RS256"
        val header = JSONObject()
            .put("alg", alg)
            .put("typ", purpose.typ)
            .put("x5c", JSONArray(chain.map { Base64.getEncoder().encodeToString(it.der) }))
        headerExtra.forEach { (key, value) -> header.put(key, value) }
        val headerPart = CertificateSignatures.base64UrlEncode(header.toString().toByteArray())
        val payloadPart = CertificateSignatures.base64UrlEncode(content)
        val signature = Signature.getInstance(if (signer.keys.private is ECPrivateKey) "SHA256withECDSA" else "SHA256withRSA")
        signature.initSign(signer.keys.private)
        signature.update("$headerPart.$payloadPart".toByteArray(Charsets.US_ASCII))
        val signed = signature.sign().let { if (signer.keys.private is ECPrivateKey) derToRaw(it) else it }
        return "$headerPart.${if (detached) "" else payloadPart}.${CertificateSignatures.base64UrlEncode(signed)}"
    }

    /** DER SEQUENCE{INTEGER r, INTEGER s} -> R‖S, 32 bytes each. */
    fun derToRaw(der: ByteArray): ByteArray {
        var offset = 2
        if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7f
        fun integer(): ByteArray {
            require(der[offset].toInt() == 0x02)
            val length = der[offset + 1].toInt()
            val value = der.copyOfRange(offset + 2, offset + 2 + length)
            offset += 2 + length
            val unsigned = BigInteger(1, value).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - unsigned.size) + unsigned
        }
        return integer() + integer()
    }

    fun revocationList(
        signer: Issued,
        chain: List<Issued>,
        catalog: String,
        sequence: Long,
        certSha256: List<String> = emptyList(),
        keySha256: List<String> = emptyList(),
        listedCatalog: String = catalog,
    ): String {
        val payload = JSONObject()
            .put("type", "droidtop-revocations")
            .put("version", 1)
            .put("catalog", listedCatalog)
            .put("sequence", sequence)
            .put("issuedAt", 1792000000)
            .put("certSha256", JSONArray(certSha256))
            .put("keySha256", JSONArray(keySha256))
        return jws(payload.toString().toByteArray(), signer, chain, SignaturePurpose.REVOCATIONS, detached = false)
    }
}
