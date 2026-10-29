package dev.enginehost

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File

/**
 * Update story for the Enginehost APK itself.
 *
 * What Android actually allows a normally-installed app: it may download a new
 * APK of itself and open a PackageInstaller session for it, and the system
 * asks the user to confirm ("install unknown apps" must be enabled for
 * Enginehost the first time). Silent self-replacement is only possible once
 * Enginehost is its own installer of record -- true after the first in-app
 * update -- on Android 12+ via UPDATE_PACKAGES_WITHOUT_USER_ACTION; the
 * session requests that and the system falls back to the confirmation dialog
 * whenever the conditions do not hold. Signature continuity is enforced by
 * Android itself: a downloaded APK not signed with the same persistent CI key
 * simply refuses to install over the existing app.
 *
 * "Is this newer" is answered by versionCode, which CI sets to the workflow
 * run number -- a plain monotonic integer -- and publishes alongside the APK
 * in release-info.json on the rolling `latest` release. The check is one
 * unauthenticated download of that small file; nothing is sent.
 *
 * The release carries two APKs, the release build and the debug build (for
 * test rigs), and an install updates to the build type it already is: a
 * debug install that updated itself to release would lose the adb harness
 * its rig depends on, and a person's release install never becomes debug.
 */
object AppUpdate {
    private const val RELEASES = "https://github.com/Droidtop/enginehost/releases/download/latest"
    const val RELEASE_INFO_URL = "$RELEASES/release-info.json"

    data class Info(val versionCode: Long, val versionName: String, val apkName: String, val apkSha256: String) {
        val apkUrl: String get() = "$RELEASES/$apkName"
    }

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Fetches what the rolling release currently is, for this install's build type. Throws on any failure. */
    fun fetch(context: Context): Info {
        // A non-2xx answer throws GitHubHttpException with its status; the update never sends the person's token.
        val json = JSONObject(checkNotNull(GithubClients.anonymous().get(RELEASE_INFO_URL)).text())
        require(json.getInt("formatVersion") == 1) { "Unsupported release info" }
        val debug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return Info(
            json.getLong("versionCode"),
            json.requiredString("versionName"),
            json.requiredString(if (debug) "debugApkName" else "apkName"),
            json.requiredSha256(if (debug) "debugApkSha256" else "apkSha256"),
        )
    }

    /**
     * Downloads the release APK, verifies its digest against the published
     * release info, and hands it to the system installer. The system takes it
     * from there; [onStatus] narrates, [onError] reports any failure, and
     * [onHandedOver] says the session is committed. Nothing comes back after
     * that when the install does not happen: the confirmation can be
     * dismissed, and the "install unknown apps" round-trip can return to the
     * app with the install never started (rig, tracker#401). The caller
     * decides when the hand-over is over.
     */
    fun downloadAndInstall(
        activity: Activity,
        info: Info,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
        onHandedOver: () -> Unit,
    ) {
        // The work holds the application, not the screen, and every callback
        // runs on the main thread and only while the screen still exists: the
        // download can outlast the screen that started it (review, 2026-10-16).
        val context = activity.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun post(block: () -> Unit) = main.post { if (!activity.isDestroyed) block() }
        Thread {
            runCatching {
                val downloading = context.getString(R.string.app_update_downloading, info.versionName)
                post { onStatus(downloading) }
                val apk = download(context, info)
                val handing = context.getString(R.string.app_update_handing_to_installer)
                post { onStatus(handing) }
                commitSession(context, apk)
                post(onHandedOver)
            }.onFailure { error ->
                android.util.Log.w(TAG, "App update failed", error)
                val message = context.getString(R.string.app_update_failed)
                post { onError(message) }
            }
        }.start()
    }

    private const val TAG = "enginehost-app-update"

    /** Screens waiting on an install session they handed over; see [sessionEnded]. */
    private val sessionListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addSessionEndListener(listener: (String) -> Unit) {
        sessionListeners += listener
    }

    fun removeSessionEndListener(listener: (String) -> Unit) {
        sessionListeners -= listener
    }

    /**
     * The installer ended a session without installing (failed, aborted, or
     * its confirmation could not be shown): tells the screens waiting on it,
     * on the main thread, and answers whether any was. Before this a failure
     * was only a toast, and the screen kept waiting for its hand-over to end.
     */
    internal fun sessionEnded(message: String): Boolean {
        val listeners = sessionListeners.toList()
        listeners.forEach { it(message) }
        return listeners.isNotEmpty()
    }

    private fun download(context: Context, info: Info): File {
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val apk = File(directory, "enginehost-${info.versionCode}.apk")
        if (apk.isFile && sha256(apk) == info.apkSha256) return apk
        val temporary = File(directory, apk.name + ".partial")
        try {
            temporary.outputStream().buffered().use { output ->
                GithubClients.anonymous().download(info.apkUrl, output, MAX_APK_BYTES)
            }
            // The digest published next to the APK is the gate: bytes that do
            // not match it are discarded, whatever served them.
            require(sha256(temporary) == info.apkSha256) { "Downloaded APK does not match the published digest" }
            if (apk.exists()) apk.delete()
            require(temporary.renameTo(apk)) { "Could not retain downloaded APK" }
            return apk
        } finally {
            temporary.delete()
        }
    }

    /** Far more than any build of Enginehost, small enough to refuse a runaway download. */
    private const val MAX_APK_BYTES = 1024L * 1024 * 1024

    private fun commitSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                // Silent only when the system itself decides Enginehost is
                // eligible (its own installer of record, and so on); otherwise
                // the normal confirmation dialog appears.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("enginehost.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, AppUpdateStatusReceiver::class.java)
                .setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }
}

/**
 * Receives PackageInstaller's verdict. The one status that needs code is
 * PENDING_USER_ACTION: the system hands over its confirmation UI to launch.
 * Success needs none -- the process is replaced mid-update.
 */
class AppUpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val failed = context.getString(R.string.app_update_failed)
        fun ended() {
            if (!AppUpdate.sessionEnded(failed)) {
                android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return ended()
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure { ended() }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                // The installer's own text is for the log, not the person.
                android.util.Log.w(
                    "enginehost-app-update",
                    "Install session ended with status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
                )
                ended()
            }
        }
    }
}
