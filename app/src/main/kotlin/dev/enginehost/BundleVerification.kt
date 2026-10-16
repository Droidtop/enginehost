package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject

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
 * The legacy signature is deprecated (owner, 2026-10-16). It is accepted only
 * while this build pins no root key (without one, no v1 official bundle can
 * be verified, and refusing legacy would leave nothing installable). With
 * the root pinned it is refused at every moment: an install, an update, and
 * the launch of a core installed with it, which says to remove the core and
 * install it again ([LEGACY_INSTALLED]).
 */
object BundleVerifier {
    const val ENGINEHOST_CATALOG = "enginehost"

    /** Why an installed core signed only the legacy way does not start, and what to do about it. */
    const val LEGACY_INSTALLED =
        "this core was installed with an old signature that Enginehost no longer accepts. " +
            "Remove it on Cores and install it again from Add."

    fun verify(
        manifestBytes: ByteArray,
        origin: String,
        bundleId: String,
        signatures: BundleSignatures,
        moment: VerificationMoment,
        sources: BundleTrustSources,
    ): BundleSigner {
        val jws = signatures.jws
        val root = sources.root
        val customKey = sources.customKey(origin)
        if (jws != null && (root != null || customKey != null)) {
            return verifyCertified(jws, manifestBytes, origin, bundleId, moment, root, customKey, sources)
        }
        val legacy = requireNotNull(signatures.legacy) {
            if (jws != null) "This build pins no certificate this bundle's signature can be verified to"
            else "This bundle carries no v1 signature"
        }
        // Legacy keys are deprecated (owner, 2026-10-16: "Detect legacy
        // plugins if someone DOES have one installed, and instruct the user
        // to delete the old one and download the new one"): with the root
        // pinned, a legacy-only signature is refused at every moment, a
        // launch included, and the reason says what to do.
        require(root == null) {
            if (moment == VerificationMoment.LAUNCH) LEGACY_INSTALLED
            else "New installs and updates need a v1 signature; this bundle carries only the legacy one"
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
    ): BundleSigner {
        // The root first: an official certificate for an origin outranks a
        // key a person pinned for it, as a root-certified key always did.
        val rootFailure: Exception? = if (root != null) {
            try {
                val verified = CertificateSignatures.verify(
                    jws, SignaturePurpose.PLUGIN, root, manifestBytes, sources.revoked(ENGINEHOST_CATALOG),
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
                jws, SignaturePurpose.PLUGIN, anchor, manifestBytes, sources.revoked(VerifiedJws.originId(origin)),
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
