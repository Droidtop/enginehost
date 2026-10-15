package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

/*
 * Signature format v1 (Droidtop/tracker#550, docs/engine-bundle-format.md
 * "Signatures"): a JWS (RFC 7515) in compact serialization whose protected
 * header carries the signer's X.509 chain in `x5c`, verified up to one
 * anchor and never past it. The anchor is the pinned Enginehost signing
 * root's public key for Enginehost's own plugins, or the key a person pinned for a
 * repository they added. Nothing here touches Android: it is the JDK's
 * CertificateFactory and Signature, base64 and org.json, so plain JVM tests
 * exercise exactly what a device runs.
 */

/** What a signature is for. The header's `typ` and the leaf's extended key usage must both name it. */
enum class SignaturePurpose(val oid: String, val typ: String) {
    PLUGIN("$SIGNATURE_PURPOSE_ARC.1", "droidtop-plugin+jws"),
    CATALOG("$SIGNATURE_PURPOSE_ARC.2", "droidtop-catalog+jws"),
    REVOCATIONS("$SIGNATURE_PURPOSE_ARC.3", "droidtop-revocations+jws"),
}

/** A UUID OID (X.667), so the arc needs no registration. */
const val SIGNATURE_PURPOSE_ARC = "2.25.230248497294207031301727532441854392946"

/** Where a chain stops. Nothing above it is read, needed or trusted. */
sealed interface TrustAnchor {
    /** The SHA-256 of the anchor key's SubjectPublicKeyInfo. */
    val keySha256: String

    /**
     * The Enginehost signing root's public key (its SubjectPublicKeyInfo).
     * The owner derives this root from Droidtop Core, which no app pins or
     * uses directly (Droidtop/tracker#550): the anchor is the key, not the
     * root's certificate. The chain's last certificate must be signed by it,
     * and no certificate in the chain may carry it.
     */
    class Root(val spki: ByteArray) : TrustAnchor {
        override val keySha256: String = sha256(spki)
    }

    /**
     * The key a person confirmed for a repository they added. The chain's last
     * certificate is either signed by it or carries it itself (a publisher's
     * self-signed certificate).
     */
    class PinnedKey(val spki: ByteArray) : TrustAnchor {
        override val keySha256: String = sha256(spki)
    }
}

/** Certificates and keys a revocation list names, by the SHA-256 of their DER. Hex, compared without case. */
data class RevokedSet(val certSha256: Set<String> = emptySet(), val keySha256: Set<String> = emptySet()) {
    fun revokesCertificate(der: ByteArray) = sha256(der) in certSha256
    fun revokesKey(spki: ByteArray) = revokesKey(sha256(spki))
    fun revokesKey(keySha256: String) = keySha256.uppercase() in this.keySha256

    operator fun plus(other: RevokedSet) = RevokedSet(certSha256 + other.certSha256, keySha256 + other.keySha256)

    companion object {
        val NONE = RevokedSet()
        fun of(certSha256: Collection<String>, keySha256: Collection<String>) =
            RevokedSet(certSha256.map(String::uppercase).toSet(), keySha256.map(String::uppercase).toSet())
    }
}

/** A JWS that verified: who signed it, as far as the chain says, and what it signed. */
class VerifiedJws(
    val chain: List<X509Certificate>,
    val payload: ByteArray,
) {
    val leaf: X509Certificate get() = chain.first()

    /** The signer's identity: the SHA-256 of the leaf's SubjectPublicKeyInfo, as for every signer Enginehost shows. */
    val leafKeySha256: String = sha256(leaf.publicKey.encoded)

    private val uris: List<String> = CertificateSignatures.uris(leaf)

    /** `urn:droidtop:origin:<owner>/<repo>`, lower-cased. */
    val origins: Set<String> = uris.urnValues(ORIGIN_URN)

    /** `urn:droidtop:plugin:<id>` or `<prefix>.*`. Empty means the leaf does not narrow which bundles it signs. */
    val plugins: Set<String> = uris.urnValues(PLUGIN_URN)

    /** `urn:droidtop:catalog:<id>`, lower-cased. */
    val catalogs: Set<String> = uris.urnValues(CATALOG_URN)

    /** The primary developer's certificate (docs/engine-bundle-format.md "Developer builds"). */
    val isDeveloper: Boolean = DEVELOPER_URN in uris.map(String::lowercase)

    /** Whether the leaf names [origin] (a GitHub repository URL), or is the developer's, which names none. */
    fun namesOrigin(origin: String): Boolean = originId(origin) in origins

    /** Whether the leaf lets this bundle ID be signed under it: no plugin URNs, an exact one, or a `prefix.*` one. */
    fun allowsPlugin(bundleId: String): Boolean = plugins.isEmpty() || plugins.any { pattern ->
        if (pattern.endsWith(".*")) bundleId.startsWith(pattern.removeSuffix("*")) else bundleId == pattern
    }

    companion object {
        const val ORIGIN_URN = "urn:droidtop:origin:"
        const val PLUGIN_URN = "urn:droidtop:plugin:"
        const val CATALOG_URN = "urn:droidtop:catalog:"
        const val DEVELOPER_URN = "urn:enginehost:developer"

        /** `https://github.com/Owner/Repo` -> `owner/repo`, the id an origin URN and an added catalog's id use. */
        fun originId(origin: String): String {
            val match = requireNotNull(GITHUB_ORIGIN.matchEntire(normalizeGithubOrigin(origin))) {
                "Not a GitHub repository: $origin"
            }
            return "${match.groupValues[1]}/${match.groupValues[2]}".lowercase()
        }

        private fun List<String>.urnValues(prefix: String): Set<String> =
            filter { it.startsWith(prefix, ignoreCase = true) }
                .map { it.substring(prefix.length).lowercase() }
                .filter(String::isNotEmpty)
                .toSet()
    }
}

