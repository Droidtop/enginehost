package dev.enginehost

import android.content.Context
import java.io.File

data class PendingPluginLaunch(val gamePath: String, val callerConfig: String?, val bundleId: String?)

class PendingPluginLaunchStore(context: Context) {
    private val preferences = context.getSharedPreferences("pending-plugin-launch-v1", Context.MODE_PRIVATE)

    fun save(gameFolder: File, callerConfig: String?, bundleId: String? = null) {
        preferences.edit()
            .putString(PATH, gameFolder.absolutePath)
            .putString(CONFIG, callerConfig)
            .putString(BUNDLE, bundleId)
            .apply()
    }

    fun setBundle(bundleId: String) = preferences.edit().putString(BUNDLE, bundleId).apply()

    fun peek(): PendingPluginLaunch? {
        val path = preferences.getString(PATH, null) ?: return null
        return PendingPluginLaunch(path, preferences.getString(CONFIG, null), preferences.getString(BUNDLE, null))
    }

    fun consumeFor(bundleId: String): PendingPluginLaunch? {
        val pending = peek() ?: return null
        if (pending.bundleId != null && pending.bundleId != bundleId) return null
        preferences.edit().clear().apply()
        return pending
    }

    companion object {
        private const val PATH = "path"
        private const val CONFIG = "config"
        private const val BUNDLE = "bundle"
    }
}
