package dev.enginehost

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Who Android's own record says started `LAUNCH` -- never what the
 * caller's own intent extras claim (see [LaunchEntryActivity]).
 */
sealed class LaunchCaller {
    /** Verified against droidtop's package and signing certificate ([TrustedCallers]): always allowed, no decision asked. */
    object Droidtop : LaunchCaller()

    /**
     * No real caller identity could be trusted: no referrer at all (the
     * shape a `LAUNCH` from `adb shell am start` takes), or the received
     * intent supplied its own `EXTRA_REFERRER`/`EXTRA_REFERRER_NAME` --
     * ordinary extras any caller can set, which `Activity.getReferrer()`
     * would otherwise repeat back unchecked, so neither is trusted.
     */
    object Unknown : LaunchCaller()

    /** A real, system-attributed package that is not droidtop. */
    data class App(val packageName: String) : LaunchCaller()

    /** The key [CallerAccessStore] and [CallerAccessSettingsActivity] use; stable for every unverifiable caller alike. */
    val storeKey: String
        get() = when (this) {
            Droidtop -> TrustedCallers.DROIDTOP_PACKAGE
            Unknown -> UNKNOWN_KEY
            is App -> packageName
        }

    companion object {
        const val UNKNOWN_KEY = "(unrecognized caller)"
    }
}

/** A decision the person made about one caller, kept until they change or remove it (owner, 2026-09-27). */
enum class CallerDecision { ALLOW, BLOCK }

/**
 * Where a person's per-caller `LAUNCH` decisions live (owner, 2026-09-27:
 * "an allowlist/blocklist function to filter launches"). Only ALLOW and
 * BLOCK are ever stored -- there is no persisted "Ask": a caller with no
 * entry here falls back to [CallerDefaults]'s blocklist heuristic, Ask
 * otherwise. Keyed by package name, or [LaunchCaller.UNKNOWN_KEY] for
 * every caller Enginehost could not identify at all.
 * [CallerAccessSettingsActivity] lists and edits exactly what this holds.
 */
class CallerAccessStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun decisionFor(key: String): CallerDecision? = when (prefs.getString(key, null)) {
        ALLOW_VALUE -> CallerDecision.ALLOW
        BLOCK_VALUE -> CallerDecision.BLOCK
        else -> null
    }

    /** [decision] null removes the entry -- that caller reverts to the default heuristic. */
    fun setDecision(key: String, decision: CallerDecision?) {
        prefs.edit().apply {
            if (decision == null) {
                remove(key)
            } else {
                putString(key, if (decision == CallerDecision.ALLOW) ALLOW_VALUE else BLOCK_VALUE)
            }
        }.apply()
    }

    /** Every caller a person has an explicit decision for, for the settings screen. */
    fun all(): Map<String, CallerDecision> = prefs.all.entries.mapNotNull { (key, value) ->
        val decision = when (value) {
            ALLOW_VALUE -> CallerDecision.ALLOW
            BLOCK_VALUE -> CallerDecision.BLOCK
            else -> null
        }
        decision?.let { key to it }
    }.toMap()

    companion object {
        private const val PREFS = "caller-access-v1"
        private const val ALLOW_VALUE = "allow"
        private const val BLOCK_VALUE = "block"
    }
}

/**
 * Every real caller [LaunchActivity] has actually gated a `LAUNCH` from,
 * decided or not, kept independently of [CallerAccessStore] (which only
 * ever holds ALLOW/BLOCK). Package visibility (Android 11+) can hide a
 * caller from `CallerAccessSettingsActivity`'s own `queryIntentActivities`
 * app picker even once it has genuinely called Enginehost: a plain
 * PackageManager query only sees what the device chooses to show this app,
 * not what has actually reached it (owner, build 234 follow-up: "the list
 * should also show every app that has actually called Enginehost"). Never
 * records [LaunchCaller.UNKNOWN_KEY]: that bucket names no real package, so
 * there is nothing here for a person to recognise or decide about beyond
 * the Ask prompt itself.
 */
class CallerSightingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(key: String) {
        if (!worthRecording(key)) return
        val current = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        if (key in current) return
        // getStringSet hands back the live backing set on some OEM
        // implementations; copy before mutating so the edit is real.
        prefs.edit().putStringSet(KEY_SEEN, HashSet(current).apply { add(key) }).apply()
    }

    fun all(): Set<String> = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    companion object {
        private const val PREFS = "caller-sightings-v1"
        private const val KEY_SEEN = "seen"

        /** The testable core: [LaunchCaller.UNKNOWN_KEY] names no real package, so it is never worth remembering. */
        fun worthRecording(key: String): Boolean = key != LaunchCaller.UNKNOWN_KEY
    }
}

