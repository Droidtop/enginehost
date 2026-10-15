package dev.enginehost

import android.content.Context
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * The legacy signature format, kept only as far as [BundleVerifier] allows
 * it (docs/engine-bundle-format.md "Signatures", "Legacy signatures"): the
 * manifest names its own P-256 key, `enginehost-bundle.sig` is that key's
 * ECDSA signature over the manifest bytes, and the key must be pinned for the
 * origin ([PluginOriginKeyStore]: root-certified, the developer's, or one a
 * person accepted).
 *
 * This file is the legacy verifier. It is deleted, with the root-certified
 * key documents in PluginOriginKeys.kt and the `signing` block of the
 * manifest, in the release after every official plugin has shipped a release
 * signed in format v1 (Droidtop/tracker#550). Until then a revocation list can
 * stop a legacy key by its SubjectPublicKeyInfo SHA-256.
 */
object LegacyBundleSignatures {
    const val ALGORITHM = "SHA256withECDSA"

    /** The manifest's own key signed its bytes. Says nothing yet about whether that key is trusted. */
    fun verifyRaw(manifestBytes: ByteArray, keySpki: ByteArray, signature: ByteArray) {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keySpki))
        require(publicKey is ECPublicKey && publicKey.params.curve.field.fieldSize == 256) {
            "Engine bundles must use an ECDSA P-256 key"
        }
        val verifier = Signature.getInstance(ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        require(verifier.verify(signature)) { "Engine bundle signature is invalid" }
    }

    /**
     * Verifies a legacy-signed manifest and answers its signer. The developer's
     * key is accepted only for a file a person installed and at the launches
     * of what they installed; a key the root has since replaced only at launch.
     */
    fun signer(
        context: Context,
        manifestBytes: ByteArray,
        origin: String,
        signature: ByteArray,
        moment: VerificationMoment,
        revoked: (catalog: String) -> RevokedSet,
    ): BundleSigner {
        val manifest = EngineBundleManifestReader.parse(manifestBytes)
        val spki = requireNotNull(manifest.legacyKeySpki) { "The manifest names no legacy signing key" }
        val fingerprint = requireNotNull(manifest.legacyKeySha256)
        verifyRaw(manifestBytes, spki, signature)
        val keys = PluginOriginKeyStore(context)
        require(
            keys.matches(
                origin, fingerprint,
                allowDeveloper = moment == VerificationMoment.FILE_INSTALL || moment == VerificationMoment.LAUNCH,
                allowSuperseded = moment == VerificationMoment.LAUNCH,
            ),
        ) {
            "Bundle signer does not match the key pinned for $origin"
        }
        val kind = when {
            keys.isOfficial(origin, fingerprint) -> SignerKind.OFFICIAL
            keys.isDeveloperDebug(fingerprint) -> SignerKind.DEVELOPER
            else -> SignerKind.CUSTOM
        }
        val catalog = if (kind == SignerKind.CUSTOM) VerifiedJws.originId(origin) else BundleVerifier.ENGINEHOST_CATALOG
        require(!revoked(catalog).revokesKey(fingerprint)) { "This bundle's signing key is revoked" }
        return BundleSigner(fingerprint, kind, format = 0, anchorKeySha256 = fingerprint, chainKeySha256 = listOf(fingerprint))
    }

    /** Whether a legacy signer still holds where nothing was verified again: its key still pinned for [origin]. */
    fun stillPinned(context: Context, origin: String, signer: BundleSigner): Boolean =
        PluginOriginKeyStore(context).matches(origin, signer.keySha256, allowDeveloper = false)
}
