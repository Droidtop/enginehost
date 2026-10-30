package dev.enginehost

import android.content.Context

/**
 * Which core builds have been launched, so the first launch of each is known: that one unpacks
 * the core's runtime and has the engine compile the game's scripts before anything is drawn,
 * and the black-screen question waits longer for it ([BlackScreenRule], Droidtop/tracker#821).
 * Kept here, in the main process, because the runtime process is locked down and keeps nothing;
 * the answer travels in the launch's intent ([RuntimeActivity.EXTRA_FIRST_LAUNCH]).
 *
 * A build is its archive's SHA-256, as the launch names it. Planning a launch counts as
 * launching it. Called off the main thread, with the rest of the plan.
 */
internal class CoreLaunches(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Whether [archiveSha256] has never been launched before; it counts as launched from now on. */
    fun firstLaunch(archiveSha256: String): Boolean {
        val key = "launched.$archiveSha256"
        if (preferences.getBoolean(key, false)) return false
        preferences.edit().putBoolean(key, true).apply()
        return true
    }

    private companion object {
        const val PREFERENCES = "core-launches-v1"
    }
}
