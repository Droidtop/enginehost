package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.io.File

data class PluginInfo(
    val engine: String,
    val pluginVersion: Version,
    val capabilities: List<EngineCapability>,
)

/** One verified, extracted, co-installable engine bundle. */
data class InstalledPlugin(
    val info: PluginInfo,
    val bundleId: String,
    val entrypointClass: String,
    val origin: String = "",
    val signerFingerprints: Set<String> = emptySet(),
    val directory: File = File("."),
    val archiveSha256: String = "",
    val apiVersion: Int = dev.enginehost.api.EnginePluginContract.API_VERSION,
    val dexFiles: List<String> = listOf("classes.dex"),
    val resourceApks: List<String> = emptyList(),
    val runtimeTransport: String = RUNTIME_TRANSPORT_PLUGIN,
) {
    /** Compatibility alias while callers migrate from package terminology. */
    val packageName: String get() = bundleId
    val signerIdentity: String = signerFingerprints.sorted().joinToString("+")
}

data class ResolvedPlugin(val plugin: InstalledPlugin, val capability: EngineCapability)

object PluginResolver {
    fun resolve(
        plugins: List<InstalledPlugin>,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
    ): ResolvedPlugin? {
        val requestedContext = engineContext ?: DEFAULT_ENGINE_CONTEXT
        return plugins.asSequence()
            .filter { it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION }
            .filter { it.info.engine == engine }
            .filter { pluginVersionAllowlist == null || pluginVersionAllowlist.matches(it.info.pluginVersion) }
            .flatMap { plugin ->
                plugin.info.capabilities.asSequence()
                    .filter {
                        it.engineContext == requestedContext && it.supports(engineVersion) &&
                            it.satisfies(runtimeRequirements)
                    }
                    .map { ResolvedPlugin(plugin, it) }
            }
            .sortedWith(
                compareByDescending<ResolvedPlugin> { it.capability.runtimeVersion == engineVersion }
                    .thenBy { it.capability.specificityFor(engineVersion) }
                    .thenByDescending { it.plugin.info.pluginVersion }
                    .thenBy { it.plugin.bundleId }
                    .thenBy { it.capability.id },
            )
            .firstOrNull()
    }
}

object PluginRegistry {
    const val INSTALL_RECORD = ".enginehost-installed.json"
    const val SIGNED_MANIFEST = ".enginehost-bundle.json"
    const val SIGNED_SIGNATURE = ".enginehost-bundle.sig"

    fun root(context: Context): File = File(context.filesDir, "engine-bundles-v1").apply { mkdirs() }

    fun discover(context: Context): List<InstalledPlugin> = discoverIn(root(context))

    /**
     * Split out from [discover] so the staging-directory filter below --
     * the property this class actually needs to be correct -- can be
     * exercised in a plain JVM test against a temp directory, with no
     * Android Context involved.
     */
    internal fun discoverIn(root: File): List<InstalledPlugin> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        // A STAGING_PREFIX directory is a bundle install still being
        // unpacked (or one that was interrupted mid-unpack and is waiting
        // for EngineBundleInstaller.sweepOrphanedStaging to clean it up).
        // It can, in principle, contain a fully-written install record if
        // the owning process died between writing that record and the
        // final rename -- readRecord() has no other way to tell it apart
        // from a real install, so this name check is the thing standing
        // between that half-finished directory and being loaded as a
        // plugin. Not an incidental detail: the staging prefix is
        // load-bearing.
        .filter { !it.name.startsWith(STAGING_PREFIX) }
        .mapNotNull { directory -> runCatching { readRecord(directory) }.getOrNull() }
        .toList()

    fun readRecord(directory: File): InstalledPlugin {
        val root = directory.canonicalFile
        require(root.parentFile == directory.parentFile?.canonicalFile) { "Bundle directory escaped its registry" }
        val json = JSONObject(File(root, INSTALL_RECORD).readText())
        require(json.getInt("formatVersion") == 1) { "Unsupported installed bundle record" }
        val bundleId = json.requiredString("bundleId")
        require(bundleId.matches(BUNDLE_ID)) { "Invalid bundle ID" }
        val capabilityDocument = JSONObject()
            .put("schemaVersion", 1)
            .put("capabilities", json.getJSONArray("capabilities"))
        return InstalledPlugin(
            PluginInfo(
                json.requiredString("engine"),
                Version.parse(json.requiredString("pluginVersion")),
                PluginCapabilitiesReader.parse(capabilityDocument.toString()),
            ),
            bundleId,
            json.requiredString("entrypoint"),
            normalizeGithubOrigin(json.requiredString("origin")),
            setOf(json.requiredSha256("signingKeySha256")),
            root,
            json.requiredSha256("archiveSha256"),
            json.getInt("apiVersion"),
            json.getJSONArray("dexFiles").let { array ->
                (0 until array.length()).map(array::getString)
            },
            json.optJSONArray("resourceApks")?.let { array ->
                (0 until array.length()).map(array::getString)
            }.orEmpty(),
            json.optString("runtimeTransport", RUNTIME_TRANSPORT_PLUGIN).also {
                require(it == RUNTIME_TRANSPORT_PLUGIN || it == RUNTIME_TRANSPORT_ACTIVITY)
            },
        )
    }

    fun uninstall(context: Context, bundleId: String): Boolean {
        val installed = discover(context).firstOrNull { it.bundleId == bundleId } ?: return false
        installed.directory.walkBottomUp().forEach { it.setWritable(true, true) }
        return installed.directory.deleteRecursively()
    }

    fun resolve(
        context: Context,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
    ): ResolvedPlugin? = PluginResolver.resolve(
        discover(context), engine, engineContext, engineVersion, runtimeRequirements, pluginVersionAllowlist,
    )
}

/** Shared with [EngineBundleInstaller], which is the sole creator of these directories. */
internal const val STAGING_PREFIX = ".staging-"

internal val BUNDLE_ID = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
const val RUNTIME_TRANSPORT_PLUGIN = "plugin-api"
const val RUNTIME_TRANSPORT_ACTIVITY = "android-activity"
internal fun JSONObject.requiredString(name: String): String =
    optString(name).takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Missing $name")
internal fun JSONObject.requiredSha256(name: String): String = requiredString(name).uppercase().also {
    require(it.matches(Regex("[A-F0-9]{64}"))) { "$name must be a SHA-256 digest" }
}
