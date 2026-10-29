package dev.enginehost

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enginehost's engine cores as its application interface presents them to droidtop
 * (Droidtop/tracker#752, point `engines.cores@1`): one entry per core line, installed or
 * installable, with what it runs and which of the shared engines database's rows it serves, so
 * droidtop can make a game's own engine its runner instead of falling back to Wine. Read from the
 * installed bundles and the cached catalog only: answering never fetches. When the list changes
 * (a core installed, removed, a catalog stored), [reportChanged] tells droidtop, which asks again.
 */
object EngineCores {
    private const val TAG = "enginehost-cores"

    /** One core line's newest build on one side: installed, or in the catalog. */
    data class Build(val bundleId: String, val info: PluginInfo)

    /** `list_cores`'s answer. Reads the bundles, the catalog cache and the registry: not for the main thread. */
    fun list(context: Context): JSONObject {
        val installed = PluginRegistry.discover(context).filter { it.component == null }.map { Build(it.bundleId, it.info) }
        val catalog = PluginCatalogCache(context)
        val available = catalog.loadAll(PluginOriginStore(context).all())
            .filter { it.manifest.component == null }
            .map { Build(it.bundleId, it.info) }
        return JSONObject()
            .put("cores", entries(installed, available, EngineRegistryStore.rows(context)))
            .put("catalogCheckedAtMs", maxOf(catalog.storedAtMs(), PluginCatalogIndex.fetchedAtMs(context)))
    }

    /** The entries, newest build per line on each side. Pure, for tests. */
    internal fun entries(installed: List<Build>, available: List<Build>, rows: List<EngineRow>): JSONArray {
        val newestInstalled = newest(installed)
        val newestAvailable = newest(available)
        val result = JSONArray()
        (newestInstalled.keys + newestAvailable.keys).sorted().forEach { id ->
            val mine = newestInstalled[id]
            val offered = newestAvailable[id]
            val info = (mine ?: offered!!).info
            val engines = info.capabilities.map(info::engineOf).ifEmpty { listOf(info.engine) }.distinct()
            val contexts = info.capabilities.map { it.engineContext }.distinct()
            val entry = JSONObject()
                .put("id", id)
                .put("engine", info.engine)
                .put("label", EngineNames.compatibility(info.engine, info.capabilities).joinToString(", ").ifEmpty { EngineNames.family(info.engine) })
                .put("contexts", JSONArray(contexts))
                .put(
                    "engineVersions",
                    JSONObject()
                        .put("series", JSONArray(info.capabilities.flatMap { c -> c.supportedSeries.map { it.toString() } }.distinct()))
                        .put("any", info.capabilities.any { it.acceptsAnyEngineVersion }),
                )
                .put("rows", JSONArray(rows.filter { row -> row.family in engines && (row.context == null || row.context in contexts) }.map { it.id }))
                .put("installed", mine != null)
            if (engines.size > 1) entry.put("engines", JSONArray(engines))
            mine?.let { entry.put("installedVersion", it.info.pluginVersion.toString()) }
            offered?.let { entry.put("availableVersion", it.info.pluginVersion.toString()) }
            entry.put("updateAvailable", mine != null && offered != null && offered.info.pluginVersion > mine.info.pluginVersion)
            result.put(entry)
        }
        return result
    }

    private fun newest(builds: List<Build>): Map<String, Build> =
        builds.groupBy { it.bundleId }.mapValues { (_, all) -> all.maxBy { it.info.pluginVersion } }

    /**
     * Tells droidtop the list changed (`engines report_changed {}`), off the caller's thread. With
     * no broker, droidtop is not connected and asks for the whole list when it next connects.
     */
    fun reportChanged(context: Context) {
        if (!DroidtopLink.isConnected()) return
        Async.run {
            val reply = DroidtopLink.call("engines", "report_changed", JSONObject())
            if (reply?.optBoolean("ok") != true) Log.i(TAG, "droidtop did not take the core list change: $reply")
        }
    }
}
