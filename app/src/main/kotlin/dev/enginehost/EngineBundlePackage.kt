package dev.enginehost

import dev.droidtop.shared.trust.BundleTar
import dev.droidtop.shared.trust.BundleTarException
import dev.droidtop.shared.version.Version
import dev.droidtop.shared.version.VersionSeries
import android.content.Context
import android.os.Build
import android.system.Os
import dev.enginehost.api.EnginePluginContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
    /**
     * The manifest's own `signing` key, which only the legacy signature uses
     * ([LegacyBundleSignatures]); null for a bundle signed in format v1 alone,
     * whose signer is the certificate in its JWS.
     */
    val legacyKeySpki: ByteArray?,
    val legacyKeySha256: String?,
    val dexFiles: List<String>,
    val resourceApks: List<String>,
    val runtimeTransport: String,
    /** See InstalledPlugin.isolatable (docs/engine-sandbox.md "Layer 2"). */
    val isolatable: Boolean = false,
    val payloadSha256: String,
    val files: List<BundleFileRecord>,
    /**
     * The engines this bundle runs, as the plugin's author names them for a
     * person ("RPG Maker XP", "RPG Maker VX Ace"). Optional: when a bundle
     * says nothing, the host derives the list from its capabilities.
     */
    val engines: List<String> = emptyList(),
    /** See InstalledPlugin.writesGameFolder (docs/engine-sandbox.md "Saves beside the game"). */
    val writesGameFolder: Boolean = false,
    /** See InstalledPlugin.gameWrites (docs/engine-bundle-format.md "gameWrites"). */
    val gameWrites: List<String> = emptyList(),
    /** See InstalledPlugin.unpack (docs/engine-bundle-format.md "unpack"). */
    val unpack: CoreUnpackSpec? = null,
    /** See InstalledPlugin.lockdown (docs/engine-sandbox.md "The lockdown is how a core that cannot be isolated is sandboxed"). */
    val lockdown: Boolean = false,
    /** Set for a runtime-component bundle (`"kind": "component"`), null for an engine bundle. */
    val component: ComponentInfo? = null,
    /** The bundle's own licence and the payload file with its text, when it declares one. */
    val license: BundleLicense? = null,
) {
    /** Isolated or locked down: see [InstalledPlugin.sandboxed]. */
    val sandboxed: Boolean get() = isolatable || lockdown

    /**
     * The ABIs this bundle's signed payload carries native libraries for:
     * its distinct top-level `lib/<abi>/` directories, the directories the
     * runtime's own native library path is built from. Empty means the
     * bundle has no native code and is architecture-agnostic
     * (docs/engine-bundle-format.md).
     */
    val carriedAbis: List<String>
        get() = files.mapNotNull { file ->
            val segments = file.path.split('/')
            if (segments.size > 2 && segments[0] == "lib") segments[1] else null
        }.distinct()

    fun installedRecord(archiveSha256: String, signer: BundleSigner): JSONObject = JSONObject()
        .put("formatVersion", 1)
        .put("bundleId", bundleId)
        .put("engine", info.engine)
        .put("engines", org.json.JSONArray(info.engines))
        .put("pluginVersion", info.pluginVersion.toString())
        .put("apiVersion", apiVersion)
        .put("entrypoint", entrypoint)
        .put("origin", origin)
        .put("signingKeySha256", signer.keySha256)
        .put("signer", signer.toJson())
        .put("archiveSha256", archiveSha256)
        .put("dexFiles", JSONArray(dexFiles))
        .put("resourceApks", JSONArray(resourceApks))
        .put("runtimeTransport", runtimeTransport)
        .put("isolatable", isolatable)
        .put("lockdown", lockdown)
        .put("writesGameFolder", writesGameFolder)
        .put("gameWrites", JSONArray(gameWrites))
        .putOpt("unpack", unpack?.toJson())
        .put("capabilities", JSONArray().apply { info.capabilities.forEach { put(it.toJson()) } })
        .put("kind", if (component == null) BUNDLE_KIND_ENGINE else BUNDLE_KIND_COMPONENT)
        .putOpt("component", component?.toJson())
        .putOpt("license", license?.toJson())
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
        // Only the legacy signature uses the manifest's own key; a v1 bundle's
        // signer is the certificate in its JWS (docs/engine-bundle-format.md
        // "Signatures").
        val signing = json.optJSONObject("signing")
        val legacyKey = signing?.let { Base64.getDecoder().decode(it.requiredString("publicKeySpki")) }
        val legacyFingerprint = signing?.let {
            require(it.requiredString("algorithm") == LegacyBundleSignatures.ALGORITHM) { "Unsupported signing algorithm" }
            sha256(legacyKey!!).also { fingerprint ->
                require(fingerprint == it.requiredSha256("keySha256")) { "Signing-key fingerprint mismatch" }
            }
        }
        // A component bundle carries no code of its own for the host to load
        // and serves no game directly: no dex, no entry point, no capabilities.
        // What it provides, and the licence a person accepts before approving
        // it, are required instead (docs/engine-bundle-format.md, "Runtime
        // components (subplugins)").
        val component = if (ComponentManifestReader.kind(json) == BUNDLE_KIND_COMPONENT) {
            ComponentManifestReader.component(json.getJSONObject("component")).also {
                require(it.engine == json.requiredString("engine")) { "A component names the engine it is for" }
            }
        } else null
        val license = ComponentManifestReader.license(json.optJSONObject("license"))
        require(component == null || license != null) { "A component bundle must carry its licence" }
        val capabilities = if (component != null) {
            require((json.optJSONArray("capabilities")?.length() ?: 0) == 0) { "A component bundle serves no game itself" }
            emptyList()
        } else {
            PluginCapabilitiesReader.parse(
                JSONObject().put("schemaVersion", 1).put("capabilities", json.getJSONArray("capabilities")).toString(),
            )
        }
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
        license?.let { declared ->
            require(files.any { it.path == declared.file }) { "The licence file must be part of the signed payload" }
        }
        val dexFiles = (if (component != null) json.optJSONArray("dexFiles") else json.getJSONArray("dexFiles"))
            ?.let { array -> (0 until array.length()).map { validateBundlePath(array.getString(it)) } }
            .orEmpty()
        if (component != null) {
            require(dexFiles.isEmpty()) { "A component bundle carries no code for the host to load" }
        } else {
            require(dexFiles.isNotEmpty() && dexFiles.all { dex -> files.any { it.path == dex } }) {
                "Every dex file must be part of the signed payload"
            }
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
        val runtimeTransport = json.optString("runtimeTransport", RUNTIME_TRANSPORT_PLUGIN).also {
            require(it == RUNTIME_TRANSPORT_PLUGIN || it == RUNTIME_TRANSPORT_ACTIVITY) {
                "Unsupported runtime transport"
            }
        }
        val isolatable = json.optBoolean("isolatable", false).also {
            // Layer 2 (docs/engine-sandbox.md) only fits the plugin-api
            // transport: an activity-transport plugin's own Activity is
            // what the OS starts, which an isolated service cannot be.
            require(!it || runtimeTransport == RUNTIME_TRANSPORT_PLUGIN) {
                "isolatable requires runtimeTransport: plugin"
            }
        }
        return EngineBundleManifest(
            rawBytes,
            json.requiredString("assetName").also {
                require(it.endsWith(".enginehost.tar.xz") && '/' !in it && '\\' !in it) { "Invalid bundle asset name" }
            },
            bundleId,
            PluginInfo(
                json.requiredString("engine"),
                Version.parsePlugin(json.requiredString("pluginVersion")),
                capabilities,
                engines = engines,
            ),
            json.getInt("apiVersion").also { require(it > 0) },
            if (component != null) json.optString("entrypoint") else json.requiredString("entrypoint"),
            origin,
            legacyKey,
            legacyFingerprint,
            dexFiles,
            resourceApks,
            runtimeTransport,
            isolatable,
            json.requiredSha256("payloadSha256"),
            files,
            engines = engines,
            writesGameFolder = json.optBoolean("writesGameFolder", false),
            gameWrites = GameWrites.parse(json.optJSONArray("gameWrites")),
            unpack = CoreUnpackSpec.parse(json.optJSONObject("unpack")),
            lockdown = json.optBoolean("lockdown", false),
            component = component,
            license = license,
        )
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
            // A catalog download must carry its repository's own signer; a
            // file a person installed may carry the developer's (BundleVerifier).
            val moment = if (expectedManifest == null) VerificationMoment.FILE_INSTALL else VerificationMoment.CATALOG_INSTALL
            val trust = BundleTrust(context)
            val extracted = extractVerified(archive, staging) { manifest, signatures ->
                require(expectedManifest == null || manifest.rawBytes.contentEquals(expectedManifest.rawBytes)) {
                    "Downloaded bundle does not match the selected catalog entry"
                }
                trust.verify(manifest, signatures, moment)
            }
            val manifest = extracted.manifest
            // A bundle this device cannot run would install cleanly and die
            // at System.loadLibrary on first launch, an UnsatisfiedLinkError
            // that names neither side of the mismatch. Refuse it here with a
            // sentence that does, against the same Build.SUPPORTED_ABIS the
            // runtime's own native library path is built from, so an install
            // can never disagree with the load-time choice it mirrors.
            requireRunnableAbis(manifest.bundleId, manifest.carriedAbis, Build.SUPPORTED_ABIS.toList())
            // Several builds of one line live side by side (owner, 2026-10-16:
            // "Several builds can be installed"), each in its own directory, and
            // a game's pluginVersion allowlist picks among them at launch. An
            // install therefore adds a build. It replaces only the same build
            // under another signature (a stable build re-signed in format v1:
            // same code, new archive), and the very same archive is refused.
            val existing = PluginRegistry.discoverAll(context).filter { it.bundleId == manifest.bundleId }
            existing.forEach { previous ->
                require(previous.archiveSha256 != archiveSha) { "Bundle ${manifest.bundleId} is already installed" }
                // One bundle ID is one repository's line; another repository
                // publishing the same ID is not a build of it.
                require(PluginUpdates.isSameLine(previous, manifest)) {
                    "Bundle ${manifest.bundleId} is installed from another repository"
                }
            }
            // Approval still carries only from a build of this line signed by
            // the same key (PluginTrustStore.carryApproval below); anything
            // else waits for the person before it ever executes.
            val replaced = existing.filter { PluginUpdates.isSameBuild(it, manifest) }
            File(staging, PluginRegistry.SIGNED_MANIFEST).writeBytes(manifest.rawBytes)
            extracted.signatures.legacy?.let { legacy ->
                File(staging, PluginRegistry.SIGNED_SIGNATURE).writeText(Base64.getEncoder().encodeToString(legacy), Charsets.US_ASCII)
            }
            extracted.signatures.jws?.let { jws -> File(staging, PluginRegistry.SIGNED_JWS).writeText(jws, Charsets.US_ASCII) }
            File(staging, PluginRegistry.INSTALL_RECORD).writeText(manifest.installedRecord(archiveSha, extracted.signer).toString())
            // Owner-only, read-only, for every bundle. The isolated runtime
            // never opens a bundle file by path: the host hands it
            // descriptors (IsolatedRuntimeHost.openDexFds and
            // openNativeLibraryFds), so no other UID needs to reach these.
            staging.walkBottomUp().forEach { file ->
                file.setReadable(true, true)
                file.setWritable(false, false)
                if (file.isDirectory) file.setExecutable(true, true)
            }
            val destination = File(root, "${manifest.bundleId}--${archiveSha.take(16).lowercase()}")
            require(staging.renameTo(destination)) { "Could not atomically install engine bundle" }
            // Only after the new archive is fully installed, and only the
            // same build it re-signs. A crash between the rename and this
            // cleanup leaves the old copy beside the new one, which a launch
            // treats as two builds of the same version; Remove clears it.
            replaced.forEach { previous ->
                previous.directory.walkBottomUp().forEach { it.setWritable(true, true) }
                previous.directory.deleteRecursively()
                BundleStamps(context).forget(previous.directory)
            }
            // Every payload byte was hashed against the signed manifest on its
            // way in; from now on a launch checks that none of it has been
            // touched since (see InstalledBundleVerifier). The stamps are only
            // a record of that: without them the first launch hashes once more.
            runCatching {
                BundleStamps(context).write(destination, InstalledBundleVerifier.stampAll(destination, manifest.files))
            }
            return PluginRegistry.readRecord(destination).also { installed ->
                PluginTrustStore(context).carryApproval(existing, installed)
                EngineCores.reportChanged(context)
            }
        } catch (error: Throwable) {
            forceDeleteRecursively(staging)
            throw error
        }
    }

    /**
     * Refuses [carried] when this device runs none of it. Split out, with
     * the device's own ABIs a parameter, so the refusal can be exercised
     * in a plain JVM test the way [InstalledBundleVerifier.checkPayload]
     * is. A bundle with no native code ([carried] empty) is
     * architecture-agnostic and runs everywhere.
     */
    internal fun requireRunnableAbis(bundleId: String, carried: List<String>, deviceAbis: List<String>) {
        require(carried.isEmpty() || carried.any { it in deviceAbis }) {
            "Bundle $bundleId ships native libraries for ${carried.joinToString()} only, " +
                "and this device runs ${deviceAbis.joinToString()}"
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

    /**
     * Reads the manifest and its signatures, has [verify] decide the signer
     * before a single payload byte is written, then extracts the payload
     * against the signed manifest. The archive is the manifest, then
     * `enginehost-bundle.sig` (legacy), `enginehost-bundle.jws` (v1) or
     * both in that order, then the payload.
     */
    internal fun extractVerified(
        archive: File,
        staging: File,
        verify: (EngineBundleManifest, BundleSignatures) -> BundleSigner,
    ): ExtractedBundle {
        var manifest: EngineBundleManifest? = null
        var legacy: ByteArray? = null
        var jws: String? = null
        var signatures: BundleSignatures? = null
        var signer: BundleSigner? = null
        var expected: Map<String, BundleFileRecord> = emptyMap()
        var orderedPaths: List<String> = emptyList()
        val seen = linkedSetOf<String>()
        val payloadDigest = MessageDigest.getInstance("SHA-256")
        var fileIndex = 0

        /** The signatures are all in: the signer decides before a single payload byte is written. */
        fun signaturesDone() {
            if (signer != null) return
            val declared = requireNotNull(manifest) { "Bundle is empty" }
            require(legacy != null || jws != null) { "Bundle signature is missing" }
            val read = BundleSignatures(jws, legacy)
            signatures = read
            signer = verify(declared, read)
            expected = declared.files.associateBy { it.path }
            orderedPaths = declared.files.map { it.path }
        }

        // BundleTar (vendor/droidtop-shared, :trust) refuses an unsafe path, a link or special file, a repeated path and
        // an xz stream that asks for too much memory. The signed manifest bounds the size and count of the files, so no
        // cap of its own is set here.
        val limits = BundleTar.Limits(maxEntryBytes = Long.MAX_VALUE, maxEntries = Int.MAX_VALUE, xzMemoryKiB = XZ_MEMORY_LIMIT_KIB)
        BundleTar.read(archive, limits, onDirectory = { directory ->
            signaturesDone()
            require(orderedPaths.any { it.startsWith("$directory/") }) { "Unsigned tar directory: $directory" }
        }) { entry ->
            if (manifest == null) {
                // The archive is the manifest, then enginehost-bundle.sig (legacy), enginehost-bundle.jws (v1) or both in that
                // order, then the payload.
                requireHeader(entry, EnginePluginContract.ENGINE_BUNDLE_MANIFEST, MAX_MANIFEST_BYTES)
                manifest = EngineBundleManifestReader.parse(entry.readBytes(MAX_MANIFEST_BYTES))
                return@read
            }
            if (signer == null) {
                if (entry.path == EnginePluginContract.ENGINE_BUNDLE_SIGNATURE && legacy == null && jws == null) {
                    requireHeader(entry, EnginePluginContract.ENGINE_BUNDLE_SIGNATURE, MAX_SIGNATURE_BYTES)
                    legacy = Base64.getMimeDecoder().decode(entry.readBytes(MAX_SIGNATURE_BYTES).toString(Charsets.US_ASCII).trim())
                    return@read
                }
                if (entry.path == ENGINE_BUNDLE_JWS && jws == null) {
                    requireHeader(entry, ENGINE_BUNDLE_JWS, MAX_JWS_BYTES)
                    jws = entry.readBytes(MAX_JWS_BYTES).toString(Charsets.US_ASCII).trim()
                    return@read
                }
                signaturesDone()
            }
            val path = entry.path
            val record = requireNotNull(expected[path]) { "Unsigned tar entry: $path" }
            require(seen.add(path)) { "Duplicate tar entry: $path" }
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
                copyExact(entry.input, sink, record.size, fileDigest, payloadDigest, path)
            }
            require(fileDigest.digest().hex() == record.sha256) { "Digest mismatch for $path" }
            applySignedFileMode(output, record.mode)
        }
        // A bundle whose archive ends before any payload entry still has its signature decided here.
        signaturesDone()
        val declared = requireNotNull(manifest) { "Bundle is empty" }
        require(seen == expected.keys) { "Bundle payload is missing signed files" }
        require(payloadDigest.digest().hex() == declared.payloadSha256) { "Bundle payload digest mismatch" }
        return ExtractedBundle(declared, requireNotNull(signatures), requireNotNull(signer))
    }

    private fun requireHeader(entry: BundleTar.Entry, name: String, maxSize: Long) {
        require(entry.path == name && entry.size in 1..maxSize) { "Invalid $name header" }
    }
}

