package dev.enginehost

import android.content.Context
import java.io.File

/**
 * A launch that stopped for a core: the catalog to install one, or the trust
 * screen to approve one. It resumes only when the core the person then
 * approves is what the game resolves to ([GameRunner.resolvesWith]), only
 * while it is fresh ([isFresh]), and is dropped when the person leaves that
 * screen without installing (review, 2026-10-16: a pending launch used to
 * outlive all of that and start a game the person had left long before).
 */
data class PendingPluginLaunch(
    val gamePath: String,
    val callerConfig: String?,
    /** The launch was Game setup's Test; it resumes as one. */
    val testing: Boolean = false,
    /** When the launch stopped, or an install for it last finished, in wall-clock milliseconds. */
    val savedAt: Long = 0,
) {
    /** Within [MAX_AGE_MS] of [savedAt]; a clock set back counts as stale too. */
    fun isFresh(now: Long): Boolean = now >= savedAt && now - savedAt <= MAX_AGE_MS

    companion object {
        /**
         * Long enough to read a trust prompt after an install; the clock
         * restarts when an install finishes, so a slow download does not
         * use it up.
         */
        const val MAX_AGE_MS = 10 * 60 * 1000L
    }
}

class PendingPluginLaunchStore(context: Context) {
    private val preferences = context.getSharedPreferences("pending-plugin-launch-v1", Context.MODE_PRIVATE)

    fun save(gameFolder: File, callerConfig: String?, testing: Boolean = false) {
        preferences.edit()
            .clear()
            .putString(PATH, gameFolder.absolutePath)
            .putString(CONFIG, callerConfig)
            .putBoolean(TESTING, testing)
            .putLong(SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** An install for the pending launch finished: the person now has a fresh while to approve it. */
    fun touch() {
        if (peek() != null) preferences.edit().putLong(SAVED_AT, System.currentTimeMillis()).apply()
    }

    /** The pending launch, or null when there is none or it went stale (and is dropped). */
    fun peek(): PendingPluginLaunch? {
        val path = preferences.getString(PATH, null) ?: return null
        val pending = PendingPluginLaunch(
            path, preferences.getString(CONFIG, null), preferences.getBoolean(TESTING, false),
            preferences.getLong(SAVED_AT, 0),
        )
        if (!pending.isFresh(System.currentTimeMillis())) {
            clear()
            return null
        }
        return pending
    }

    /** Removes and answers the pending launch; the caller has checked it resolves ([GameRunner.resolvesWith]). */
    fun take(): PendingPluginLaunch? = peek()?.also { clear() }

    fun clear() = preferences.edit().clear().apply()

    companion object {
        private const val PATH = "path"
        private const val CONFIG = "config"
        private const val TESTING = "testing"
        private const val SAVED_AT = "savedAt"

        /**
         * On the catalog or trust screen a launch stopped at: leaving that
         * screen with no install running drops the pending launch.
         */
        const val EXTRA_RESUMES = "dev.enginehost.RESUMES_PENDING_LAUNCH"
    }
}
