package dev.enginehost

import android.content.Context
import dev.enginehost.api.EnginePluginContract
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

data class BundleFileRecord(val path: String, val size: Long, val sha256: String, val mode: Int)

data class EngineBundleManifest(
    val rawBytes: ByteArray,
    val assetName: String,
    val bundleId: String,
    val info: PluginInfo,
    val apiVersion: Int,
    val entrypoint: String,
    val origin: String,
    val publicKeySpki: ByteArray,
    val signingKeySha256: String,
    val dexFiles: List<String>,
    val resourceApks: List<String>,
    val runtimeTransport: String,
    val payloadSha256: String,
    val files: List<BundleFileRecord>,
    /**
     * The engines this bundle runs, as the plugin's author names them for a
     * person ("RPG Maker XP", "RPG Maker VX Ace"). Optional: when a bundle
     * says nothing, the host derives the list from its capabilities.
     */
    val engines: List<String> = emptyList(),
) {
    fun installedRecord(archiveSha256: String): JSONObject = JSONObject()
        .put("formatVersion", 1)
        .put("bundleId", bundleId)
        .put("engine", info.engine)
        .put("engines", org.json.JSONArray(info.engines))
        .put("pluginVersion", info.pluginVersion.toString())
        .put("apiVersion", apiVersion)
        .put("entrypoint", entrypoint)
        .put("origin", origin)
        .put("signingKeySha256", signingKeySha256)
        .put("archiveSha256", archiveSha256)
        .put("dexFiles", JSONArray(dexFiles))
        .put("resourceApks", JSONArray(resourceApks))
        .put("runtimeTransport", runtimeTransport)
        .put("capabilities", JSONArray().apply { info.capabilities.forEach { put(it.toJson()) } })
}

object EngineBundleManifestReader {
    fun parse(rawBytes: ByteArray): EngineBundleManifest {
        val json = JSONObject(rawBytes.toString(Charsets.UTF_8))
        require(json.getInt("formatVersion") == EnginePluginContract.ENGINE_BUNDLE_FORMAT_VERSION) {
            "Unsupported engine bundle format"
        }
        val bundleId = json.requiredString("bundleId")
        require(bundleId.matches(BUNDLE_ID)) { "Invalid bundle ID" }
        val origin = normalizeGithubOrigin(json.requiredString("origin"))
        require(GITHUB_ORIGIN.matches(origin)) { "Bundle origin must be a GitHub repository" }
        val signing = json.getJSONObject("signing")
        require(signing.requiredString("algorithm") == SIGNATURE_ALGORITHM) { "Unsupported signing algorithm" }
        val publicKey = Base64.getDecoder().decode(signing.requiredString("publicKeySpki"))
        val fingerprint = sha256(publicKey)
        require(fingerprint == signing.requiredSha256("keySha256")) { "Signing-key fingerprint mismatch" }
        val capabilityDocument = JSONObject()
            .put("schemaVersion", 1)
            .put("capabilities", json.getJSONArray("capabilities"))
        val files = json.getJSONArray("files").let { array ->
            (0 until array.length()).map { index ->
                val file = array.getJSONObject(index)
                BundleFileRecord(
                    validateBundlePath(file.requiredString("path")),
                    file.getLong("size").also { require(it >= 0) },
                    file.requiredSha256("sha256"),
                    file.optInt("mode", 0b100_100_100).also { require(it in 0..0x1ff) { "Invalid file mode" } },
                )
            }
        }
        require(files.isNotEmpty()) { "Bundle payload cannot be empty" }
        require(files.map { it.path }.distinct().size == files.size) { "Duplicate paths in bundle manifest" }
        files.fold(0L) { total, file ->
            require(file.size <= MAX_UNPACKED_BYTES - total) { "Bundle exceeds unpacked size limit" }
            total + file.size
        }
        val dexFiles = json.getJSONArray("dexFiles").let { array ->
            (0 until array.length()).map { validateBundlePath(array.getString(it)) }
        }
        require(dexFiles.isNotEmpty() && dexFiles.all { dex -> files.any { it.path == dex } }) {
            "Every dex file must be part of the signed payload"
        }
        val resourceApks = json.optJSONArray("resourceApks")?.let { array ->
            (0 until array.length()).map { validateBundlePath(array.getString(it)) }
        }.orEmpty()
        require(resourceApks.all { apk -> files.any { it.path == apk } }) {
            "Every resource APK must be part of the signed payload"
        }
        val engines = json.optJSONArray("engines")?.let { array ->
            (0 until array.length()).map { array.getString(it).trim() }.filter(String::isNotEmpty)
        }.orEmpty()
        return EngineBundleManifest(
            rawBytes,
            json.requiredString("assetName").also {
                require(it.endsWith(".enginehost.tar.xz") && '/' !in it && '\\' !in it) { "Invalid bundle asset name" }
            },
            bundleId,
            PluginInfo(
                json.requiredString("engine"),
                Version.parse(json.requiredString("pluginVersion")),
                PluginCapabilitiesReader.parse(capabilityDocument.toString()),
                engines = engines,
            ),
            json.getInt("apiVersion").also { require(it > 0) },
            json.requiredString("entrypoint"),
            origin,
            publicKey,
            fingerprint,
            dexFiles,
            resourceApks,
            json.optString("runtimeTransport", RUNTIME_TRANSPORT_PLUGIN).also {
                require(it == RUNTIME_TRANSPORT_PLUGIN || it == RUNTIME_TRANSPORT_ACTIVITY) {
                    "Unsupported runtime transport"
                }
            },
            json.requiredSha256("payloadSha256"),
            files,
            engines = engines,
        )
    }

