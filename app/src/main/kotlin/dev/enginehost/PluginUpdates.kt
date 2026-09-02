package dev.enginehost

/**
 * The single definition of "this signed bundle is a newer build of that
 * installed one", shared by the installer (which enforces it before
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
     * Replacing a bundle never inherits its approval: the trust store binds
     * a decision to the exact archive digest and signer (see
     * PluginTrustStore), so the replacement is unapproved until the user
     * approves that exact new archive.
     */
    fun isNewerBuildOf(installed: InstalledPlugin, manifest: EngineBundleManifest): Boolean =
        manifest.bundleId == installed.bundleId &&
            manifest.origin == installed.origin &&
            manifest.info.pluginVersion > installed.info.pluginVersion

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