class ExtractedBundle(val manifest: EngineBundleManifest, val signatures: BundleSignatures, val signer: BundleSigner)

/** The v1 signature's place in a bundle archive, after the manifest (and the legacy signature, when there is one). */
const val ENGINE_BUNDLE_JWS = "enginehost-bundle.jws"

/**
 * What every launch checks before a bundle's code is loaded.
 *
 * The signed metadata is re-checked in full each time: the manifest parses,
 * its signature verifies, the signer is still pinned for its origin, and the
 * install record agrees with it. That is one signature and costs nothing.
 *
 * The payload is not re-hashed each time. Every byte of it was hashed
 * against the signed manifest when it was installed, and a bundle is
 * hundreds of MB (Godot, KiriKiri, Ren'Py), so hashing it again was seconds
 * of work on the runtime's main thread before every first frame. Instead the
 * installer records each file's inode, size and change time ([FileStamp])
 * once its bytes are proven, and a launch compares them. The kernel sets a
 * file's change time on every write, truncate, chmod or rename-over and no
 * unprivileged process can set it back, and a replaced file has a new inode,
 * so a file whose stamp still matches holds the bytes that were hashed. A
 * file whose stamp differs is hashed again against its signed digest, and
 * the launch is refused if it no longer matches; if it does, its new stamp
 * is kept. A payload file that is missing or has the wrong size, and any
 * file in the bundle the manifest does not sign (a library dropped into
 * `lib/<abi>` would be found by the loader), refuse the launch outright.
 *
 * What this does not stop is the thing the full re-hash did not stop
 * either: code running as Enginehost's own user, which can rewrite the
 * stamps as easily as it could have rewritten the trust store.
 */
