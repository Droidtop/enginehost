package dev.enginehost

/**
 * The single definition of how a signed bundle relates to the builds
 * installed: the same line, the same build, or a newer one. Shared by the
 * installer (what an install adds or replaces) and the catalog and update
 * check (what is offered as an update).
 */
object PluginUpdates {
    /**
     * Whether [manifest] describes a strictly newer build of [installed].
     *
     * A bundle line is identified by its bundle ID, and an update must come
     * from the repository the installed build came from: a different origin
     * publishing the same ID is not an update, and its signature would not
     * match the key pinned for the installed bundle's origin anyway. Within
     * a line, `pluginVersion` is the wrapper build number -- `runtimeVersion`
     * never changes inside a bundle ID, so it plays no part here.
     *
     * The trust store binds a decision to the exact archive digest and
     * signer, so a replacement is a new archive; it inherits the person's
     * approval only when signed by the same key (PluginTrustStore.carryApproval),
     * and otherwise waits for approval.
     */
    fun isNewerBuildOf(installed: InstalledPlugin, manifest: EngineBundleManifest): Boolean =
        isSameLine(installed, manifest) && manifest.info.pluginVersion > installed.info.pluginVersion

    /** Whether [manifest] belongs to [installed]'s line: the same bundle ID, from the same repository. */
    fun isSameLine(installed: InstalledPlugin, manifest: EngineBundleManifest): Boolean =
        manifest.bundleId == installed.bundleId && manifest.origin == installed.origin

    /**
     * Whether [manifest] is the build [installed] is, possibly under another
     * signature: the same line and the same build number. A stable build
     * re-signed in format v1 is such a build (docs/engine-bundle-format.md
     * "Signing, provisioning and the owner's steps"): same code, new archive.
     */
    fun isSameBuild(installed: InstalledPlugin, manifest: EngineBundleManifest): Boolean =
        isSameLine(installed, manifest) && manifest.info.pluginVersion == installed.info.pluginVersion

    /**
     * The newest installed build of each line. Several builds of one line can
     * be installed side by side (owner, 2026-10-16: "Several builds can be
     * installed"); an update is a build newer than the newest of them.
     */
    fun newestPerLine(installed: List<InstalledPlugin>): List<InstalledPlugin> =
        installed.groupBy { it.bundleId to it.origin }.values.map { builds -> builds.maxBy { it.info.pluginVersion } }

    /** The newest available update for each installed line, keyed by bundle ID. */
    fun updatesFor(
        installed: List<InstalledPlugin>,
        available: List<AvailablePlugin>,
    ): Map<String, AvailablePlugin> = newestPerLine(installed).mapNotNull { plugin ->
        available
            .filter { it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION }
            .filter { isNewerBuildOf(plugin, it.manifest) }
            .maxByOrNull { it.info.pluginVersion }
            ?.let { plugin.bundleId to it }
    }.toMap()
}
