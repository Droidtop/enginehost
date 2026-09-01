package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads, verifies and atomically installs a self-contained engine bundle. */
object PluginInstaller {
    fun install(
        activity: Activity,
        plugin: AvailablePlugin,
        onError: (String) -> Unit,
        onStatus: ((String) -> Unit)? = null,
    ) {
        Thread {
            runCatching {
                val archive = download(activity, plugin, onStatus)
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

    /**
     * Install a bundle the user picked from storage.
     *
     * Until now a bundle could only arrive from a published GitHub release, which
     * meant anyone without a command line could not install a plugin at all. The
     * file still goes through exactly the same verification and the same trust
     * prompt as a downloaded one -- picking it locally buys no extra privilege.
     */
    fun installFromFile(activity: Activity, uri: Uri, onError: (String) -> Unit) {
        Thread {
            runCatching {
                val archive = copyIn(activity, uri)
                val installed = EngineBundleInstaller.install(activity, archive)
                archive.delete()
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

    private fun copyIn(activity: Activity, uri: Uri): File {
        val directory = File(activity.cacheDir, "engine-bundle-downloads").apply { mkdirs() }
        val archive = File(directory, "picked-${System.currentTimeMillis()}.enginehost.tar.xz")
        activity.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not read the selected file" }
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        return archive
    }

    private fun download(
        activity: Activity,
        plugin: AvailablePlugin,
        onStatus: ((String) -> Unit)? = null,
    ): File {
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
            val totalBytes = connection.contentLengthLong
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var reportedMegabytes = -1L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val megabytes = copied shr 20
                        if (onStatus != null && megabytes != reportedMegabytes) {
                            reportedMegabytes = megabytes
                            onStatus(
                                if (totalBytes > 0) {
                                    activity.getString(
                                        R.string.downloading_progress,
                                        plugin.manifest.assetName, megabytes, totalBytes shr 20,
                                    )
                                } else {
                                    activity.getString(
                                        R.string.downloading_progress_unknown,
                                        plugin.manifest.assetName, megabytes,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            onStatus?.invoke(activity.getString(R.string.installing))
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
