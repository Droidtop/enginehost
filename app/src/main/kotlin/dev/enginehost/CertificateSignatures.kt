package dev.enginehost

import dev.droidtop.shared.trust.ChainSignature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/*
 * Signature format v1 (Droidtop/tracker#550, docs/engine-bundle-format.md
 * "Signatures"): a JWS (RFC 7515) in compact serialization whose protected
 * header carries the signer's X.509 chain in `x5c`, verified up to one
 * anchor and never past it. The anchor is the pinned Enginehost signing
 * root's public key for Enginehost's own plugins, or the key a person pinned for a
 * repository they added. Nothing here touches Android: it is the JDK's
 * shared verifier (vendor/droidtop-shared) and org.json for the revocation lists, so plain JVM tests
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
     * The owner derives this root from the Droidtop CA, which no app pins or
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

    /** A chain certificate is revoked by its own hash or by its key's. */
    fun revokesChainCertificate(certificate: X509Certificate) = revokesCertificate(certificate.encoded) || revokesKey(certificate.publicKey.encoded)

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

/**
 * Enginehost's names for the shared verifier (vendor/droidtop-shared, `:trust`, `ChainSignature`): the same JWS-with-`x5c`
 * format droidtop checks, verified up to one anchor and no further. This object keeps Enginehost's types (a
 * [TrustAnchor], a [RevokedSet], a [VerifiedJws]) and its convention that a signature which does not verify throws
 * [IllegalArgumentException] with the reason; the checks themselves are the library's.
 */
object CertificateSignatures {
    /**
     * Verifies a JWS whose content is [detachedContent] (bundle manifests, catalog indexes) or, when that is null, carried in
     * the JWS itself (revocation lists). No certificate's validity period is read: expiry never invalidates a signature (owner,
     * Droidtop/tracker#550: "We don't want older plugins to be invalidated"); only [revoked] withdraws trust, and it names any
     * chain certificate or key, the anchor's key included.
     */
    fun verify(
        compact: String,
        purpose: SignaturePurpose,
        anchor: TrustAnchor,
        detachedContent: ByteArray?,
        revoked: RevokedSet = RevokedSet.NONE,
    ): VerifiedJws =
        when (
            val result = ChainSignature.verify(
                compact, purpose.shared(), anchor.shared(), detachedContent, revoked::revokesChainCertificate, revoked.revokesKey(anchor.keySha256),
            )
        ) {
            is ChainSignature.Result.Refused -> throw IllegalArgumentException(result.reason)
            is ChainSignature.Result.Verified -> VerifiedJws(result.chain, detachedContent ?: requireNotNull(result.payload) { "Signature payload is missing" })
        }

    /**
     * Verifies [chain] (leaf first, the anchor excluded) for [purpose] up to [anchor] and no further: the checks of [verify] without a
     * signature around them. An origin's published certificate is checked this way.
     */
    fun verifyChain(
        chain: List<X509Certificate>,
        purpose: SignaturePurpose,
        anchor: TrustAnchor,
        revoked: RevokedSet = RevokedSet.NONE,
    ): List<X509Certificate> {
        ChainSignature.verifyChain(chain, purpose.shared(), anchor.shared(), revoked::revokesChainCertificate, revoked.revokesKey(anchor.keySha256))
            ?.let { throw IllegalArgumentException(it) }
        return chain
    }

    /**
     * The SHA-256 of the key a JWS's leaf certificate names, read without verifying anything: only for telling a person
     * which key a refused signature claims, never for trusting it.
     */
    fun claimedLeafKeySha256(compact: String): String =
        requireNotNull(ChainSignature.claimedLeafKeySha256(compact)) { "Signature carries no readable certificate chain" }

    /** x5c-style standard base64 DER certificates, leaf first. */
    fun parseChain(base64Der: List<String>): List<X509Certificate> =
        base64Der.map { parseCertificate(Base64.getDecoder().decode(it)) }

    /** The URIs among [certificate]'s subjectAltName entries. */
    internal fun uris(certificate: X509Certificate): List<String> = ChainSignature.uris(certificate)

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /** A PEM `PUBLIC KEY` block (SubjectPublicKeyInfo), as DER: the pinned Enginehost root key. */
    fun parsePemPublicKey(pem: String): ByteArray {
        val body = pem.substringAfter("-----BEGIN PUBLIC KEY-----", "").substringBefore("-----END PUBLIC KEY-----", "")
        require(body.isNotBlank()) { "Not a PEM public key" }
        val spki = Base64.getMimeDecoder().decode(body)
        publicKey(spki)
        return spki
    }

    internal fun publicKey(spki: ByteArray): PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(spki)
        return runCatching { java.security.KeyFactory.getInstance("EC").generatePublic(spec) }
            .recoverCatching { java.security.KeyFactory.getInstance("RSA").generatePublic(spec) }
            .getOrElse { throw IllegalArgumentException("The pinned key is not an EC or RSA key") }
    }

    fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun SignaturePurpose.shared(): ChainSignature.Purpose = ChainSignature.Purpose.entries.single { it.typ == typ }

    /** The root is the library's pinned anchor (the chain stops below it); a pinned key may also be a publisher's own certificate's. */
    private fun TrustAnchor.shared(): ChainSignature.Anchor = when (this) {
        is TrustAnchor.Root -> ChainSignature.Anchor(publicKey(spki), pinned = true)
        is TrustAnchor.PinnedKey -> ChainSignature.Anchor(publicKey(spki))
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
            alreadyRevoked: RevokedSet = RevokedSet.NONE,
        ): RevocationList {
            val expected = catalog.lowercase()
            val verified = CertificateSignatures.verify(
                compact, SignaturePurpose.REVOCATIONS, anchor, detachedContent = null, revoked = alreadyRevoked,
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
