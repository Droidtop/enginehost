package dev.enginehost

import android.content.Context
import android.net.ConnectivityManager

/**
 * The automatic update pass and the choices that govern it. Nothing here
 * installs an app; the pass fetches small documents (plugin catalogs, the
 * newest Enginehost build's description, the engine detection rules) so the
 * home screen can say what is available, and installs a plugin update only
 * when the person has turned that on.
 */
class PluginUpdateCheck(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-update-check-v1", Context.MODE_PRIVATE)

    /** How often the pass may run. [OFF] means never without being asked. */
    enum class Frequency(val intervalMs: Long) {
        OFF(Long.MAX_VALUE),
        DAILY(24L * 60 * 60 * 1000),
        WEEKLY(7 * 24L * 60 * 60 * 1000),
        MONTHLY(30 * 24L * 60 * 60 * 1000),
    }

    var frequency: Frequency
        get() = preferences.getString(FREQUENCY, null)?.let { name -> Frequency.entries.firstOrNull { it.name == name } }
            // Before there was a frequency there was one switch; honour what it said.
            ?: if (preferences.getBoolean(LEGACY_CHECK, true)) Frequency.DAILY else Frequency.OFF
        set(value) = preferences.edit().putString(FREQUENCY, value.name).remove(LEGACY_CHECK).apply()

    var installAutomatically: Boolean
        get() = preferences.getBoolean(INSTALL, false)
        set(value) = preferences.edit().putBoolean(INSTALL, value).apply()

    /** The most adventurous release stream to offer; see [PluginStream]. */
    var stream: PluginStream
        get() = preferences.getString(STREAM, null)?.let { name -> PluginStream.entries.firstOrNull { it.name == name } }
            ?: PluginStream.STABLE
        set(value) {
            // A refresh fetches and caches every stream a repository
            // publishes, not only this one; [PluginStream.offeredTo] is
            // applied wherever the cache is read, so changing this is a
            // local choice that needs no re-fetch and loses nothing already
            // held for the other streams.
            preferences.edit().putString(STREAM, value.name).apply()
        }

    /** Skip the pass on metered connections (mobile data, tethering). */
    var unmeteredOnly: Boolean
        get() = preferences.getBoolean(UNMETERED_ONLY, false)
        set(value) = preferences.edit().putBoolean(UNMETERED_ONLY, value).apply()

    /** When the pass last ran, as epoch milliseconds, or null if it never has. */
    val lastAttempt: Long? get() = preferences.getLong(LAST_ATTEMPT, 0L).takeIf { it > 0 }

    /** Updates for installed bundles, computed from the already-cached catalogs alone. */
    fun pending(): List<AvailablePlugin> {
        val installed = PluginRegistry.discover(context)
        val origins = installed.map { it.origin }.filter(String::isNotBlank).distinct()
        val available = PluginCatalogCache(context).loadAll(origins).filter { it.stream.offeredTo(stream) }
        return PluginUpdates.updatesFor(installed, available).values.toList()
    }

    /**
     * Runs the pass if it is enabled, due, and allowed on the current
     * network, then reports the updates still pending (after any automatic
     * installs). When the pass does not run, the cached catalogs answer
     * instead -- reading them still verifies every cached manifest
     * signature, so even that happens off the caller's thread. The callback
     * always arrives on a background thread.
     */
    fun maybeRun(onPending: (List<AvailablePlugin>) -> Unit) {
        val frequency = frequency
        if (frequency == Frequency.OFF) return
        val now = System.currentTimeMillis()
        // Due on the schedule, or because the catalogs this count comes from
        // are older than the Plugins screen accepts: then both screens answer
        // from the same fresh catalogs. Home used to keep a count from the
        // last daily pass ("16 plugin updates") until the Plugins screen ran
        // its own refresh ("22"; rig, dq-ehfix-02).
        val scheduled = now - preferences.getLong(LAST_ATTEMPT, 0L) >= frequency.intervalMs
        Thread {
            val due = scheduled || catalogsStale()
            if (!due || (unmeteredOnly && isMetered())) onPending(pending()) else run(onPending)
        }.start()
    }

    /** Whether any installed plugin's catalog is older than [CATALOG_MAX_AGE_MS]. */
    private fun catalogsStale(): Boolean {
        val cache = PluginCatalogCache(context)
        return PluginRegistry.discover(context).map { it.origin }.filter(String::isNotBlank).distinct()
            .any { cache.isStale(it, CATALOG_MAX_AGE_MS) }
    }

    /** Runs the pass now, regardless of schedule or network. */
    fun run(onPending: (List<AvailablePlugin>) -> Unit) {
        preferences.edit().putLong(LAST_ATTEMPT, System.currentTimeMillis()).apply()
        Thread {
            val installed = PluginRegistry.discover(context)
            val origins = installed.map { it.origin }.filter(String::isNotBlank).distinct()
            val cache = PluginCatalogCache(context)
            // The same refresh the catalog screen's button runs: the plugins
            // index first, the GitHub API with the stored ETag where the index
            // does not reach. Failures are recorded per origin and, here, as
            // silent as they have always been -- the screen is where a reason
            // belongs. Every stream is fetched; only [stream] decides which
            // of what lands is used for updates below.
            CatalogRefresh(context).run(origins)
            // The engine detection rules ride along too: they are the one
            // piece of enginehost that changes faster than the app, and a
            // person with a game that detects wrongly has no way to know a
            // fix was published. Validate-before-replace inside
            // EngineRegistryStore.update means a bad download changes nothing.
            runCatching { EngineRegistryStore.update(context) }
            // The same pass also asks what the newest Enginehost build is --
            // one more small unauthenticated download, remembered so the
            // home screen can say an app update exists. Failure is as silent
            // as a failed catalog fetch.
            runCatching { AppUpdate.fetch(context) }.onSuccess { info ->
                preferences.edit()
                    .putLong(APP_VERSION_CODE, info.versionCode)
                    .putString(APP_VERSION_NAME, info.versionName)
                    .apply()
            }
            val updates = PluginUpdates.updatesFor(
                installed,
                cache.loadAll(origins).filter { it.stream.offeredTo(stream) },
            ).values
            onPending(
                updates.filterNot { update ->
                    installAutomatically && runCatching {
                        val archive = PluginInstaller.fetch(context, update)
                        EngineBundleInstaller.install(context, archive, update.manifest)
                    }.isSuccess
                },
            )
        }.start()
    }

    /** The newest Enginehost build the pass has seen, when newer than what is running. */
    fun newerAppVersionName(): String? {
        val seen = preferences.getLong(APP_VERSION_CODE, 0L)
        if (seen <= AppUpdate.installedVersionCode(context)) return null
        return preferences.getString(APP_VERSION_NAME, null)
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    companion object {
        /** How old a cached catalog may be before Home or the Plugins screen fetches it again. */
        const val CATALOG_MAX_AGE_MS = 10L * 60 * 1000
        private const val LEGACY_CHECK = "checkAutomatically"
        private const val FREQUENCY = "frequency"
        private const val STREAM = "stream"
        private const val INSTALL = "installAutomatically"
        private const val UNMETERED_ONLY = "unmeteredOnly"
        private const val LAST_ATTEMPT = "lastAttemptMs"
        private const val APP_VERSION_CODE = "newestAppVersionCode"
        private const val APP_VERSION_NAME = "newestAppVersionName"
    }
}
