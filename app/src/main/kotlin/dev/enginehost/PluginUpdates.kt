package dev.enginehost

/**
 * The single definition of where a signed bundle stands relative to an
 * installed one, shared by the installer (which enforces it before
 * replacing anything) and the catalog (which uses it to offer updates).
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
     * Replacing a bundle with such an update carries the person's APPROVED
     * decision with it when the new archive is provably the same line under
     * the same key: same origin, and a verified signer identical to the
     * previously-approved build's (PluginTrustStore.carryApprovalFrom;
     * decided 2026-09-27, docs/plugin-catalog.md "Updates"). A different
     * signing key, or a different origin, carries nothing -- the replacement
     * is unapproved and prompts exactly as before.
     */
    fun isNewerBuildOf(installed: InstalledPlugin, manifest: EngineBundleManifest): Boolean =
        manifest.bundleId == installed.bundleId &&
            manifest.origin == installed.origin &&
            manifest.info.pluginVersion > installed.info.pluginVersion

    /**
     * Whether [manifest] is a step back within [installed]'s line: the same
     * bundle ID from the same repository at a strictly lower build number.
     * The installer replaces nothing with it until the person has accepted
     * a downgrade warning naming both builds, and a version equal to the
     * installed one is neither an update nor a downgrade, so it replaces
     * nothing at all.
     */
    fun isDowngradeOf(installed: InstalledPlugin, manifest: EngineBundleManifest): Boolean =
        manifest.bundleId == installed.bundleId &&
            manifest.origin == installed.origin &&
            manifest.info.pluginVersion < installed.info.pluginVersion

    /** The newest available update for each installed bundle, keyed by bundle ID. */
    fun updatesFor(
        installed: List<InstalledPlugin>,
        available: List<AvailablePlugin>,
    ): Map<String, AvailablePlugin> = installed.mapNotNull { plugin ->
        available
            .filter { it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION }
            .filter { isNewerBuildOf(plugin, it.manifest) }
            .maxByOrNull { it.info.pluginVersion }
            ?.let { plugin.bundleId to it }
    }.toMap()
}
