package dev.enginehost

import android.app.Activity

/**
 * The text route for a core on the activity transport (Ren'Py): such a core runs its own Activity
 * in the runtime process and has no EngineHost to call emitText and replaceText on, so it calls
 * these by name, through reflection, from that Activity. They go to the same [GameText] as
 * EngineHost.emitText and replaceText (docs/text-services.md; Droidtop/tracker#490).
 *
 * The owner chose this bridge on 2026-10-28, the same kind as [ActivityLinks], as the activity
 * transport's route until each of those cores moves onto the plugin transport, where
 * EngineHost.emitText and replaceText replace it; it is deleted then, with [ActivityLinks]. The
 * names and signatures are the contract: `dev.enginehost.ActivityText.emit(Activity, String)` and
 * `dev.enginehost.ActivityText.replace(String): String?`.
 */
object ActivityText {
    /** One ehtext/1 event from the game running in [activity]; any thread, returns at once. */
    @JvmStatic
    fun emit(activity: Activity, event: String) = GameText.emit(activity, event)

    /** [event] (a line about to be printed) in the person's language, or null to print the original; never waits. */
    @JvmStatic
    fun replace(event: String): String? = GameText.replace(event)
}
