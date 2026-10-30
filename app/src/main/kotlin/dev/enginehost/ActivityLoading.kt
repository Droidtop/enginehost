package dev.enginehost

import android.app.Activity
import android.os.SystemClock

/**
 * The loading route for a core on the activity transport (Ren'Py): such a core runs its own
 * Activity in the runtime process and has no EngineHost, so it reports how far its start-up has
 * got by calling this by name, through reflection, from that Activity (Droidtop/tracker#821).
 * The same kind of named bridge as [ActivityLinks] and [ActivityText], and deleted with them
 * when the core moves onto the plugin transport.
 *
 * The fraction is the engine's own: for Ren'Py, the share its desktop presplash would draw.
 * The host's loading screen draws it ([LoadingCover]), and a report is a sign the game is
 * still loading for the black-screen question ([BlackScreenRule]). The name and signature are
 * the contract: `dev.enginehost.ActivityLoading.progress(Activity, float)`.
 */
object ActivityLoading {
    /** [done] of the engine's start-up, 0 to 1, from the game running in [activity]; any thread, returns at once. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun progress(activity: Activity, done: Float) {
        LoadingProgress.report(done, SystemClock.uptimeMillis())
        LoadingCover.progressed()
    }
}
