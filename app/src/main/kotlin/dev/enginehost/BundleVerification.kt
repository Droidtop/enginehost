package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/** What a bundle carries to prove who signed its manifest: a v1 JWS, the legacy raw signature, or both. */
class BundleSignatures(val jws: String?, val legacy: ByteArray?) {
    init {
        require(jws != null || legacy != null) { "Bundle carries no signature" }
    }
}

/** Who a verified bundle's signer is, as far as Enginehost trusts it. */
enum class SignerKind { OFFICIAL, DEVELOPER, CUSTOM }

/**
 * The outcome of verifying a bundle's signature: the signer's key (the
 * identity the trust store keys approvals on), how it is trusted, and what
 * a later revocation list or pin change must be checked against.
 *
 * [format] is 1 for a certificate chain (docs/engine-bundle-format.md
 * "Signatures"), 0 for the legacy raw-key signature ([LegacyBundleSignatures]).
 */
data class BundleSigner(
    val keySha256: String,
    val kind: SignerKind,
    val format: Int,
    /** The Enginehost root's key, the person's pinned key, or for a legacy signer its own key. */
    val anchorKeySha256: String,
    val chainCertSha256: List<String> = emptyList(),
    val chainKeySha256: List<String> = emptyList(),
) {
    /** Whether [revoked] names anything this signer's trust rests on. */
    fun isRevokedBy(revoked: RevokedSet): Boolean =
        revoked.revokesKey(keySha256) || revoked.revokesKey(anchorKeySha256) ||
            chainKeySha256.any(revoked::revokesKey) || chainCertSha256.any { it.uppercase() in revoked.certSha256 }

    fun toJson(): JSONObject = JSONObject()
        .put("keySha256", keySha256)
        .put("kind", kind.name.lowercase())
        .put("format", format)
        .put("anchorKeySha256", anchorKeySha256)
        .put("chainCertSha256", JSONArray(chainCertSha256))
        .put("chainKeySha256", JSONArray(chainKeySha256))

    companion object {
        fun fromJson(json: JSONObject): BundleSigner = BundleSigner(
            json.requiredSha256("keySha256"),
            SignerKind.valueOf(json.getString("kind").uppercase()),
            json.getInt("format"),
            json.requiredSha256("anchorKeySha256"),
            json.optJSONArray("chainCertSha256").strings(),
            json.optJSONArray("chainKeySha256").strings(),
        )

        private fun JSONArray?.strings(): List<String> = (0 until (this?.length() ?: 0)).map { this!!.getString(it) }
    }
}

/** When a bundle is verified; each moment allows different signers and checks different things. */
enum class VerificationMoment {
    /** A release envelope read from a repository's catalog. */
    CATALOG,

    /** A bundle downloaded for a catalog entry. */
    CATALOG_INSTALL,

    /** A bundle file a person put on the device. */
    FILE_INSTALL,

    /** An installed bundle, before its code is loaded. */
    LAUNCH,
}

/** Where [BundleVerifier] gets what it trusts. Split out so the decisions run in a plain JVM test. */
interface BundleTrustSources {
    /** The Enginehost signing root's pinned public key, or null in a build that pins none. */
    val root: TrustAnchor.Root?

    /** The key a person pinned for a repository they added, as SubjectPublicKeyInfo DER, or null. */
    fun customKey(origin: String): ByteArray?

    /** What the revocation list held for [catalog] ("enginehost", or an added repository's `owner/repo`) revokes. */
    fun revoked(catalog: String): RevokedSet

    /**
     * Verifies the legacy raw-key signature and answers its signer, or
     * throws ([LegacyBundleSignatures]). Asked only where [BundleVerifier]
     * allows the legacy format.
     */
    fun legacy(manifestBytes: ByteArray, origin: String, signature: ByteArray, moment: VerificationMoment): BundleSigner
}

