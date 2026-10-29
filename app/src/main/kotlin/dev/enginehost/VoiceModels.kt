package dev.enginehost

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The voice models on this device, for reading games aloud with sherpa-onnx (docs/text-services.md,
 * "Voices on the device"; Droidtop/tracker#490). The owner, 2026-10-29: the engine is in the build
 * "that way they can supply their own voices"; the models are downloads on request, pinned by
 * SHA-256, or archives the person picks. Both install the same way: unpacked under the app's own
 * files, `voices/<id>/`, with what was found recorded beside them.
 *
 * Main process. Everything here reads or writes files or the network: background threads only.
 */
object VoiceModels {
    /** A model Enginehost offers, from sherpa-onnx's own releases. */
    data class Offered(val id: String, val label: Int, val url: String, val sha256: String, val bytes: Long)

    /** One installed model: where it is and what it holds. */
    data class Installed(val id: String, val label: String, val folder: File, val layout: VoiceModelLayout, val supplied: Boolean)

    val OFFERED = listOf(
        Offered(
            "kokoro-en-v0_19", R.string.voices_kokoro_en,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
            "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd", 103_248_205,
        ),
        Offered(
            "kokoro-multi-lang-v1_0", R.string.voices_kokoro_multi,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653", 132_353_410,
        ),
    )

    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private const val DESCRIPTOR = "enginehost-voice.json"

    private fun root(context: Context) = File(context.filesDir, "voices")

    /** The models installed, by label. */
    fun installed(context: Context): List<Installed> =
        root(context).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { read(it) }
            .sortedBy { it.label.lowercase() }

    fun find(context: Context, id: String): Installed? =
        File(root(context), id).takeIf { it.isDirectory && id.isNotBlank() && '/' !in id && !id.startsWith(".") }?.let(::read)

    private fun read(folder: File): Installed? = runCatching {
        val descriptor = JSONObject(File(folder, DESCRIPTOR).readText())
        val model = File(folder, descriptor.getString("model"))
        Installed(
            folder.name, descriptor.getString("label"), model.parentFile!!,
            VoiceModelLayout.of(list(model.parentFile!!), OnnxMetadata.read(model)) ?: return@runCatching null,
            descriptor.optBoolean("supplied"),
        )
    }.getOrNull()

    /** Removes an installed model, and with it every character's choice among its voices. */
    fun remove(context: Context, id: String) {
        val folder = find(context, id)?.let { File(root(context), id) } ?: return
        folder.deleteRecursively()
        CharacterVoices(context).forgetModel(id)
    }

    /**
     * Downloads [offered], checks it against its pinned SHA-256, and installs it; [progress] gets the
     * bytes so far. Throws when the download fails or is not the file pinned.
     */
    fun download(context: Context, offered: Offered, progress: (Long) -> Unit): Installed {
        val root = root(context).apply { mkdirs() }
        val partial = File(root, ".${offered.id}.download")
        try {
            val connection = URI(offered.url).toURL().openConnection() as HttpURLConnection
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                if (connection.responseCode !in 200..299) throw IOException("The download answered ${connection.responseCode}")
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > offered.bytes) throw IOException("The download is larger than the file pinned")
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            progress(total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != offered.sha256) throw IOException("The download is not the file pinned")
            return partial.inputStream().use { install(context, offered.id, context.getString(offered.label), it, supplied = false) }
        } finally {
            partial.delete()
        }
    }

    /** Installs a voice archive the person picked ([uri], from the system picker), named [label]. */
    fun supply(context: Context, uri: Uri, label: String): Installed {
        val id = "own-" + System.currentTimeMillis()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be opened")
        return input.use { install(context, id, label, it, supplied = true) }
    }

    /**
     * Unpacks [archive] (tar.bz2, tar.gz, tar.xz, tar or zip) and installs it as [id], when it holds a
     * voice model this build runs; throws otherwise and leaves nothing behind.
     */
    private fun install(context: Context, id: String, label: String, archive: InputStream, supplied: Boolean): Installed {
        val root = root(context).apply { mkdirs() }
        val staging = File(root, ".$id.staging").apply { deleteRecursively(); mkdirs() }
        try {
            unpack(archive, staging)
            // The model's folder: where its .onnx and tokens.txt are, at the top or one folder down,
            // as sherpa-onnx's own archives put them.
            val modelFolder = (listOf(staging) + staging.listFiles().orEmpty().filter { it.isDirectory })
                .firstOrNull { folder -> folder.listFiles().orEmpty().any { it.name.endsWith(".onnx", true) } && File(folder, "tokens.txt").isFile }
                ?: throw IOException("The archive holds no voice model: no .onnx with a tokens.txt beside it")
            val files = list(modelFolder)
            val onnx = files.filter { '/' !in it && it.endsWith(".onnx", true) }
            val probe = VoiceModelLayout.of(files, onnx.firstOrNull()?.let { OnnxMetadata.read(File(modelFolder, it)) }.orEmpty())
                ?: throw IOException("The archive's model is not a kind this build reads aloud with (Kokoro or VITS)")
            // Read again with the chosen model's own metadata, which names its voices.
            val metadata = OnnxMetadata.read(File(modelFolder, probe.model))
            val layout = VoiceModelLayout.of(files, metadata) ?: throw IOException("The archive's model is not one this build reads aloud with")
            val relative = File(modelFolder, layout.model).relativeTo(staging).path
            File(staging, DESCRIPTOR).writeText(JSONObject().put("label", label).put("model", relative).put("supplied", supplied).toString())
            val folder = File(root, id)
            folder.deleteRecursively()
            if (!staging.renameTo(folder)) throw IOException("Could not move the voices into place")
            return read(folder) ?: throw IOException("The installed voices could not be read back")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The files under [folder], relative, "/" between folders. */
    private fun list(folder: File): List<String> =
        folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toList()

    /**
     * Unpacks [input] into [into]. The format is read from its first bytes, not its name: plain files
     * and folders only, each a relative path with no "." or ".." segment; a link or device refuses the
     * archive, as the core installer does ([CoreUnpack]).
     */
    internal fun unpack(input: InputStream, into: File) {
        val buffered = BufferedInputStream(input, 1 shl 16)
        buffered.mark(8)
        val head = ByteArray(6).also { buffered.read(it) }
        buffered.reset()
        val decompressed: InputStream = when {
            head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte() -> BZip2CompressorInputStream(buffered)
            head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> GZIPInputStream(buffered)
            head[0] == 0xFD.toByte() && head[1] == '7'.code.toByte() && head[2] == 'z'.code.toByte() -> XZCompressorInputStream(buffered)
            else -> buffered
        }
        val archive: ArchiveInputStream<out ArchiveEntry> =
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) ZipArchiveInputStream(decompressed) else TarArchiveInputStream(decompressed)
        var bytes = 0L
        var entries = 0
        archive.use {
            while (true) {
                val entry = archive.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw IOException("The archive has too many entries")
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                if (!GameWrites.isSubFolder(name)) throw IOException("Unsafe path in the archive: ${entry.name}")
                if (entry is TarArchiveEntry && (entry.isSymbolicLink || entry.isLink || entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO)) {
                    throw IOException("Not a file or folder in the archive: ${entry.name}")
                }
                val target = File(into, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = archive.read(buffer)
                        if (read < 0) break
                        bytes += read
                        if (bytes > MAX_BYTES) throw IOException("The archive is too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}
