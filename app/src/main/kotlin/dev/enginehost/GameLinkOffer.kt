package dev.enginehost

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.view.Display
import android.widget.Toast

/**
 * Offers a link the running game asked to open (EngineHost.openLink; docs/SPEC.md, "Links a game
 * opens"; Droidtop/tracker#635). Owner, 2026-10-23: "Enginehost should parse the link type
 * (safely), and then OFFER to open it. A discord link gets offered to discord, etc".
 *
 * Enginehost's process does the opening; the core never calls startActivity for a link. The sheet
 * asks every time, with no "always": Cancel first (the safe choice leads, as on every warning
 * sheet), then the app the link belongs to when one installed opens it, the browser (or the email
 * app), and Copy. On a device with a second screen the chosen app opens on the screen the game is
 * not on, so the game keeps running. One offer at a time: a link asked for while one is showing
 * is dropped, so a game cannot stack sheets.
 */
object GameLinkOffer {
    private const val TAG = "EnginehostLinks"
    @Volatile private var showing = false
    @Volatile private var kinds: List<LinkKind>? = null

    /** From any thread; returns at once. */
    fun offer(activity: Activity, raw: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            if (showing) {
                Log.i(TAG, "A link was asked for while another is offered; dropped")
                return@runOnUiThread
            }
            val link = GameLinks.parse(raw, kinds(activity))
            showing = true
            val sheet = if (link == null) refused(activity) else offered(activity, link)
            sheet.onDismiss { showing = false }.show()
        }
    }

    private fun kinds(context: Context): List<LinkKind> = kinds ?: runCatching {
        GameLinks.parseKinds(context.assets.open("link-kinds.json").bufferedReader().use { it.readText() })
    }.getOrElse {
        Log.w(TAG, "link-kinds.json did not read; every link is a web page", it)
        emptyList()
    }.also { kinds = it }

    private fun refused(activity: Activity): Sheet {
        // Logcat only, never host events: a link can name things about the person.
        Log.w(TAG, "Refused a link the game asked to open")
        return Sheet(activity).warning()
            .title(R.string.link_refused_title)
            .message(R.string.link_refused_message)
            .choice(R.string.ok) {}
    }

    private fun offered(activity: Activity, link: GameLink): Sheet {
        val title = link.kind?.let { activity.getString(R.string.link_offer_title_app, it.name) }
            ?: activity.getString(if (link.isEmail) R.string.link_offer_title_email else R.string.link_offer_title_web)
        val where = when {
            link.displayHost != null && link.displayHost != link.host ->
                activity.getString(R.string.link_offer_host_decoded, link.displayHost, link.host)
            link.host != null -> link.host
            else -> null
        }
        val sheet = Sheet(activity).title(title)
            .message(listOfNotNull(where, link.text).joinToString("\n"))
            .choice(R.string.cancel) {}
        appFor(activity, link)?.let { (name, pkg) ->
            sheet.choice(activity.getString(R.string.link_open_in, name)) {
                open(activity, Intent(Intent.ACTION_VIEW, Uri.parse(link.text)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg))
            }
        }
        when {
            link.isWeb -> sheet.choice(R.string.link_open_browser) { openInBrowser(activity, link) }
            link.isEmail -> sheet.choice(R.string.link_open_email) { open(activity, Intent(Intent.ACTION_SENDTO, Uri.parse(link.text))) }
        }
        sheet.choice(R.string.link_copy) {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.link_clip_label), link.text))
            Toast.makeText(activity, R.string.link_copied, Toast.LENGTH_SHORT).show()
        }
        return sheet
    }

    /** The installed app the link's kind names that Android says opens it, as (label, package). */
    private fun appFor(activity: Activity, link: GameLink): Pair<String, String>? {
        val kind = link.kind ?: return null
        if (kind.packages.isEmpty()) return null
        val handlers = runCatching {
            activity.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse(link.text)).addCategory(Intent.CATEGORY_BROWSABLE), 0,
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val pkg = kind.packages.firstOrNull { it in handlers } ?: return null
        return kind.name to pkg
    }

    private fun openInBrowser(activity: Activity, link: GameLink) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link.text)).addCategory(Intent.CATEGORY_BROWSABLE)
        // A browser, not whichever app claims the link: the person chose "browser".
        val browser = Intent(view).apply { selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) }
        if (!open(activity, browser, quiet = true)) open(activity, view)
    }

    private fun open(activity: Activity, intent: Intent, quiet: Boolean = false): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent, otherScreen(activity)?.toBundle())
        true
    } catch (e: ActivityNotFoundException) {
        if (!quiet) Toast.makeText(activity, R.string.link_no_app, Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Opening a link was refused", e)
        if (!quiet) Toast.makeText(activity, R.string.link_no_app, Toast.LENGTH_SHORT).show()
        false
    }

    /** On a device with a second screen that is on, the one the game is not on. */
    private fun otherScreen(activity: Activity): ActivityOptions? {
        val current = activity.window?.decorView?.display?.displayId ?: return null
        val displays = activity.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        val other = displays.firstOrNull { it.displayId != current && it.state == Display.STATE_ON } ?: return null
        return ActivityOptions.makeBasic().setLaunchDisplayId(other.displayId)
    }
}
