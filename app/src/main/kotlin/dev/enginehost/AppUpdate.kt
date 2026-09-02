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
import java.net.HttpURLConnection
import java.net.URL

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
 */
object AppUpdate {
    private const val RELEASES = "https://github.com/Droidtop/enginehost/releases/download/latest"
    const val RELEASE_INFO_URL = "$RELEASES/release-info.json"

    data class Info(val versionCode: Long, val versionName: String, val apkName: String, val apkSha256: String) {
        val apkUrl: String get() = "$RELEASES/$apkName"
    }

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Fetches what the rolling release currently is. Throws on any failure. */
    fun fetch(): Info {
        val connection = URL(RELEASE_INFO_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "enginehost/0.1")
        try {
            require(connection.responseCode in 200..299) { "Release info returned HTTP ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            require(json.getInt("formatVersion") == 1) { "Unsupported release info" }
            return Info(
                json.getLong("versionCode"),
                json.requiredString("versionName"),
                json.requiredString("apkName"),
                json.requiredSha256("apkSha256"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the release APK, verifies its digest against the published
     * release info, and hands it to the system installer. The system takes it
     * from there; [onStatus] narrates, [onError] reports any failure.
     */
    fun downloadAndInstall(
        activity: Activity,
        info: Info,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            runCatching {
                onStatus(activity.getString(R.string.app_update_downloading, info.versionName))
                val apk = download(activity, info)
                onStatus(activity.getString(R.string.app_update_handing_to_installer))
                commitSession(activity, apk)
            }.onFailure { error ->
                onError(error.message ?: activity.getString(R.string.app_update_failed))
            }
        }.start()
    }

    private fun download(context: Context, info: Info): File {
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val apk = File(directory, "enginehost-${info.versionCode}.apk")
        if (apk.isFile && sha256(apk) == info.apkSha256) return apk
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "enginehost/0.1")
        try {
            require(connection.responseCode in 200..299) { "APK download returned HTTP ${connection.responseCode}" }
            val temporary = File(directory, apk.name + ".partial")
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            // The digest published next to the APK is the gate: bytes that do
            // not match it are discarded, whatever served them.
            require(sha256(temporary) == info.apkSha256) { "Downloaded APK does not match the published digest" }
            if (apk.exists()) apk.delete()
            require(temporary.renameTo(apk)) { "Could not retain downloaded APK" }
            return apk
        } finally {
            connection.disconnect()
        }
    }

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
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> android.widget.Toast.makeText(
                context,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: context.getString(R.string.app_update_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
