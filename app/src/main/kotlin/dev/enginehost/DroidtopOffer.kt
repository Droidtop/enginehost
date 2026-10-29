package dev.enginehost

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The one line that tells Enginehost's people about droidtop (Droidtop/tracker#755): on the
 * empty library and on Settings > About, "Get droidtop from GitHub" or, when droidtop is
 * installed, "Open droidtop". Never a pop-up, never at launch, never during a game. Settings >
 * About's "Suggest droidtop" turns it off for good; while it is off, nothing here runs, not even
 * the check for droidtop (docs/SPEC.md "droidtop, suggested once").
 */
object DroidtopOffer {
    /** droidtop's builds, the source the line names. droidtop-platforms' other sources join it under #754. */
    const val RELEASES = "https://github.com/Droidtop/droidtop/releases"

    private const val PREFERENCES = "droidtop-offer"
    private const val KEY_SHOWN = "shown"

    /** What the line offers: nothing (turned off), getting droidtop, or opening it. */
    enum class Offer { NONE, GET, OPEN }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, true)

    fun setEnabled(context: Context, shown: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, shown).apply()
    }

    /** Reads the package manager: not for the main thread. */
    fun offer(context: Context): Offer = when {
        !enabled(context) -> Offer.NONE
        DroidtopLink.isInstalled(context) -> Offer.OPEN
        else -> Offer.GET
    }

    /** The button's words for [offer]. */
    fun label(context: Context, offer: Offer): String =
        context.getString(if (offer == Offer.OPEN) R.string.droidtop_open else R.string.droidtop_get)

    /** Opens droidtop, or its releases page in the person's browser. */
    fun act(context: Context, offer: Offer) {
        val intent = if (offer == Offer.OPEN) {
            context.packageManager.getLaunchIntentForPackage(DroidtopLink.DROIDTOP_PACKAGE)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))
        } ?: return
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser: the line has nowhere to go, and says nothing more.
        }
    }
}