object CertificateSignatures {
    private const val MAX_INTERMEDIATES = 2
    private const val MAX_JWS_CHARS = 64 * 1024

    private const val KEY_USAGE_DIGITAL_SIGNATURE = 0
    private const val KEY_USAGE_KEY_CERT_SIGN = 5

    /** Signature algorithms a chain certificate may be signed with: sha256/384/512WithRSA, ecdsa-with-SHA256/384. */
    private val CERTIFICATE_ALGORITHMS = setOf(
        "1.2.840.113549.1.1.11",
        "1.2.840.113549.1.1.12",
        "1.2.840.113549.1.1.13",
        "1.2.840.10045.4.3.2",
        "1.2.840.10045.4.3.3",
    )

    /**
     * Extensions this verifier reads. A certificate marking any other one
     * critical is refused, as PKIX requires of an extension a verifier does
     * not process.
     */
    private val UNDERSTOOD_CRITICAL = setOf(
        "2.5.29.15", // keyUsage
        "2.5.29.17", // subjectAltName
        "2.5.29.19", // basicConstraints
        "2.5.29.32", // certificatePolicies (informational)
        "2.5.29.37", // extendedKeyUsage
    )

    private val BASE64URL = Regex("[A-Za-z0-9_-]*")

    /**
     * Verifies a JWS whose content is [detachedContent] (bundle manifests,
     * catalog indexes: the payload part is empty and the signing input is the
     * header and the content's base64url) or, when that is null, carried in
     * the JWS itself (revocation lists).
     *
     * [now] checks every chain certificate's validity; null skips it, which
     * is how an installed plugin is verified at launch (a wrong device clock
     * must not stop plugins; only revocation does). [revoked] refuses any
     * chain certificate or key it names, the anchor's key included.
     */
    fun verify(
        compact: String,
        purpose: SignaturePurpose,
        anchor: TrustAnchor,
        detachedContent: ByteArray?,
        now: Date?,
        revoked: RevokedSet = RevokedSet.NONE,
    ): VerifiedJws {
        require(compact.length <= MAX_JWS_CHARS) { "Signature is too large" }
        val parts = compact.trim().split('.')
        require(parts.size == 3 && parts.all { BASE64URL.matches(it) }) { "Not a compact JWS" }
        val (headerPart, payloadPart, signaturePart) = parts
        val header = JSONObject(base64Url(headerPart).toString(Charsets.UTF_8))
        require(!header.has("crit")) { "A signature with critical header parameters is refused" }
        require(header.optString("typ") == purpose.typ) { "Signature is not a ${purpose.typ}" }
        val algorithm = header.optString("alg")
        require(algorithm == "ES256" || algorithm == "RS256") { "Unsupported signature algorithm $algorithm" }

        val payload: ByteArray
        val signingPayload: String
        if (detachedContent != null) {
            require(payloadPart.isEmpty()) { "A detached signature carries no payload" }
            payload = detachedContent
            signingPayload = base64UrlEncode(detachedContent)
        } else {
            require(payloadPart.isNotEmpty()) { "Signature payload is missing" }
            payload = base64Url(payloadPart)
            signingPayload = payloadPart
        }

        val chain = verifyChain(certificates(header.optJSONArray("x5c")), purpose, anchor, now, revoked)
        val leaf = chain.first()
        checkLeafAlgorithm(leaf, algorithm)

        val signer = when (algorithm) {
            "ES256" -> Signature.getInstance("SHA256withECDSA")
            else -> Signature.getInstance("SHA256withRSA")
        }
        signer.initVerify(leaf.publicKey)
        signer.update("$headerPart.$signingPayload".toByteArray(Charsets.US_ASCII))
        val signature = base64Url(signaturePart)
        require(signer.verify(if (algorithm == "ES256") rawEcdsaToDer(signature) else signature)) {
            "Signature does not verify"
        }
        return VerifiedJws(chain, payload)
    }