/**
 * The sane default this app ships with before a person has decided
 * anything about a caller (owner, 2026-09-27): a browser, or a known
 * remote-access or automation tool, starts Blocked rather than Ask,
 * because a page or a script is the shape a confused-deputy attempt
 * actually takes, not a person's own choice to run a game. Anyone can
 * move a default-blocked package to Allow, from the prompt or from
 * [CallerAccessSettingsActivity]; this only decides what a caller with no
 * explicit decision gets.
 */
object CallerDefaults {
    enum class BlockReason { BROWSER, REMOTE_OR_AUTOMATION }

    /**
     * A short list of major browsers' own package names, as a fallback
     * for one that does not currently resolve as a device's `http`/`https`
     * handler (a fresh install, a disabled default) -- [isBrowser]'s real
     * definition of "a browser" is the resolution check below, not this
     * list alone.
     */
    private val KNOWN_BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.android.browser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.focus",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser",
        "com.UCMobile.intl",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
    )

    /**
     * Terminal/SSH, remote-desktop and device-automation apps: there is no
     * platform category for these the way `ACTION_VIEW` resolution gives
     * one for browsers, so this is a maintained list of real, well-known
     * packages, not a heuristic.
     */
    private val KNOWN_REMOTE_OR_AUTOMATION_PACKAGES = setOf(
        "com.termux",
        "com.termux.api",
        "org.connectbot",
        "com.teamviewer.host.market",
        "com.teamviewer.quicksupport.market",
        "com.teamviewer.teamviewer.market.mobile",
        "com.anydesk.anydeskandroid",
        "com.realvnc.viewer.android",
        "android.androidVNC",
        "com.google.chromeremotedesktop",
        "net.dinglisch.android.taskerm",
        "com.arlosoft.macrodroid",
        "com.llamalab.automate",
    )

    /** True for a package this app already knows by name, without asking the platform anything -- what makes this testable in a plain JVM test. */
    fun isKnownBrowserPackage(packageName: String): Boolean = packageName in KNOWN_BROWSER_PACKAGES

    fun isRemoteOrAutomationTool(packageName: String): Boolean = packageName in KNOWN_REMOTE_OR_AUTOMATION_PACKAGES

    /**
     * [resolvesHttpHandler] is the platform check (does [packageName]
     * resolve `ACTION_VIEW` for `http`/`https`); real callers pass
     * [resolvesHttpHandlerOn], a test passes whatever it likes.
     */
    fun isBrowser(packageName: String, resolvesHttpHandler: (String) -> Boolean): Boolean =
        isKnownBrowserPackage(packageName) || resolvesHttpHandler(packageName)

    /** Why [key] is blocked by default, or null if it is not (also null for [LaunchCaller.UNKNOWN_KEY], which is not a real package). */
    fun blockReason(key: String, resolvesHttpHandler: (String) -> Boolean): BlockReason? = when {
        key == LaunchCaller.UNKNOWN_KEY -> null
        isBrowser(key, resolvesHttpHandler) -> BlockReason.BROWSER
        isRemoteOrAutomationTool(key) -> BlockReason.REMOTE_OR_AUTOMATION
        else -> null
    }

    /** The real, on-device [resolvesHttpHandler]: does [packageName] resolve `ACTION_VIEW` for a plain `http` URL. */
    fun resolvesHttpHandlerOn(pm: PackageManager): (String) -> Boolean = { packageName ->
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        runCatching {
            pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo?.packageName == packageName }
        }.getOrDefault(false)
    }
}

/** What a caller's `LAUNCH` actually gets, after [CallerAccessStore]'s explicit decision and [CallerDefaults]'s heuristic both had their say. */
sealed class EffectiveAccess {
    object Allow : EffectiveAccess()
    object Ask : EffectiveAccess()
    data class Block(val reason: CallerDefaults.BlockReason?) : EffectiveAccess()

    companion object {
        fun forCaller(store: CallerAccessStore, pm: PackageManager, key: String): EffectiveAccess =
            forCaller(key, store.decisionFor(key), CallerDefaults.resolvesHttpHandlerOn(pm))

        /** The testable core: no [Context] or [PackageManager], so a plain JVM test can drive every branch directly. */
        fun forCaller(key: String, stored: CallerDecision?, resolvesHttpHandler: (String) -> Boolean): EffectiveAccess {
            when (stored) {
                CallerDecision.ALLOW -> return Allow
                CallerDecision.BLOCK -> return Block(null)
                null -> {}
            }
            val reason = CallerDefaults.blockReason(key, resolvesHttpHandler)
            return if (reason != null) Block(reason) else Ask
        }
    }
}