object InstalledBundleVerifier {
    fun verify(context: Context, installed: InstalledPlugin): EngineBundleManifest {
        val directory = installed.directory.canonicalFile
        require(directory.parentFile == PluginRegistry.root(context).canonicalFile) { "Bundle escaped the registry" }
        val manifest = EngineBundleManifestReader.parse(
            File(directory, PluginRegistry.SIGNED_MANIFEST).readBytes().also {
                require(it.size <= MAX_MANIFEST_BYTES) { "Installed manifest is too large" }
            },
        )
        val legacy = File(directory, PluginRegistry.SIGNED_SIGNATURE).takeIf(File::isFile)?.let {
            Base64.getMimeDecoder().decode(it.readText(Charsets.US_ASCII).trim())
        }
        val jws = File(directory, PluginRegistry.SIGNED_JWS).takeIf(File::isFile)?.readText(Charsets.US_ASCII)?.trim()
        // At launch: an installed plugin is stopped by revocation (never by
        // expiry, at any moment), a key the root has since replaced still vouches
        // for what was installed under it, and a legacy signature still
        // serves until the bundle is updated (BundleVerifier).
        val signer = BundleTrust(context).verify(manifest, BundleSignatures(jws, legacy), VerificationMoment.LAUNCH)
        require(manifest.bundleId == installed.bundleId && manifest.entrypoint == installed.entrypointClass) {
            "Installed bundle record does not match its signed manifest"
        }
        require(installed.signerFingerprints == setOf(signer.keySha256)) {
            "Installed bundle record does not match its signer"
        }
        val stamps = BundleStamps(context)
        val known = stamps.read(directory)
        val current = checkPayload(directory, manifest.files, known, ::stampOf)
        if (current != known) runCatching { stamps.write(directory, current) }
        return manifest
    }

