package dev.enginehost

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Whether a package name Enginehost was told is droidtop really is: droidtop's
 * own package under droidtop's own signing certificate, checked against
 * PackageManager, never against anything an intent's own extras said about
 * themselves. Used only to decide whether an external `LAUNCH` gets
 * [LaunchActivity]'s external-caller consent prompt (see
 * [LaunchEntryActivity]); it grants no permission, and nothing is skipped or
 * assumed true if the check itself cannot run.
 */
object TrustedCallers {
    const val DROIDTOP_PACKAGE = "dev.droidtop.app"

    /**
     * SHA-256 of the X.509 certificate droidtop's APK is signed with.
     * Extracted 2026-09-27 from both assets of the `Droidtop/droidtop`
     * `latest` release (`droidtop-latest.apk`, `droidtop-latest-debug.apk`);
     * both carry this same certificate, so one entry covers rig and
     * production installs alike. There is one droidtop signer, unlike
     * Enginehost's own per-repository operational subkeys
     * (docs/plugin-catalog.md): this is the app's own APK signature, a
     * separate thing from any origin key.
     */
    private const val DROIDTOP_CERT_SHA256 =
        "38b1ef4bc47fa8cd62ffcbb43fa85d7f450e9c2d4a8b690776d2218b23f0749f"

    fun isDroidtop(pm: PackageManager, packageName: String): Boolean {
        if (packageName != DROIDTOP_PACKAGE) return false
        val certs = signingCertificates(pm, packageName) ?: return false
        return certs.any { sha256Hex(it) == DROIDTOP_CERT_SHA256 }
    }

    private fun signingCertificates(pm: PackageManager, packageName: String): List<ByteArray>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            val history = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            history?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
