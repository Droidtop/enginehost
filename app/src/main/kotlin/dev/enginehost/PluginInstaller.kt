package dev.enginehost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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

    /** Download (or reuse the cached copy of) a catalog entry's archive, quietly. */
    fun fetch(context: Context, plugin: AvailablePlugin): File = download(context, plugin, null)

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
        context: Context,
        plugin: AvailablePlugin,
        onStatus: ((String) -> Unit)? = null,
    ): File {
        val directory = File(context.cacheDir, "engine-bundle-downloads").apply { mkdirs() }
        val cacheName = plugin.archiveSha256?.lowercase() ?: sha256(plugin.archiveUrl.toByteArray()).lowercase()
        val archive = File(directory, "$cacheName.enginehost.tar.xz")
        if (archive.isFile && (plugin.archiveSha256 == null || sha256(archive) == plugin.archiveSha256)) return archive
        val temporary = File(directory, archive.name + ".partial")
        try {
            downloadTo(context, plugin, temporary, onStatus)
            plugin.archiveSha256?.let { expected ->
                val actual = sha256(temporary)
                // dq-sandbox-10 (emulator-5560): this check failed five times in a
                // row against a build BlueStacks installed correctly from the same
                // release in the same window, with nothing in logcat saying what
                // either side of the comparison actually was. Whatever the root
                // cause turns out to be (a proxy or CDN edge on this rig's own
                // network path is the leading theory -- nothing in this file's own
                // logic reuses a partial download or a stale cache entry across
                // attempts; the cache key is the expected digest itself, so a
                // different build never collides with an old one), the comparison
                // itself is worth a real log line so a future failure doesn't need
                // rig access to say more than "does not match" again.
                Log.w(
                    TAG,
                    "digest check for ${plugin.manifest.assetName}: expected $expected, got $actual " +
                        "(${temporary.length()} bytes downloaded from ${plugin.archiveUrl})",
                )
                require(actual == expected) { "Downloaded bundle does not match GitHub's asset digest" }
            }
            if (archive.exists()) archive.delete()
            require(temporary.renameTo(archive)) { "Could not retain downloaded engine bundle" }
            return archive
        } finally {
            // A failed download or a failed digest check must never leave a
            // wrong-content file sitting at this exact, deterministic path for
            // a later attempt (retry, or a second plugin sharing this cache
            // directory) to find and mistake for a real cache hit.
            temporary.delete()
        }
    }

    private fun downloadTo(
        context: Context,
        plugin: AvailablePlugin,
        temporary: File,
        onStatus: ((String) -> Unit)?,
    ) {
        val connection = URL(plugin.archiveUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "enginehost/0.1")
        // Defeats any cache along the way -- this device's own HTTP stack, or a
        // proxy on its specific network path -- serving a stale response for a
        // URL whose content changed since it was last fetched (dq-sandbox-10:
        // one candidate for a digest mismatch reproducing on one rig only).
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.useCaches = false
        // Disables OkHttp's own transparent gzip negotiation and decoding: if a
        // proxy on this rig's specific network path mishandles a compressed
        // response (strips or rewrites Content-Encoding while leaving the body
        // compressed, a known misbehaving-proxy pattern), the saved bytes would
        // silently be the wrong ones with no error at any layer before the
        // digest check. Asking for identity encoding removes that whole class
        // of failure at the cost of a slightly larger transfer, irrelevant for
        // a plugin bundle.
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            require(connection.responseCode in 200..299) { "Download returned HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= MAX_ARCHIVE_BYTES || connection.contentLengthLong < 0) {
                "Engine bundle exceeds the download size limit"
            }
            val totalBytes = connection.contentLengthLong
            var copied = 0L
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
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
                                    context.getString(
                                        R.string.downloading_progress,
                                        plugin.manifest.assetName, megabytes, totalBytes shr 20,
                                    )
                                } else {
                                    context.getString(
                                        R.string.downloading_progress_unknown,
                                        plugin.manifest.assetName, megabytes,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            onStatus?.invoke(context.getString(R.string.installing))
            require(temporary.length() <= MAX_ARCHIVE_BYTES) { "Engine bundle exceeds the download size limit" }
            // A server that declared Content-Length and then sent a different
            // number of bytes is itself evidence worth having on hand the next
            // time a digest mismatch shows up with nothing else to explain it.
            if (totalBytes >= 0 && copied != totalBytes) {
                Log.w(TAG, "download for ${plugin.manifest.assetName}: Content-Length said $totalBytes bytes, got $copied")
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val TAG = "enginehost-plugin-install"
    private const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
}
