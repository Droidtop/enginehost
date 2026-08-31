package dev.enginehost

import android.app.Activity
import android.content.Intent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads, verifies and atomically installs a self-contained engine bundle. */
object PluginInstaller {
    fun install(activity: Activity, plugin: AvailablePlugin, onError: (String) -> Unit) {
        Thread {
            runCatching {
                val archive = download(activity, plugin)
                val installed = EngineBundleInstaller.install(activity, archive, plugin.manifest)
                PendingPluginLaunchStore(activity).peek()?.let {
                    PendingPluginLaunchStore(activity).setBundle(installed.bundleId)
                }
                activity.runOnUiThread {
                    activity.startActivity(
                        Intent(activity, PluginTrustActivity::class.java)
                            .putExtra(PluginTrustActivity.EXTRA_BUNDLE, installed.bundleId),
                    )
                }
            }.onFailure { error ->
                activity.runOnUiThread { onError(error.message ?: "Engine bundle installation failed") }
            }
        }.start()
    }

    private fun download(activity: Activity, plugin: AvailablePlugin): File {
        val directory = File(activity.cacheDir, "engine-bundle-downloads").apply { mkdirs() }
        val cacheName = plugin.archiveSha256?.lowercase() ?: sha256(plugin.archiveUrl.toByteArray()).lowercase()
        val archive = File(directory, "$cacheName.enginehost.tar.xz")
        if (archive.isFile && (plugin.archiveSha256 == null || sha256(archive) == plugin.archiveSha256)) return archive
        val temporary = File(directory, archive.name + ".partial")
        val connection = URL(plugin.archiveUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "enginehost/0.1")
        try {
            require(connection.responseCode in 200..299) { "Download returned HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= MAX_ARCHIVE_BYTES || connection.contentLengthLong < 0) {
                "Engine bundle exceeds the download size limit"
            }
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            require(temporary.length() <= MAX_ARCHIVE_BYTES) { "Engine bundle exceeds the download size limit" }
        } finally {
            connection.disconnect()
        }
        plugin.archiveSha256?.let { expected ->
            require(sha256(temporary) == expected) { "Downloaded bundle does not match GitHub's asset digest" }
        }
        if (archive.exists()) archive.delete()
        require(temporary.renameTo(archive)) { "Could not retain downloaded engine bundle" }
        return archive
    }

    private const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
}
