package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.io.File

data class PluginInfo(
    val engine: String,
    val pluginVersion: Version,
    val capabilities: List<EngineCapability>,
    /** Every engine this bundle runs; [engine] is the first. A browser runtime serves several. */
    val engines: List<String> = emptyList(),
) {
    fun runs(requested: String): Boolean = requested == engine || requested in engines

    /** The engine a capability serves: its own when it names one, else the bundle's. */
    fun engineOf(capability: EngineCapability): String = capability.engine ?: engine
}

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
    /**
     * Whether this bundle's own code may run under an isolated-UID runtime
     * (docs/engine-sandbox.md "Layer 2"). Declared by the bundle itself, not
     * decided by the host: a plugin only sets it once its native code has the
     * broker seam and its Java entry point implements EngineStepDriven.
     * runtimeTransport must still be RUNTIME_TRANSPORT_PLUGIN -- an
     * activity-transport plugin cannot be isolated (see the audit in
     * docs/engine-sandbox.md for why).
     */
    val isolatable: Boolean = false,
    /**
     * Whether the engine writes into the game folder itself (saves or
     * settings beside the game, as the original does on its desktop
     * platform). Only an isolated launch consults it: the game folder's
     * broker is read-only otherwise (docs/engine-sandbox.md "Saves beside
     * the game").
     */
    val writesGameFolder: Boolean = false,
    /**
     * The game sub-folders this engine writes, relative to the game folder ("game/saves"):
     * under the lockdown only these are writable, the rest of the game folder stays read-only
     * (owner 2026-10-23, docs/engine-sandbox.md "Ren'Py under lockdown"). [writesGameFolder]
     * makes the whole folder writable instead.
     */
    val gameWrites: List<String> = emptyList(),
    /**
     * Whether this bundle's code runs correctly under the process lockdown
     * (the sandbox library, without Android isolation), on either transport.
     * Declared by the bundle once a rig run shows it does; a launch of it is
     * then always locked down (docs/engine-sandbox.md "The lockdown is how a
     * core that cannot be isolated is sandboxed", owner 2026-10-17).
     */
    val lockdown: Boolean = false,
    /** Set for a runtime-component bundle, which no game runs directly ([ComponentSelection]). */
    val component: ComponentInfo? = null,
    /** The bundle's own licence, shown on the trust screen before approval. */
    val license: BundleLicense? = null,
    /**
     * Who signed it, as verified at install ([BundleVerifier]); every launch
     * verifies it again and refuses a record that disagrees. Null for a
     * bundle installed before signers were recorded: its legacy key is in
     * [signerFingerprints] and trust is decided from that, as it always was.
     */
    val signer: BundleSigner? = null,
) {
    /** Compatibility alias while callers migrate from package terminology. */
    val packageName: String get() = bundleId
    val signerIdentity: String = signerFingerprints.sorted().joinToString("+")

    /**
     * Installed with only the legacy signature: a v0 signer, or a record from
     * before signers were recorded, which predates format v1. Such a core no
     * longer starts once the root is pinned; it is removed and installed again
     * ([BundleVerifier.LEGACY_INSTALLED]).
     */
    val legacySigned: Boolean get() = signer == null || signer.format == 0

    /** Isolated under its own UID, or locked down by the sandbox library: either is a sandbox (owner, 2026-10-17). */
    val sandboxed: Boolean get() = isolatable || lockdown
}

data class ResolvedPlugin(
    val plugin: InstalledPlugin,
    val capability: EngineCapability,
    /** The separately installed component bundles this launch loads ([ComponentSelection.select]). */
    val components: List<InstalledPlugin> = emptyList(),
) {
    /** Everything the launch loads beside the engine: what the capability carries, then the component bundles. */
    val runtimeComponents: Map<String, Version>
        get() = capability.runtimeComponents + components.mapNotNull { it.component }.associate { it.name to it.version }
}

/** A detected engine line and the installed alternatives that can run it. */
data class PluginLineSelection(
    val detectedLine: VersionSeries?,
    val selectedLine: VersionSeries?,
    val alternatives: List<VersionSeries>,
    val reason: String,
)

/**
 * Decide whether detection names an installed line. Missing or malformed
 * detection deliberately has no automatic selection: a nearby line is an
 * option to show the person, not a safe compatibility assumption.
 */
