package dev.enginehost

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources

/** The intent-filter action every real plugin app declares on its run activity. */
const val ACTION_RUN_PLUGIN = "dev.enginehost.plugin.RUN"

private const val META_ENGINE = "dev.enginehost.plugin.engine"
private const val META_ENGINE_VERSION = "dev.enginehost.plugin.engineVersion"
private const val META_PLUGIN_VERSION = "dev.enginehost.plugin.pluginVersion"
private const val META_CAPABILITIES = "dev.enginehost.plugin.capabilities"

data class PluginInfo(
    val engine: String,
    val pluginVersion: Version,
    val capabilities: List<EngineCapability>,
)

data class InstalledPlugin(
    val info: PluginInfo,
    val packageName: String,
    val activityName: String,
)

data class ResolvedPlugin(
    val plugin: InstalledPlugin,
    val capability: EngineCapability,
)

object PluginResolver {
    /**
     * Compatibility is always plugin-declared. Ranking is deterministic:
     * exact bundled runtime, then narrowest support declaration, newest
     * allowlisted plugin build, then stable package/capability identifiers.
     */
    fun resolve(
        plugins: List<InstalledPlugin>,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        pluginVersionAllowlist: VersionConstraint?,
    ): ResolvedPlugin? {
        val requestedContext = engineContext ?: DEFAULT_ENGINE_CONTEXT
        return plugins.asSequence()
            .filter { it.info.engine == engine }
            .filter { pluginVersionAllowlist == null || pluginVersionAllowlist.matches(it.info.pluginVersion) }
            .flatMap { plugin ->
                plugin.info.capabilities.asSequence()
                    .filter { it.engineContext == requestedContext && it.supports(engineVersion) }
                    .map { ResolvedPlugin(plugin, it) }
            }
            .sortedWith(
                compareByDescending<ResolvedPlugin> { it.capability.runtimeVersion == engineVersion }
                    .thenBy { it.capability.specificityFor(engineVersion) }
                    .thenByDescending { it.plugin.info.pluginVersion }
                    .thenBy { it.plugin.packageName }
                    .thenBy { it.capability.id },
            )
            .firstOrNull()
    }
}

object PluginRegistry {
    fun discover(context: Context): List<InstalledPlugin> {
        val packageManager = context.packageManager
        return packageManager.queryIntentActivities(
            Intent(ACTION_RUN_PLUGIN),
            PackageManager.GET_META_DATA,
        ).mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val metaData = activityInfo.metaData ?: return@mapNotNull null
            val engine = metaData.getString(META_ENGINE)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pluginVersionRaw = metaData.getString(META_PLUGIN_VERSION) ?: return@mapNotNull null
            try {
                // Android resource IDs are package-local. Opening a plugin's
                // @raw resource through enginehost's Resources can resolve an
                // unrelated host resource or throw NotFoundException.
                val pluginResources = packageManager.getResourcesForApplication(activityInfo.applicationInfo)
                val capabilities = readCapabilities(pluginResources, metaData.get(META_CAPABILITIES))
                    ?: legacyCapability(metaData.getString(META_ENGINE_VERSION))
                    ?: return@mapNotNull null
                InstalledPlugin(
                    info = PluginInfo(engine, Version.parse(pluginVersionRaw), capabilities),
                    packageName = activityInfo.packageName,
                    activityName = activityInfo.name,
                )
            } catch (_: Exception) {
                // One malformed third-party manifest/resource must not
                // prevent discovery of every other installed plugin.
                null
            }
        }
    }

    private fun readCapabilities(resources: Resources, value: Any?): List<EngineCapability>? = when (value) {
        is Int -> resources.openRawResource(value).bufferedReader().use {
            PluginCapabilitiesReader.parse(it.readText())
        }
        is String -> PluginCapabilitiesReader.parse(value)
        else -> null
    }

    private fun legacyCapability(engineVersionRaw: String?): List<EngineCapability>? =
        engineVersionRaw?.let { Version.parse(it) }?.let { version ->
            listOf(
                EngineCapability(
                    id = "legacy-$version",
                    engineContext = DEFAULT_ENGINE_CONTEXT,
                    runtimeVersion = version,
                    supportedVersions = emptySet(),
                    supportedRanges = emptyList(),
                ),
            )
        }

    fun resolve(
        context: Context,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        pluginVersionAllowlist: VersionConstraint?,
    ): ResolvedPlugin? = PluginResolver.resolve(
        discover(context), engine, engineContext, engineVersion, pluginVersionAllowlist,
    )
}
