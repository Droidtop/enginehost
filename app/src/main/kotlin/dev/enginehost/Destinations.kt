package dev.enginehost

import android.app.Activity
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * The three places the app is organised around (Droidtop/tracker#234): the
 * Library of games, the Cores that run them (the installed plugins) and
 * Settings. Adding games is the Library's one primary action, not a place.
 * Everything else opens inside the destination that owns it.
 */
enum class Destination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val screen: Class<out Activity>,
) {
    LIBRARY(R.string.nav_library, R.drawable.ic_play, MainActivity::class.java),
    CORES(R.string.nav_cores, R.drawable.ic_plugins, PluginTrustActivity::class.java),
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings, EnginehostSettingsActivity::class.java),
}

object Destinations {
    /** The destination [steps] along from [from], wrapping: R1 is +1 and L1 is -1. */
    fun neighbour(from: Destination, steps: Int): Destination {
        val all = Destination.entries
        return all[((from.ordinal + steps) % all.size + all.size) % all.size]
    }

    /**
     * Goes to [to] from the destination screen [from]. The Library is the root, so reaching it
     * pops back to it; the others replace the destination screen they are opened from, so back
     * from either one always lands on the Library and the stack never grows with each switch.
     */
    fun open(from: Activity, current: Destination, to: Destination) {
        if (to == current) return
        val intent = Intent(from, to.screen)
        if (to == Destination.LIBRARY) {
            from.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return
        }
        from.startActivity(intent)
        if (current != Destination.LIBRARY) from.finish()
    }
}