    /**
     * Checks [directory] against the signed [files], hashing only the files
     * whose stamp is not the one in [known], and returns every file's stamp
     * as it is now. Split out, with [stamp] as a parameter, so the decision
     * can be exercised in a plain JVM test.
     */
    internal fun checkPayload(
        directory: File,
        files: List<BundleFileRecord>,
        known: Map<String, FileStamp>,
        stamp: (File) -> FileStamp,
    ): Map<String, FileStamp> {
        val signed = files.mapTo(hashSetOf()) { it.path }
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            require(path in signed || path in HOST_RECORDS) { "Installed bundle has a file it does not sign: $path" }
        }
        return files.associate { record ->
            val file = safeChild(directory, record.path)
            require(file.isFile && file.length() == record.size) { "Installed bundle payload changed: ${record.path}" }
            // Stamped before it is read: a write that lands while the file is
            // being hashed changes the stamp, and the next launch hashes again.
            val now = stamp(file)
            if (known[record.path] != now) {
                require(sha256(file) == record.sha256) { "Installed bundle payload changed: ${record.path}" }
            }
            record.path to now
        }
    }

    /** The stamps of a bundle whose every byte has just been proven, as the installer has. */
    internal fun stampAll(directory: File, files: List<BundleFileRecord>): Map<String, FileStamp> =
        files.associate { it.path to stampOf(safeChild(directory, it.path)) }

    private val HOST_RECORDS = setOf(
        PluginRegistry.INSTALL_RECORD,
        PluginRegistry.SIGNED_MANIFEST,
        PluginRegistry.SIGNED_SIGNATURE,
        PluginRegistry.SIGNED_JWS,
    )
}