object PluginLineSelector {
    fun select(detectedVersion: String?, installedLines: Collection<VersionSeries>): PluginLineSelection {
        val installed = installedLines.distinct().sortedWith(compareBy { Version.parse(it.toString()) })
        val detected = detectedVersion?.let { raw ->
            runCatching { VersionSeries.parse(raw) }.getOrNull()?.takeIf { it.parts.size >= 2 }
                ?.let { VersionSeries(it.parts.take(2)) }
        }
        if (detected == null) {
            return PluginLineSelection(null, null, installed, "Detection is missing or ambiguous")
        }
        if (detected in installed) {
            return PluginLineSelection(detected, detected, emptyList(), "Detected game version")
        }
        val target = Version.parse(detected.toString())
        val nearest = installed.sortedWith(
            compareBy<VersionSeries> { Version.parse(it.toString()).distanceTo(target) }
                .thenBy { it.parts.joinToString(".") },
        )
        return PluginLineSelection(detected, null, nearest, "Detected line is not installed")
    }
}

object PluginResolver {
    /**
     * [trustOf] decides whether trust is even considered: callers with no
     * notion of trust (existing JVM tests, and anywhere the caller already
     * filtered its candidate list) get today's behaviour by leaving it at
     * the default, which treats every candidate as approved. A real caller
     * ([PluginRegistry.resolve]) passes [PluginTrustStore.state] so a
     * DENIED bundle is dropped before capability matching even starts --
     * it never resolves, however specific or however new its version --
     * and, among what is left, an APPROVED capability always outranks a
     * PENDING one of the same or even better fit. Before this, trust was
     * checked only after the fact, on whichever bundle resolution already
     * picked, so a pending or denied bundle could outrank an approved one
     * on version alone and be the one to detour to the trust screen.
     */
    fun resolve(
        plugins: List<InstalledPlugin>,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
        trustOf: (InstalledPlugin) -> PluginTrustState = { PluginTrustState.APPROVED },
        components: List<InstalledPlugin> = emptyList(),
    ): ResolvedPlugin? =
        ranked(
            currentBuilds(plugins, pluginVersionAllowlist, trustOf),
            engine, engineContext, engineVersion, runtimeRequirements, pluginVersionAllowlist, trustOf, components,
        ).firstOrNull()

    /**
     * Of each line's installed builds, the one a launch may use: the newest the game's
     * `pluginVersion` allowlist accepts and the person has not denied, an approved build before
     * one still waiting for approval (an update keeps the approved build playing until it is
     * approved). Several builds of a line coexist, and the allowlist is how an older one is chosen
     * (SPEC.md "Several builds of a line, side by side"). An older build never runs just because
     * the newest cannot serve this game. Before, Goodbye Eternity ran `godot.v45.v2` 1.1.4,
     * built before the pack-read fix, because 1.2.2 of that line needed a Spine component not
     * yet approved (Droidtop/tracker#604). Component bundles pass through unchanged.
     */
    internal fun currentBuilds(
        plugins: List<InstalledPlugin>,
        pluginVersionAllowlist: VersionConstraint?,
        trustOf: (InstalledPlugin) -> PluginTrustState,
    ): List<InstalledPlugin> {
        val (components, engines) = plugins.partition { it.component != null }
        val order = compareBy<InstalledPlugin>({ trustOf(it) == PluginTrustState.APPROVED }, { it.info.pluginVersion })
        val current = engines
            .filter { pluginVersionAllowlist == null || pluginVersionAllowlist.matches(it.info.pluginVersion) }
            .filter { trustOf(it) != PluginTrustState.DENIED }
            .groupBy { it.bundleId }
            .values
            // Two copies of one build (a re-signed archive beside its original) are both kept.
            .flatMap { line -> line.maxWith(order).let { top -> line.filter { order.compare(it, top) == 0 } } }
        return current + components
    }