    fun verifySignature(manifest: EngineBundleManifest, signatureBytes: ByteArray) {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(manifest.publicKeySpki))
        require(publicKey is ECPublicKey && publicKey.params.curve.field.fieldSize == 256) {
            "Engine bundles must use an ECDSA P-256 key"
        }
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(manifest.rawBytes)
        require(verifier.verify(signatureBytes)) { "Engine bundle signature is invalid" }
    }
}

/** Installs one self-verifying .tar.xz package into Enginehost-private storage. */
object EngineBundleInstaller {
    fun install(
        context: Context,
        archive: File,
        expectedManifest: EngineBundleManifest? = null,
    ): InstalledPlugin {
        require(archive.isFile) { "Engine bundle does not exist" }
        val archiveSha = sha256(archive)
        val root = PluginRegistry.root(context)
        val staging = File(root, "$STAGING_PREFIX${UUID.randomUUID()}")
        require(staging.mkdir()) { "Could not create bundle staging directory" }
        try {
            val extracted = extractVerified(archive, staging)
            val manifest = extracted.manifest
            require(expectedManifest == null || manifest.rawBytes.contentEquals(expectedManifest.rawBytes)) {
                "Downloaded bundle does not match the selected catalog entry"
            }
            require(PluginOriginKeyStore(context).matches(manifest.origin, manifest.signingKeySha256)) {
                "Bundle signer does not match the key pinned for ${manifest.origin}"
            }
            // Every directory carrying this bundle ID, not just the first:
            // a crash between a previous replacement's rename and its cleanup
            // can leave a superseded build behind, and treating the whole set
            // here makes a retried install remove it rather than trip on it.
            val existing = PluginRegistry.discover(context).filter { it.bundleId == manifest.bundleId }
            existing.forEach { previous ->
                require(previous.archiveSha256 != archiveSha) { "Bundle ${manifest.bundleId} is already installed" }
                // Same line, different bytes: only a strictly newer build from
                // the same origin may replace what is there. The replacement
                // arrives unapproved -- trust decisions bind the exact archive
                // digest and signer, so the user re-approves the new build
                // before it ever executes.
                require(PluginUpdates.isNewerBuildOf(previous, manifest)) {
                    "Bundle ${manifest.bundleId} build ${previous.info.pluginVersion} is already installed; " +
                        "only a newer build from the same repository can replace it"
                }
            }
            File(staging, PluginRegistry.SIGNED_MANIFEST).writeBytes(manifest.rawBytes)
            File(staging, PluginRegistry.SIGNED_SIGNATURE).writeText(
                Base64.getEncoder().encodeToString(extracted.signatureBytes),
                Charsets.US_ASCII,
            )
            File(staging, PluginRegistry.INSTALL_RECORD).writeText(manifest.installedRecord(archiveSha).toString())
            staging.walkBottomUp().forEach { file ->
                file.setReadable(true, true)
                file.setWritable(false, false)
                if (file.isDirectory) file.setExecutable(true, true)
            }
            val destination = File(root, "${manifest.bundleId}--${archiveSha.take(16).lowercase()}")
            require(staging.renameTo(destination)) { "Could not atomically install engine bundle" }
            // Only after the replacement is fully installed. A crash between
            // the rename and this cleanup at worst leaves the superseded build
            // behind alongside the new one; the existing-set handling above
            // clears it on the next install of a newer build.
            existing.forEach { previous ->
                previous.directory.walkBottomUp().forEach { it.setWritable(true, true) }
                previous.directory.deleteRecursively()
            }
            return PluginRegistry.readRecord(destination)
        } catch (error: Throwable) {
            forceDeleteRecursively(staging)
            throw error
        }
    }