    /**
     * Verifies [chain] (leaf first, the anchor excluded) for [purpose] up to
     * [anchor] and no further: the checks of [verify] without a signature
     * around them. An origin's published certificate is checked this way.
     */
    fun verifyChain(
        chain: List<X509Certificate>,
        purpose: SignaturePurpose,
        anchor: TrustAnchor,
        now: Date?,
        revoked: RevokedSet = RevokedSet.NONE,
    ): List<X509Certificate> {
        require(chain.size in 1..(1 + MAX_INTERMEDIATES)) { "Certificate chain is empty or too long" }
        checkLeaf(chain.first(), purpose)
        chain.drop(1).forEachIndexed { index, intermediate -> checkIntermediate(intermediate, below = index) }
        chain.forEach(::checkCommon)
        if (now != null) chain.forEach { it.checkValidity(now) }

        // Each certificate is signed by the next, and names it as its issuer.
        chain.zipWithNext().forEach { (child, parent) ->
            require(child.issuerX500Principal == parent.subjectX500Principal) { "Certificate chain is out of order" }
            child.verify(parent.publicKey)
        }
        checkAnchor(chain.last(), chain, anchor)

        chain.forEach { certificate ->
            require(!revoked.revokesCertificate(certificate.encoded)) { "A certificate in the chain is revoked" }
            require(!revoked.revokesKey(certificate.publicKey.encoded)) { "A key in the chain is revoked" }
        }
        require(!revoked.revokesKey(anchor.keySha256)) { "The anchor key is revoked" }
        return chain
    }

    /**
     * The SHA-256 of the key a JWS's leaf certificate names, read without
     * verifying anything: only for telling a person which key a refused
     * signature claims, never for trusting it.
     */
    fun claimedLeafKeySha256(compact: String): String {
        val header = JSONObject(base64Url(compact.trim().substringBefore('.')).toString(Charsets.UTF_8))
        return sha256(certificates(header.optJSONArray("x5c")).first().publicKey.encoded)
    }

    /** x5c-style standard base64 DER certificates, leaf first. */
    fun parseChain(base64Der: List<String>): List<X509Certificate> =
        base64Der.map { parseCertificate(Base64.getDecoder().decode(it)) }

    /** The URIs among [certificate]'s subjectAltName entries. */
    internal fun uris(certificate: X509Certificate): List<String> =
        certificate.subjectAlternativeNames.orEmpty()
            .filter { it.size >= 2 && it[0] == 6 }
            .mapNotNull { it[1] as? String }

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun certificates(x5c: JSONArray?): List<X509Certificate> {
        requireNotNull(x5c) { "Signature carries no certificate chain" }
        require(x5c.length() in 1..(1 + MAX_INTERMEDIATES)) { "Certificate chain is empty or too long" }
        return (0 until x5c.length()).map { index ->
            // x5c is standard base64, not the url-safe alphabet (RFC 7515 4.1.6).
            parseCertificate(Base64.getDecoder().decode(x5c.getString(index)))
        }
    }

    private fun checkCommon(certificate: X509Certificate) {
        require(certificate.version == 3) { "Chain certificates must be X.509 v3" }
        require(certificate.sigAlgOID in CERTIFICATE_ALGORITHMS) {
            "Unsupported certificate signature algorithm ${certificate.sigAlgName}"
        }
        val unknown = certificate.criticalExtensionOIDs.orEmpty() - UNDERSTOOD_CRITICAL
        require(unknown.isEmpty()) { "Certificate has unsupported critical extensions $unknown" }
    }

