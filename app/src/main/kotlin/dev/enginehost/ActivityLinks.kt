package dev.enginehost

import android.app.Activity

/**
 * The link route for a core on the activity transport (Ren'Py, KiriKiri, NScripter, AGS, mkxp-z):
 * such a core runs its own Activity in the runtime process and has no EngineHost to call openLink
 * on, so it calls [offer] by name, through reflection, from that Activity. It is the same check and
 * the same offer as EngineHost.openLink ([GameLinkOffer]; docs/SPEC.md, "Links a game opens").
 *
 * The owner chose this bridge on 2026-10-24 as the activity transport's route until each of those
 * cores moves onto the plugin transport, where EngineHost.openLink replaces it; it is deleted then.
 * The name and signature are the contract: `dev.enginehost.ActivityLinks.offer(Activity, String)`.
 */
object ActivityLinks {
    /** Offers [url] over [activity]; true when Enginehost took it (a refused link included). */
    @JvmStatic
    fun offer(activity: Activity, url: String): Boolean {
        GameLinkOffer.offer(activity, url)
        return true
    }
}