    /**
     * Every installed build that could run the game, best first: the order [resolve] picks from.
     * With no allowlist this is the list the game's Change core offers.
     *
     * [components] are the installed component bundles a capability may
     * load for [runtimeRequirements] it does not carry itself; the caller
     * passes only approved ones, so a launch never loads a component the
     * person has not accepted ([PluginRegistry.approvedComponents]).
     */
    fun ranked(
        plugins: List<InstalledPlugin>,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
        trustOf: (InstalledPlugin) -> PluginTrustState = { PluginTrustState.APPROVED },
        components: List<InstalledPlugin> = emptyList(),
    ): Sequence<ResolvedPlugin> {
        val requestedContext = effectiveEngineContext(
            engineContext,
            plugins.filter { it.component == null }.flatMap { p -> p.info.capabilities.filter { p.info.engineOf(it) == engine }.map { it.engineContext } },
        )
        return plugins.asSequence()
            .filter { it.component == null }
            .filter { it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION }
            .filter { it.info.runs(engine) }
            .filter { pluginVersionAllowlist == null || pluginVersionAllowlist.matches(it.info.pluginVersion) }
            .filter { trustOf(it) != PluginTrustState.DENIED }
            .flatMap { plugin ->
                plugin.info.capabilities.asSequence()
                    .filter {
                        plugin.info.engineOf(it) == engine && it.engineContext == requestedContext &&
                            it.supports(engineVersion)
                    }
                    .mapNotNull { capability ->
                        ComponentSelection.select(engine, capability, runtimeRequirements, components)
                            ?.let { ResolvedPlugin(plugin, capability, it) }
                    }
            }
            .sortedWith(
                compareByDescending<ResolvedPlugin> { trustOf(it.plugin) == PluginTrustState.APPROVED }
                    .thenByDescending { it.capability.runtimeVersion == engineVersion }
                    .thenBy { it.capability.specificityFor(engineVersion) }
                    .thenBy { it.capability.unrequestedComponents(runtimeRequirements) }
                    .thenByDescending { it.plugin.info.pluginVersion }
                    .thenBy { it.plugin.bundleId }
                    // Two copies of one build (a re-signed archive beside the
                    // one it replaces, left by an interrupted install) still
                    // rank the same way every time, so the planner and the
                    // runtime agree on which one loads.
                    .thenBy { it.plugin.archiveSha256 }
                    .thenBy { it.capability.id },
            )
    }
}

object PluginRegistry {
    const val INSTALL_RECORD = ".enginehost-installed.json"
    const val SIGNED_MANIFEST = ".enginehost-bundle.json"
    const val SIGNED_SIGNATURE = ".enginehost-bundle.sig"
    const val SIGNED_JWS = ".enginehost-bundle.jws"

    /**
     * The bundle registry, in this app's private storage. Owner-only: the
     * isolated runtime receives descriptors, never a path into it.
     */
    fun root(context: Context): File {
        val root = File(context.filesDir, "engine-bundles-v1")
        root.mkdirs()
        return root
    }

    /** Every installed engine bundle: what games run on. Component bundles are [components]. */
    fun discover(context: Context): List<InstalledPlugin> = discoverAll(context).filter { it.component == null }

    /** Every installed bundle, engines and runtime components alike: what Plugins lists, updates and removes. */
    fun discoverAll(context: Context): List<InstalledPlugin> = discoverIn(root(context))

    /** Every installed runtime-component bundle (docs/engine-bundle-format.md, "Runtime components"). */
    fun components(context: Context): List<InstalledPlugin> = discoverAll(context).filter { it.component != null }

