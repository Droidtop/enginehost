package dev.enginehost

import android.content.Context

/**
 * The person's choices for a game's text (docs/text-services.md). Read in the main process, when
 * a launch is planned, and handed to the runtime with the launch ([RuntimeActivity.EXTRA_READ_ALOUD]),
 * which reads no preferences of its own once its sandbox has closed.
 */
class TextSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("text_services", Context.MODE_PRIVATE)

    /** Read lines aloud with the device's own voices. Off until the person turns it on. */
    var readAloud: Boolean
        get() = prefs.getBoolean(READ_ALOUD, false)
        set(value) = prefs.edit().putBoolean(READ_ALOUD, value).apply()

    private companion object {
        const val READ_ALOUD = "read_aloud"
    }
}
