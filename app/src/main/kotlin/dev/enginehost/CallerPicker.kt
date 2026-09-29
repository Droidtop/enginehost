package dev.enginehost

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** One launchable app as the "Add an app" picker sees it. */
data class CallerCandidate(
    val packageName: String,
    val label: String,
    /** Installed by the person; not part of the device's own image. */
    val userInstalled: Boolean,
    /** Offers itself as a home screen: a launcher or a game frontend. */
    val home: Boolean,
    /** Declares itself a game. */
    val game: Boolean,
    /** A browser or a remote-access or automation tool, which [CallerDefaults] blocks until decided. */
    val blockedByDefault: Boolean,
) {
    /** Declaration order is the picker's ranking. */
    enum class Role { FRONTEND, GAME, APP, BLOCKED_BY_DEFAULT }

    val role: Role
        get() = when {
            home -> Role.FRONTEND
            game -> Role.GAME
            blockedByDefault -> Role.BLOCKED_BY_DEFAULT
            else -> Role.APP
        }
}

/**
 * Which apps "Add an app" (Settings > App launch access) offers first
 * (Droidtop/tracker#33). Deciding who may launch a game only matters for a
 * handful of apps: the launchers and frontends that start games, and other
 * apps a person installed. The device's own Camera, Clock and Files are not
 * that, so they are left out until "Show every app" is asked for, and no row
 * is named by its package id.
 */
object CallerPicker {
    /** The apps worth deciding about: frontends first, then games, then other installed apps, then the default-blocked ones; each group by name. */
    fun relevant(all: List<CallerCandidate>): List<CallerCandidate> =
        all.filter { it.userInstalled || it.home }.sortedWith(ORDER)

    /** Every launchable app, same order: the way out when the app someone means was filtered. */
    fun everything(all: List<CallerCandidate>): List<CallerCandidate> = all.sortedWith(ORDER)

    private val ORDER = compareBy<CallerCandidate>({ it.role.ordinal }, { it.label.lowercase() }, { it.packageName })

    /** Launchable apps besides this one, droidtop and [exclude]. Queries the package manager for every app: not for the main thread. */
    fun load(context: Context, exclude: Set<String>): List<CallerCandidate> {
        val pm = context.packageManager
        val homes = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
        val http = CallerDefaults.resolvesHttpHandlerOn(pm)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName && it.packageName != TrustedCallers.DROIDTOP_PACKAGE && it.packageName !in exclude }
            .map { info ->
                val app = info.applicationInfo
                CallerCandidate(
                    packageName = info.packageName,
                    label = app?.let { pm.getApplicationLabel(it).toString() }?.takeIf { it.isNotBlank() } ?: info.packageName,
                    userInstalled = app != null && app.flags and ApplicationInfo.FLAG_SYSTEM == 0,
                    home = info.packageName in homes,
                    game = app?.category == ApplicationInfo.CATEGORY_GAME,
                    blockedByDefault = CallerDefaults.blockReason(info.packageName, http) != null,
                )
            }
    }
}

/** How a caller is named to a person: the app's own name, its package id only when the device will not say. */
object CallerLabels {
    fun of(pm: PackageManager, key: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: key
}