/** A payload file's identity on disk: what changes when anything writes to it or replaces it. */
internal data class FileStamp(val size: Long, val inode: Long, val changedNs: Long, val modifiedNs: Long)

internal fun stampOf(file: File): FileStamp {
    val stat = Os.stat(file.path)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        FileStamp(
            stat.st_size, stat.st_ino,
            stat.st_ctim.tv_sec * 1_000_000_000 + stat.st_ctim.tv_nsec,
            stat.st_mtim.tv_sec * 1_000_000_000 + stat.st_mtim.tv_nsec,
        )
    } else {
        FileStamp(stat.st_size, stat.st_ino, stat.st_ctime * 1_000_000_000, stat.st_mtime * 1_000_000_000)
    }
}

/**
 * The stamps each installed bundle's payload had when its bytes were last
 * proven, one file per bundle directory. Kept out of the bundle directory,
 * which is read-only once installed, and out of backups: an inode means
 * nothing on another device, and a missing record only costs one full hash.
 * Written by the main process at install and by `:runtime` after a re-hash,
 * so each write replaces the file whole.
 */
internal class BundleStamps(context: Context) {
    private val directory = File(context.noBackupFilesDir, "engine-bundle-stamps-v1")

    fun read(bundle: File): Map<String, FileStamp> = runCatching {
        val json = JSONObject(file(bundle).readText())
        json.keys().asSequence().associateWith { path ->
            json.getJSONArray(path).let { FileStamp(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3)) }
        }
    }.getOrDefault(emptyMap())

    fun write(bundle: File, stamps: Map<String, FileStamp>) {
        val json = JSONObject()
        stamps.forEach { (path, stamp) ->
            json.put(path, JSONArray().put(stamp.size).put(stamp.inode).put(stamp.changedNs).put(stamp.modifiedNs))
        }
        directory.mkdirs()
        val target = file(bundle)
        val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(target)) temporary.delete()
    }

    fun forget(bundle: File) {
        file(bundle).delete()
    }

    private fun file(bundle: File) = File(directory, "${bundle.name}.json")
}

private fun copyExact(
    input: java.io.InputStream,
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

internal fun validateBundlePath(path: String): String =
    try {
        BundleTar.validatePath(path)
    } catch (e: BundleTarException) {
        throw IllegalArgumentException(e.message, e)
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
    .put("loadsComponents", JSONArray(loadableComponents.sorted()))

private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
private const val MAX_SIGNATURE_BYTES = 16L * 1024
/** A v1 signature: the JWS header with up to three DER certificates, and the signature. */
private const val MAX_JWS_BYTES = 64L * 1024
private const val MAX_UNPACKED_BYTES = 8L * 1024 * 1024 * 1024
private const val XZ_MEMORY_LIMIT_KIB = 512 * 1024