/**
 * The one place a bundle's signature is decided, at every moment: catalog,
 * install and launch (docs/engine-bundle-format.md "Signatures").
 *
 * A v1 signature is verified up to an anchor: the Enginehost signing root for
 * Enginehost's own plugins (and the developer's certificate), or the key a
 * person pinned for a repository they added. When such an anchor exists for the bundle's
 * origin, the JWS decides alone: there is no falling back to the legacy
 * signature beside it, so a bad v1 signature is never rescued by an old one.
 *
 * The legacy signature is bounded: an installed bundle keeps verifying with it
 * at launch until it is updated, and a new install or catalog entry may use
 * it only while this build pins no root key (without one, no v1 official
 * bundle can be verified, and refusing legacy would leave nothing
 * installable). With the root pinned, every new install and update needs a
 * v1 signature.
 */
object BundleVerifier {
    const val ENGINEHOST_CATALOG = "enginehost"

    fun verify(
        manifestBytes: ByteArray,
        origin: String,
        bundleId: String,
        signatures: BundleSignatures,
        moment: VerificationMoment,
        sources: BundleTrustSources,
        now: Date,
    ): BundleSigner {
        val clock = if (moment == VerificationMoment.LAUNCH) null else now
        val jws = signatures.jws
        val root = sources.root
        val customKey = sources.customKey(origin)
        if (jws != null && (root != null || customKey != null)) {
            return verifyCertified(jws, manifestBytes, origin, bundleId, moment, root, customKey, sources, clock)
        }
        val legacy = requireNotNull(signatures.legacy) {
            if (jws != null) "This build pins no certificate this bundle's signature can be verified to"
            else "This bundle carries no v1 signature"
        }
        require(moment == VerificationMoment.LAUNCH || root == null) {
            "New installs and updates need a v1 signature; this bundle carries only the legacy one"
        }
        return sources.legacy(manifestBytes, origin, legacy, moment)
    }

    private fun verifyCertified(
        jws: String,
        manifestBytes: ByteArray,
        origin: String,
        bundleId: String,
        moment: VerificationMoment,
        root: TrustAnchor.Root?,
        customKey: ByteArray?,
        sources: BundleTrustSources,
        clock: Date?,
    ): BundleSigner {
        // The root first: an official certificate for an origin outranks a
        // key a person pinned for it, as a root-certified key always did.
        val rootFailure: Exception? = if (root != null) {
            try {
                val verified = CertificateSignatures.verify(
                    jws, SignaturePurpose.PLUGIN, root, manifestBytes, clock, sources.revoked(ENGINEHOST_CATALOG),
                )
                val kind = when {
                    verified.isDeveloper -> {
                        // The developer's certificate is good for any origin,
                        // so it never arrives through a catalog: only as a file
                        // a person installed, and then at its launches.
                        require(moment == VerificationMoment.FILE_INSTALL || moment == VerificationMoment.LAUNCH) {
                            "A developer-signed bundle is installed only from a file"
                        }
                        SignerKind.DEVELOPER
                    }
                    else -> {
                        require(verified.namesOrigin(origin)) { "The signing certificate is not for $origin" }
                        SignerKind.OFFICIAL
                    }
                }
                require(verified.allowsPlugin(bundleId)) { "The signing certificate does not cover $bundleId" }
                return signer(verified, kind, root)
            } catch (error: IllegalArgumentException) {
                error
            } catch (error: java.security.GeneralSecurityException) {
                error
            } catch (error: org.json.JSONException) {
                error
            }
        } else null
        if (customKey != null) {
            val anchor = TrustAnchor.PinnedKey(customKey)
            val verified = CertificateSignatures.verify(
                jws, SignaturePurpose.PLUGIN, anchor, manifestBytes, clock, sources.revoked(VerifiedJws.originId(origin)),
            )
            require(verified.namesOrigin(origin)) { "The signing certificate is not for $origin" }
            require(verified.allowsPlugin(bundleId)) { "The signing certificate does not cover $bundleId" }
            return signer(verified, SignerKind.CUSTOM, anchor)
        }
        throw requireNotNull(rootFailure)
    }

    private fun signer(verified: VerifiedJws, kind: SignerKind, anchor: TrustAnchor) = BundleSigner(
        verified.leafKeySha256,
        kind,
        format = 1,
        anchorKeySha256 = anchor.keySha256,
        chainCertSha256 = verified.chain.map { sha256(it.encoded) },
        chainKeySha256 = verified.chain.map { sha256(it.publicKey.encoded) },
    )
}