    /*
     * Deletes every leftover STAGING_PREFIX directory under the bundle root.
     *
     * The catch block in install() above only runs when installation fails
     * by throwing. It never runs when the process is killed outright
     * instead -- another component force-stopping the app mid-unpack, the
     * system reclaiming memory, and so on -- which leaves a staging
     * directory holding a partial bundle (up to hundreds of MB) on disk
     * forever, since nothing else ever revisits it.
     *
     * Call this once, from EnginehostApplication.onCreate, before this
     * process has had any opportunity to call install() itself. That
     * timing is what makes the sweep safe rather than a size or age
     * threshold: install() is only ever invoked from the default process.
     * RuntimeActivity and BundledActivityProxy, the two components
     * declared with android:process=":runtime" in the manifest, never
     * install bundles. So a staging directory that exists at the moment
     * this fresh default-process instance starts up cannot belong to an
     * install this process is running, since none has started yet, and
     * cannot belong to one still running in another process either,
     * because the only other process that ever calls install() is the
     * previous incarnation of this very process, and that incarnation is,
     * by construction, the one that just ended. EnginehostApplication
     * restricts the call to the default process for exactly this reason:
     * running it from the runtime process own onCreate would have no such
     * guarantee, since the default process could be mid-install at that
     * exact moment.
     */
    fun sweepOrphanedStaging(context: Context) = sweepOrphanedStagingIn(PluginRegistry.root(context))

    /**
     * Split out from [sweepOrphanedStaging] so it can be exercised in a
     * plain JVM test against a temp directory, with no Android Context
     * involved.
     */
    internal fun sweepOrphanedStagingIn(root: File) {
        root.listFiles().orEmpty().forEach { entry ->
            if (entry.isDirectory && entry.name.startsWith(STAGING_PREFIX)) {
                runCatching { forceDeleteRecursively(entry) }
            }
        }
    }