    private fun checkLeaf(leaf: X509Certificate, purpose: SignaturePurpose) {
        require(leaf.basicConstraints == -1) { "The signing certificate must not be a CA" }
        val usage = requireNotNull(leaf.keyUsage) { "The signing certificate has no key usage" }
        require(usage.bit(KEY_USAGE_DIGITAL_SIGNATURE)) { "The signing certificate does not allow digital signatures" }
        require(!usage.bit(KEY_USAGE_KEY_CERT_SIGN)) { "The signing certificate may not sign certificates" }
        val extended = requireNotNull(extendedKeyUsage(leaf)) { "The signing certificate has no extended key usage" }
        require(extended.any { it.contentEquals(encodeOid(purpose.oid)) }) { "The signing certificate is not for ${purpose.typ}" }
        when (val key = leaf.publicKey) {
            is ECPublicKey -> require(key.params.curve.field.fieldSize == 256) { "EC signing keys must be P-256" }
            is RSAPublicKey -> require(key.modulus.bitLength() >= 2048) { "RSA signing keys must be 2048 bits or more" }
            else -> throw IllegalArgumentException("Unsupported signing key type ${key.algorithm}")
        }
    }

    /** The header's `alg` must match the leaf's key type. */
    private fun checkLeafAlgorithm(leaf: X509Certificate, algorithm: String) {
        when (leaf.publicKey) {
            is ECPublicKey -> require(algorithm == "ES256") { "An EC signing key signs ES256" }
            is RSAPublicKey -> require(algorithm == "RS256") { "An RSA signing key signs RS256" }
        }
    }

    /** [below] is how many intermediates sit between this one and the leaf. */
    private fun checkIntermediate(intermediate: X509Certificate, below: Int) {
        val pathLength = intermediate.basicConstraints
        require(pathLength >= 0) { "An intermediate certificate must be a CA" }
        require(pathLength >= below) { "An intermediate's path length constraint is exceeded" }
        val usage = requireNotNull(intermediate.keyUsage) { "An intermediate certificate has no key usage" }
        require(usage.bit(KEY_USAGE_KEY_CERT_SIGN)) { "An intermediate certificate may not sign certificates" }
    }

    private fun checkAnchor(last: X509Certificate, chain: List<X509Certificate>, anchor: TrustAnchor) {
        when (anchor) {
            is TrustAnchor.Root -> {
                // The walk stops at the root: no certificate carrying the
                // root's key (its own self-signed one, or any re-issue of it)
                // has a place in the chain, and nothing above it (Droidtop
                // Core, the CA) signs bundles. There is no pinned certificate,
                // so there is no issuer name to compare; the signature decides.
                require(chain.none { it.publicKey.encoded.contentEquals(anchor.spki) }) {
                    "The chain may not contain a certificate carrying the Enginehost root's key"
                }
                last.verify(publicKey(anchor.spki))
            }
            // Signed by the pinned key: a certificate it issued, or the
            // publisher's self-signed certificate that carries it.
            is TrustAnchor.PinnedKey -> last.verify(publicKey(anchor.spki))
        }
    }

    /**
     * The extendedKeyUsage OIDs of [certificate], each as its DER content
     * bytes, or null when it has none. Read from the extension's own DER, never
     * X509Certificate.getExtendedKeyUsage: Android's provider (Conscrypt,
     * BoringSSL) throws on the purpose OIDs' UUID arc, which is wider than 64
     * bits, so that call would refuse every Droidtop certificate on a device
     * (found by droidtop's Conscrypt test, Droidtop/tracker#550 07:04).
     */
    internal fun extendedKeyUsage(certificate: X509Certificate): List<ByteArray>? {
        val wrapped = certificate.getExtensionValue("2.5.29.37") ?: return null
        val sequence = Der(wrapped).element(0x04).let { Der(it).element(0x30) }
        val reader = Der(sequence)
        val oids = mutableListOf<ByteArray>()
        while (!reader.done) oids += reader.element(0x06)
        return oids
    }

