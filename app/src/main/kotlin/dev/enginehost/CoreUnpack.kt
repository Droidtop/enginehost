package dev.enginehost

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * A core's own runtime, packed as a tar.gz asset in its verified resource APK, which
 * Enginehost unpacks for it (docs/engine-bundle-format.md "unpack"). Ren'Py's RAPT keeps its
 * Python runtime in `assets/private.mp3` and unpacks it itself on first launch; under the
 * lockdown the core may not write, so the host does it (owner, 2026-10-23: "Host unpacks,
 * core read-only"). [stamp] goes into [stampFile] in the unpacked folder, which is how the
 * core recognises its runtime as already unpacked (RAPT's `private.version`).
 */
data class CoreUnpackSpec(val apk: String, val asset: String, val stampFile: String, val stamp: String) {
    fun toJson(): JSONObject = JSONObject().put("apk", apk).put("asset", asset).put("stampFile", stampFile).put("stamp", stamp)

    companion object {
        fun parse(json: JSONObject?): CoreUnpackSpec? = json?.let {
            CoreUnpackSpec(it.getString("apk"), it.getString("asset"), it.getString("stampFile"), it.getString("stamp")).also { spec ->
                require(GameWrites.isSubFolder(spec.apk)) { "unpack.apk must be a path in the bundle: ${spec.apk}" }
                require(GameWrites.isSubFolder(spec.asset)) { "unpack.asset must be a path in the APK: ${spec.asset}" }
                require(GameWrites.isSubFolder(spec.stampFile) && '/' !in spec.stampFile) { "unpack.stampFile must be a file name" }
                require(spec.stamp.isNotEmpty() && spec.stamp.length <= 256 && spec.stamp.none { c -> c == '\n' || c == '\r' }) {
                    "unpack.stamp must be one short line"
                }
            }
        }
    }
}

/**
 * Unpacks a core's runtime ([CoreUnpackSpec]) into a folder of Enginehost's, one per
 * installed build, before the lockdown, and checks it again on every launch: the stamps of
 * every file (size, inode, change and modification times, which the kernel sets) are
 * recorded when it is unpacked, and anything that differs, a file added, removed or written,
 * unpacks it again from the verified bundle. A locked-down core reads the folder and cannot
 * write it ([RuntimeSandbox.policy]), so no game can change the runtime later games load.
 */
object CoreUnpacker {
    private const val TAG = "EnginehostRuntime"
    private const val MAX_BYTES = 512L shl 20
    private const val MAX_ENTRIES = 20_000

    fun folder(context: Context, installed: InstalledPlugin): File =
        File(File(context.filesDir, "core-runtime"), "runtime-${installed.bundleId}--${installed.archiveSha256.lowercase().take(16)}")

    /** The core's unpacked runtime, unpacked now unless it is there and unchanged. Host code, before the core loads. */
    fun ensure(context: Context, installed: InstalledPlugin): File {
        val spec = checkNotNull(installed.unpack) { "${installed.bundleId} declares nothing to unpack" }
        val folder = folder(context, installed)
        val stamps = BundleStamps(context)
        val recorded = stamps.read(folder)
        if (recorded.isNotEmpty() && unchanged(folder, recorded)) return folder
        Log.i(TAG, if (recorded.isEmpty()) "Unpacking ${installed.bundleId}'s runtime" else "${installed.bundleId}'s runtime changed; unpacking it again")
        val apk = safeRuntimeChild(installed.directory.canonicalFile, spec.apk)
        val staging = File(folder.parentFile, "${folder.name}.staging").apply { deleteRecursively(); mkdirs() }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(spec.asset) ?: error("${spec.apk} has no ${spec.asset}")
            zip.getInputStream(entry).use { unpackTarGz(it, staging) }
        }
        File(staging, spec.stampFile).writeText(spec.stamp)
        folder.deleteRecursively()
        check(staging.renameTo(folder)) { "Could not move the unpacked runtime into place" }
        stamps.write(folder, stampTree(folder))
        return folder
    }

    /** Drops a build's unpacked runtime and its stamps, when the build is removed. */
    fun forget(context: Context, installed: InstalledPlugin) {
        val folder = folder(context, installed)
        folder.deleteRecursively()
        BundleStamps(context).forget(folder)
    }

    internal fun unchanged(folder: File, recorded: Map<String, FileStamp>): Boolean =
        runCatching { stampTree(folder) == recorded }.getOrDefault(false)

    internal fun stampTree(folder: File): Map<String, FileStamp> =
        folder.walkTopDown().filter(File::isFile).associate { it.relativeTo(folder).path to stampOf(it) }

    /**
     * Unpacks [input], a tar.gz, into [into]: plain files and folders only, each a relative
     * path with no "." or ".." segment; a link, device or any other entry refuses the archive.
     */
    internal fun unpackTarGz(input: InputStream, into: File) {
        var bytes = 0L
        var entries = 0
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "The runtime archive has too many entries" }
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                require(GameWrites.isSubFolder(name)) { "Unsafe path in the runtime archive: ${entry.name}" }
                // TarArchiveEntry.isFile is true for any entry not named like a folder, links
                // included, so links and special files are refused first, as the installer does.
                require(!entry.isSymbolicLink && !entry.isLink && !entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO) {
                    "Not a file or folder in the runtime archive: ${entry.name}"
                }
                val target = File(into, name)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isFile -> {
                        bytes += entry.size
                        require(bytes <= MAX_BYTES) { "The runtime archive is too large" }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val copied = tar.copyTo(output)
                            require(copied == entry.size) { "Truncated entry in the runtime archive: $name" }
                        }
                    }
                    else -> throw IllegalArgumentException("Not a file or folder in the runtime archive: ${entry.name}")
                }
            }
        }
    }
}