    /** The component bundles a launch may load: installed and approved by the person, licence accepted. */
    fun approvedComponents(context: Context): List<InstalledPlugin> {
        val trust = PluginTrustStore(context)
        return components(context).filter { trust.state(it) == PluginTrustState.APPROVED }
    }

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
        val component = if (ComponentManifestReader.kind(json) == BUNDLE_KIND_COMPONENT) {
            ComponentManifestReader.component(json.getJSONObject("component"))
        } else null
        val capabilities = if (component != null) emptyList() else PluginCapabilitiesReader.parse(
            JSONObject().put("schemaVersion", 1).put("capabilities", json.getJSONArray("capabilities")).toString(),
        )
        return InstalledPlugin(
            PluginInfo(
                json.requiredString("engine"),
                Version.parsePlugin(json.requiredString("pluginVersion")),
                capabilities,
                engines = json.optJSONArray("engines")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
            ),
            bundleId,
            if (component != null) json.optString("entrypoint") else json.requiredString("entrypoint"),
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
            json.optBoolean("isolatable", false),
            writesGameFolder = json.optBoolean("writesGameFolder", false),
            gameWrites = GameWrites.parse(json.optJSONArray("gameWrites")),
            lockdown = json.optBoolean("lockdown", false),
            component = component,
            license = ComponentManifestReader.license(json.optJSONObject("license")),
            signer = json.optJSONObject("signer")?.let(BundleSigner::fromJson),
        )
    }

    /**
     * Removes one installed build: [plugin]'s own directory. Several builds of
     * a line can be installed side by side, and each is removed on its own;
     * the others, and the person's decisions about them, stay.
     */
    fun uninstall(context: Context, plugin: InstalledPlugin): Boolean {
        val directory = plugin.directory.canonicalFile
        // Only a directory this registry lists, never a path handed in from elsewhere.
        if (discoverAll(context).none { it.directory.canonicalFile == directory }) return false
        directory.walkBottomUp().forEach { it.setWritable(true, true) }
        return directory.deleteRecursively().also { BundleStamps(context).forget(directory) }
    }

    /** Every installed build that could run [config], best first, one entry per bundle build. */
    fun compatible(context: Context, config: EngineConfig): List<InstalledPlugin> {
        val trustStore = PluginTrustStore(context)
        return PluginResolver.ranked(
            discover(context), config.engine, config.engineContext, config.engineVersion, config.runtimeRequirements, null,
            trustOf = trustStore::state, components = approvedComponents(context),
        ).map { it.plugin }.distinctBy { it.bundleId to it.info.pluginVersion }.toList()
    }

    /**
     * When no launch resolves: the component requirements an installed
     * engine bundle could meet once the right component bundle is installed
     * and approved, so the launch can offer that component (with its
     * licence) instead of another engine. Empty when installing a component
     * would not help.
     */
    fun missingComponents(
        context: Context,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
    ): Map<String, Version> {
        if (runtimeRequirements.isEmpty()) return emptyMap()
        val trustStore = PluginTrustStore(context)
        val installed = discover(context)
        val requestedContext = effectiveEngineContext(
            engineContext,
            installed.filter { it.component == null }.flatMap { p -> p.info.capabilities.filter { p.info.engineOf(it) == engine }.map { it.engineContext } },
        )
        val approved = approvedComponents(context)
        return installed.asSequence()
            .filter { it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION && it.info.runs(engine) }
            .filter { pluginVersionAllowlist == null || pluginVersionAllowlist.matches(it.info.pluginVersion) }
            .filter { trustStore.state(it) != PluginTrustState.DENIED }
            .flatMap { plugin ->
                plugin.info.capabilities.asSequence().filter {
                    plugin.info.engineOf(it) == engine && it.engineContext == requestedContext && it.supports(engineVersion)
                }
            }
            .map { ComponentSelection.missing(engine, it, runtimeRequirements, approved) }
            .filter { it.isNotEmpty() }
            .minByOrNull { it.size }
            .orEmpty()
    }

    fun resolve(
        context: Context,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
    ): ResolvedPlugin? {
        val trustStore = PluginTrustStore(context)
        val stale = staleTest(context)
        return PluginResolver.resolve(
            discover(context).filterNot(stale), engine, engineContext, engineVersion, runtimeRequirements, pluginVersionAllowlist,
            trustOf = trustStore::state, components = approvedComponents(context),
        )
    }

    /**
     * The installed build that would run the game if it could start, when
     * [resolve] found none because every such build is stale: signed only
     * the legacy way, which no launch accepts while the root is pinned
     * ([BundleVerifier.LEGACY_INSTALLED]). The launch then says the engine
     * needs updating instead of the runtime failing on it (rig, 2026-10-22:
     * Ren'Py 7.5 and 8.4, Buriko and CatSystem2 stopped Enginehost with
     * Android's "keeps stopping" dialog).
     */
    fun resolveStale(
        context: Context,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
    ): ResolvedPlugin? {
        val trustStore = PluginTrustStore(context)
        return PluginResolver.resolve(
            discover(context).filter(staleTest(context)), engine, engineContext, engineVersion, runtimeRequirements,
            pluginVersionAllowlist, trustOf = trustStore::state, components = approvedComponents(context),
        )
    }

    /** A build that cannot start: signed only the legacy way while the root is pinned. */
    private fun staleTest(context: Context): (InstalledPlugin) -> Boolean {
        val rootPinned = EnginehostRoot.get(context) != null
        return { rootPinned && it.legacySigned }
    }
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