    /** A dotted OID as the content bytes of its DER encoding (X.690 8.19). */
    internal fun encodeOid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map(::BigInteger)
        require(arcs.size >= 2) { "Not an OID: $dotted" }
        val out = ByteArrayOutputStream()
        (listOf(arcs[0] * BigInteger.valueOf(40) + arcs[1]) + arcs.drop(2)).forEach { arc ->
            val groups = mutableListOf<Int>()
            var rest = arc
            do {
                groups += rest.and(BigInteger.valueOf(0x7f)).toInt()
                rest = rest.shiftRight(7)
            } while (rest.signum() > 0)
            groups.asReversed().forEachIndexed { index, group ->
                out.write(if (index < groups.size - 1) group or 0x80 else group)
            }
        }
        return out.toByteArray()
    }

    /** A minimal DER reader: definite lengths only, which is all DER has. */
    private class Der(private val bytes: ByteArray) {
        private var offset = 0
        val done: Boolean get() = offset >= bytes.size

        fun element(tag: Int): ByteArray {
            require(offset < bytes.size && (bytes[offset].toInt() and 0xff) == tag) { "Malformed extension" }
            offset += 1
            require(offset < bytes.size) { "Malformed extension" }
            var length = bytes[offset].toInt() and 0xff
            offset += 1
            if (length and 0x80 != 0) {
                val count = length and 0x7f
                require(count in 1..3 && offset + count <= bytes.size) { "Malformed extension" }
                length = 0
                repeat(count) { length = (length shl 8) or (bytes[offset].toInt() and 0xff); offset += 1 }
            }
            require(length >= 0 && offset + length <= bytes.size) { "Malformed extension" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }

    /** A PEM `PUBLIC KEY` block (SubjectPublicKeyInfo), as DER: the pinned Enginehost root key. */
    fun parsePemPublicKey(pem: String): ByteArray {
        val body = pem.substringAfter("-----BEGIN PUBLIC KEY-----", "").substringBefore("-----END PUBLIC KEY-----", "")
        require(body.isNotBlank()) { "Not a PEM public key" }
        val spki = Base64.getMimeDecoder().decode(body)
        publicKey(spki)
        return spki
    }

    private fun publicKey(spki: ByteArray): PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(spki)
        return runCatching { java.security.KeyFactory.getInstance("EC").generatePublic(spec) }
            .recoverCatching { java.security.KeyFactory.getInstance("RSA").generatePublic(spec) }
            .getOrElse { throw IllegalArgumentException("The pinned key is not an EC or RSA key") }
    }

    private fun BooleanArray.bit(index: Int) = index < size && this[index]

    private fun base64Url(part: String): ByteArray = Base64.getUrlDecoder().decode(part)

    fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** ES256 signs as R‖S, 32 bytes each (RFC 7518 3.4); the JDK verifies a DER SEQUENCE of two INTEGERs. */
    internal fun rawEcdsaToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "An ES256 signature is 64 bytes" }
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return ByteArrayOutputStream().apply {
            write(0x30)
            write(body.size)
            write(body)
        }.toByteArray()
    }
}

/**
 * One signed revocation list (`revocations.jws`, an attached-payload JWS):
 * the catalog it speaks for, its sequence, and what it revokes. A list is
 * taken only over a lower sequence ([RevocationListStore]), so an old list
 * cannot be replayed to un-revoke anything.
 */
data class RevocationList(
    val catalog: String,
    val sequence: Long,
    val issuedAt: Long,
    val revoked: RevokedSet,
    val compact: String,
) {
    companion object {
        private val HEX_SHA256 = Regex("[0-9A-Fa-f]{64}")

        /**
         * Verifies [compact] under [anchor] for [catalog]: the signer must be a
         * revocation-list certificate naming that catalog, and the list must
         * say the same. [alreadyRevoked] is the list held before, so a signer
         * it revoked cannot publish the next one.
         */
        fun verify(
            compact: String,
            anchor: TrustAnchor,
            catalog: String,
            now: Date?,
            alreadyRevoked: RevokedSet = RevokedSet.NONE,
        ): RevocationList {
            val expected = catalog.lowercase()
            val verified = CertificateSignatures.verify(
                compact, SignaturePurpose.REVOCATIONS, anchor, detachedContent = null, now = now, revoked = alreadyRevoked,
            )
            require(expected in verified.catalogs) { "The revocation list's signer does not speak for $catalog" }
            val json = JSONObject(verified.payload.toString(Charsets.UTF_8))
            require(json.optString("type") == "droidtop-revocations" && json.optInt("version") == 1) {
                "Unsupported revocation list"
            }
            require(json.optString("catalog").lowercase() == expected) { "The revocation list is for another catalog" }
            val sequence = json.getLong("sequence").also { require(it >= 0) { "Negative revocation list sequence" } }
            fun digests(name: String): List<String> = json.optJSONArray(name)?.let { array ->
                (0 until array.length()).map { index ->
                    array.getString(index).also { require(HEX_SHA256.matches(it)) { "Invalid $name entry" } }
                }
            }.orEmpty()
            return RevocationList(
                expected,
                sequence,
                json.optLong("issuedAt", 0),
                RevokedSet.of(digests("certSha256"), digests("keySha256")),
                compact.trim(),
            )
        }
    }
}
