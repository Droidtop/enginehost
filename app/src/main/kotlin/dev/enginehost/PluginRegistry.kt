package dev.enginehost

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** The intent-filter action every real plugin app declares on its own run activity. */
const val ACTION_RUN_PLUGIN = "dev.enginehost.plugin.RUN"

private const val META_ENGINE = "dev.enginehost.plugin.engine"
private const val META_ENGINE_VERSION = "dev.enginehost.plugin.engineVersion"
private const val META_PLUGIN_VERSION = "dev.enginehost.plugin.pluginVersion"

/**
 * What one installed plugin is: an implementation of a specific `engine`
 * (e.g. "kirikiri2"), at a specific `engineVersion` (the real underlying
 * engine's own version -- different game titles can need different engine
 * versions for real compatibility reasons), built as its own
 * `pluginVersion` (independent of engineVersion: a plugin's own code can
 * regress or fix things across its own revisions without the underlying
 * engine changing at all).
 */
data class PluginInfo(
    val engine: String,
    val engineVersion: Version,
    val pluginVersion: Version,
)

/** A [PluginInfo] plus the real installed component enginehost found it on. */
data class InstalledPlugin(
    val info: PluginInfo,
    val packageName: String,
    val activityName: String,
)

/**
 * Plugins are separate, manually-installed apps -- their own repos, their
 * own release cadence, their own git history -- not code enginehost
 * bundles itself. A plugin declares the [ACTION_RUN_PLUGIN] intent-filter
 * on an exported activity, plus real manifest `<meta-data>` for
 * `dev.enginehost.plugin.engine`/`engineVersion`/`pluginVersion`. This is
 * Android's standard package-discovery mechanism, with version-range
 * resolution on top.
 */
object PluginRegistry {
    fun discover(context: Context): List<InstalledPlugin> {
        val packageManager = context.packageManager
        val resolved = packageManager.queryIntentActivities(
            Intent(ACTION_RUN_PLUGIN),
            PackageManager.GET_META_DATA,
        )
        return resolved.mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val metaData = activityInfo.metaData ?: return@mapNotNull null
            val engine = metaData.getString(META_ENGINE) ?: return@mapNotNull null
            val engineVersion = metaData.getString(META_ENGINE_VERSION) ?: return@mapNotNull null
            val pluginVersion = metaData.getString(META_PLUGIN_VERSION) ?: return@mapNotNull null
            InstalledPlugin(
                info = PluginInfo(engine, Version.parse(engineVersion), Version.parse(pluginVersion)),
                packageName = activityInfo.packageName,
                activityName = activityInfo.name,
            )
        }
    }

    /**
     * 1. Filter to installed plugins for the requested `engine`.
     * 2. If the game specifies a pluginVersion constraint, drop any
     *    candidate whose pluginVersion doesn't satisfy it.
     * 3. Prefer an exact engineVersion match among what's left; otherwise
     *    fall back to the nearest one (see [Version.distanceTo]).
     */
    fun resolve(
        context: Context,
        engine: String,
        engineVersion: Version,
        pluginVersionConstraint: VersionConstraint?,
    ): InstalledPlugin? {
        val candidates = discover(context)
            .filter { it.info.engine == engine }
            .filter { pluginVersionConstraint == null || pluginVersionConstraint.matches(it.info.pluginVersion) }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.info.engineVersion == engineVersion }?.let { return it }
        return candidates.minByOrNull { it.info.engineVersion.distanceTo(engineVersion) }
    }
}