    private fun extractVerified(archive: File, staging: File): ExtractedBundle {
        archive.inputStream().buffered().use { compressed ->
            @Suppress("DEPRECATION")
            XZCompressorInputStream(compressed, false, XZ_MEMORY_LIMIT_KIB).use { xz ->
                TarArchiveInputStream(BufferedInputStream(xz)).use { tar ->
                    val manifestEntry = tar.nextEntry ?: error("Bundle is empty")
                    requireHeader(manifestEntry, EnginePluginContract.ENGINE_BUNDLE_MANIFEST, MAX_MANIFEST_BYTES)
                    val manifest = EngineBundleManifestReader.parse(readEntry(tar, manifestEntry.size, MAX_MANIFEST_BYTES))

                    val signatureEntry = tar.nextEntry ?: error("Bundle signature is missing")
                    requireHeader(signatureEntry, EnginePluginContract.ENGINE_BUNDLE_SIGNATURE, MAX_SIGNATURE_BYTES)
                    val signature = Base64.getMimeDecoder().decode(
                        readEntry(tar, signatureEntry.size, MAX_SIGNATURE_BYTES).toString(Charsets.US_ASCII).trim(),
                    )
                    EngineBundleManifestReader.verifySignature(manifest, signature)

                    val expected = manifest.files.associateBy { it.path }
                    val orderedPaths = manifest.files.map { it.path }
                    val seen = linkedSetOf<String>()
                    val payloadDigest = MessageDigest.getInstance("SHA-256")
                    var fileIndex = 0
                    var entry = tar.nextEntry
                    while (entry != null) {
                        require(!entry.isSymbolicLink && !entry.isLink) { "Archive links are forbidden" }
                        if (entry.isDirectory) {
                            val directory = validateBundleDirectoryPath(entry.name)
                            require(orderedPaths.any { it.startsWith("$directory/") }) {
                                "Unsigned tar directory: $directory"
                            }
                            entry = tar.nextEntry
                            continue
                        }
                        val path = validateBundlePath(entry.name)
                        require(entry.isFile) { "Unsupported tar entry: $path" }
                        require(seen.add(path)) { "Duplicate tar entry: $path" }
                        val record = requireNotNull(expected[path]) { "Unsigned tar entry: $path" }
                        require(fileIndex < orderedPaths.size && orderedPaths[fileIndex] == path) {
                            "Payload order does not match the signed manifest"
                        }
                        fileIndex += 1
                        require(entry.size == record.size) { "Size mismatch for $path" }
                        require((entry.mode and 0x1ff) == record.mode) { "Mode mismatch for $path" }
                        val output = safeChild(staging, path)
                        output.parentFile?.mkdirs()
                        val fileDigest = MessageDigest.getInstance("SHA-256")
                        FileOutputStream(output).use { sink ->
                            copyExact(tar, sink, record.size, fileDigest, payloadDigest, path)
                        }
                        require(fileDigest.digest().hex() == record.sha256) { "Digest mismatch for $path" }
                        applySignedFileMode(output, record.mode)
                        entry = tar.nextEntry
                    }
                    require(seen == expected.keys) { "Bundle payload is missing signed files" }
                    require(payloadDigest.digest().hex() == manifest.payloadSha256) { "Bundle payload digest mismatch" }
                    return ExtractedBundle(manifest, signature)
                }
            }
        }
    }

    private fun requireHeader(entry: TarArchiveEntry, name: String, maxSize: Long) {
        require(entry.name == name && entry.isFile && entry.size in 1..maxSize) { "Invalid $name header" }
    }
}

data class ExtractedBundle(val manifest: EngineBundleManifest, val signatureBytes: ByteArray)

/** Revalidates the signed metadata and every payload byte before runtime loading. */
object InstalledBundleVerifier {
    fun verify(context: Context, installed: InstalledPlugin): EngineBundleManifest {
        val directory = installed.directory.canonicalFile
        require(directory.parentFile == PluginRegistry.root(context).canonicalFile) { "Bundle escaped the registry" }
        val manifest = EngineBundleManifestReader.parse(
            File(directory, PluginRegistry.SIGNED_MANIFEST).readBytes().also {
                require(it.size <= MAX_MANIFEST_BYTES) { "Installed manifest is too large" }
            },
        )
        val signature = Base64.getMimeDecoder().decode(
            File(directory, PluginRegistry.SIGNED_SIGNATURE).readText(Charsets.US_ASCII).trim(),
        )
        EngineBundleManifestReader.verifySignature(manifest, signature)
        require(PluginOriginKeyStore(context).matches(manifest.origin, manifest.signingKeySha256)) {
            "Installed bundle signer is no longer pinned for ${manifest.origin}"
        }
        require(manifest.bundleId == installed.bundleId && manifest.entrypoint == installed.entrypointClass) {
            "Installed bundle record does not match its signed manifest"
        }
        manifest.files.forEach { record ->
            val file = safeChild(directory, record.path)
            require(file.isFile && file.length() == record.size && sha256(file) == record.sha256) {
                "Installed bundle payload changed: ${record.path}"
            }
        }
        return manifest
    }
}

