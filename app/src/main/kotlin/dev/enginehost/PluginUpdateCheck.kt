package dev.enginehost

import android.content.Context

/**
 * The at-most-daily plugin update pass.
 *
 * What it does on the network, and deliberately nothing more: it lists the
 * published GitHub releases of exactly those repositories that have a bundle
 * installed from them -- the same unauthenticated request the catalog screen's
 * own Refresh button makes -- and nothing about the device, the game library
 * or the installed bundles is ever sent anywhere. Being offline, or any fetch
 * failing, is silent: the pass just runs again after the next interval.
 *
 * "Install updates automatically" replaces the bundle's bytes only. Execution
 * approval is bound to the exact archive digest and signer (PluginTrustStore),
 * so an automatically installed update still puts the trust prompt in front
 * of the user before it runs anything. Both behaviours have off switches in
 * settings; turning the check off stops all of this feature's network use.
 */
class PluginUpdateCheck(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-update-check-v1", Context.MODE_PRIVATE)

    var checkAutomatically: Boolean
        get() = preferences.getBoolean(CHECK, true)
        set(value) = preferences.edit().putBoolean(CHECK, value).apply()

    var installAutomatically: Boolean
        get() = preferences.getBoolean(INSTALL, false)
        set(value) = preferences.edit().putBoolean(INSTALL, value).apply()

    /** Updates for installed bundles, computed from the already-cached catalogs alone. */
    fun pending(): List<AvailablePlugin> {
        val installed = PluginRegistry.discover(context)
        val origins = installed.map { it.origin }.filter(String::isNotBlank).distinct()
        return PluginUpdates.updatesFor(installed, PluginCatalogCache(context).loadAll(origins)).values.toList()
    }

    /**
     * Runs the pass if it is enabled and due, then reports the updates still
     * pending (after any automatic installs). When the pass is not due, the
     * cached catalogs answer instead -- reading them still verifies every
     * cached manifest signature, so even that happens off the caller's
     * thread. The callback always arrives on a background thread.
     */
    fun maybeRun(onPending: (List<AvailablePlugin>) -> Unit) {
        if (!checkAutomatically) return
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(LAST_ATTEMPT, 0L) < INTERVAL_MS) {
            Thread { onPending(pending()) }.start()
            return
        }
        preferences.edit().putLong(LAST_ATTEMPT, now).apply()
        Thread {
            val installed = PluginRegistry.discover(context)
            val origins = installed.map { it.origin }.filter(String::isNotBlank).distinct()
            val cache = PluginCatalogCache(context)
            origins.forEach { origin ->
                runCatching { cache.save(origin, GithubPluginCatalogClient(context).fetch(origin)) }
            }
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
            runCatching { AppUpdate.fetch() }.onSuccess { info ->
                preferences.edit()
                    .putLong(APP_VERSION_CODE, info.versionCode)
                    .putString(APP_VERSION_NAME, info.versionName)
                    .apply()
            }
            val updates = PluginUpdates.updatesFor(installed, cache.loadAll(origins)).values
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

    /** The newest Enginehost build the daily pass has seen, when newer than what is running. */
    fun newerAppVersionName(): String? {
        val seen = preferences.getLong(APP_VERSION_CODE, 0L)
        if (seen <= AppUpdate.installedVersionCode(context)) return null
        return preferences.getString(APP_VERSION_NAME, null)
    }

    companion object {
        private const val CHECK = "checkAutomatically"
        private const val INSTALL = "installAutomatically"
        private const val LAST_ATTEMPT = "lastAttemptMs"
        private const val APP_VERSION_CODE = "newestAppVersionCode"
        private const val APP_VERSION_NAME = "newestAppVersionName"
        private const val INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