private fun readEntry(tar: TarArchiveInputStream, size: Long, maximum: Long): ByteArray {
    require(size in 0..maximum)
    val result = ByteArray(size.toInt())
    var offset = 0
    while (offset < result.size) {
        val read = tar.read(result, offset, result.size - offset)
        require(read > 0) { "Truncated tar entry" }
        offset += read
    }
    return result
}

private fun copyExact(
    input: TarArchiveInputStream,
    output: FileOutputStream,
    size: Long,
    fileDigest: MessageDigest,
    payloadDigest: MessageDigest,
    path: String,
) {
    // The aggregate digest is length-delimited and path-delimited, so two
    // different payload layouts cannot produce the same concatenation.
    payloadDigest.update(path.toByteArray())
    payloadDigest.update(0)
    payloadDigest.update(size.toString().toByteArray())
    payloadDigest.update(0)
    val buffer = ByteArray(64 * 1024)
    var remaining = size
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        require(read > 0) { "Truncated payload file: $path" }
        output.write(buffer, 0, read)
        fileDigest.update(buffer, 0, read)
        payloadDigest.update(buffer, 0, read)
        remaining -= read
    }
}

/*
 * A signed bundle payload file is extracted mode 0444, read-only, as it
 * is written, and some may still carry that mode when a staging
 * directory is cleaned up. A plain deleteRecursively() silently leaves
 * those behind, which is exactly what turned a manual rm -rf into a
 * no-op on device tonight. Restoring write permission bottom-up first
 * makes deletion actually work.
 */
private fun forceDeleteRecursively(dir: File) {
    dir.walkBottomUp().forEach { it.setWritable(true, true) }
    dir.deleteRecursively()
}

private fun safeChild(root: File, path: String): File {
    val file = File(root, path).canonicalFile
    val prefix = root.canonicalPath + File.separator
    require(file.path.startsWith(prefix)) { "Bundle path escapes installation root" }
    return file
}

internal fun validateBundlePath(path: String): String = path.replace('\\', '/').also {
    require(it.isNotBlank() && !it.startsWith('/') && !it.endsWith('/')) { "Invalid bundle path" }
    require(it.split('/').none { part -> part.isBlank() || part == "." || part == ".." }) { "Unsafe bundle path" }
    require(it.matches(Regex("[A-Za-z0-9._+@/-]+"))) { "Unsupported characters in bundle path" }
}

private fun validateBundleDirectoryPath(path: String): String = path.replace('\\', '/').trimEnd('/').also {
    require(it.isNotBlank() && !it.startsWith('/')) { "Invalid bundle directory" }
    require(it.split('/').none { part -> part.isBlank() || part == "." || part == ".." }) {
        "Unsafe bundle directory"
    }
    require(it.matches(Regex("[A-Za-z0-9._+@/-]+"))) { "Unsupported characters in bundle directory" }
}

private fun applySignedFileMode(file: File, mode: Int) {
    file.setReadable((mode and 0b100_100_100) != 0, false)
    file.setWritable((mode and 0b010_010_010) != 0, false)
    file.setExecutable((mode and 0b001_001_001) != 0, false)
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().hex()
}
private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

private fun EngineCapability.toJson() = JSONObject()
    .put("id", id)
    .putOpt("engine", engine)
    .put("engineContext", engineContext)
    .put("runtimeVersion", runtimeVersion.toString())
    .put("acceptsAnyEngineVersion", acceptsAnyEngineVersion)
    .put("supportedVersions", JSONArray(supportedVersions.map(Version::toString)))
    .put("supportedSeries", JSONArray(supportedSeries.map(VersionSeries::toString)))
    .put("supportedRanges", JSONArray().apply {
        supportedRanges.forEach { put(JSONObject().put("min", it.min.toString()).put("max", it.max.toString())) }
    })
    .put("runtimeComponents", JSONObject().apply {
        runtimeComponents.forEach { (name, version) -> put(name, version.toString()) }
    })

private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
private const val MAX_SIGNATURE_BYTES = 16L * 1024
private const val MAX_UNPACKED_BYTES = 8L * 1024 * 1024 * 1024
private const val XZ_MEMORY_LIMIT_KIB = 512 * 1024
