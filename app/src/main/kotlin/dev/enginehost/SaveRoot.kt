package dev.enginehost

import org.json.JSONObject
import java.io.File

/**
 * A caller's save folder for one launch: the inline config's `saveRoot`
 * (docs/contract.md "A caller's save folder: `saveRoot`", Droidtop/tracker#580).
 * Read from the caller's inline config only, never from a folder's
 * enginehost.json, and never stored. When accepted it is the game's own save
 * folder, as the caller keeps it, and the engine uses it as is (owner,
 * 2026-10-18); otherwise the launch runs on the person's own save location.
 */
object SaveRoot {
    const val KEY = "saveRoot"

    /**
     * The inline config's `savesSynced`: the caller keeps this game's saves
     * synced by a system of its own, so they must never quietly land in the
     * person's default location (owner, 2026-10-22, Droidtop/tracker#580).
     */
    const val SYNCED_KEY = "savesSynced"

    /** Why a `saveRoot` was not taken, as `GAME_ENDED` reports it on a synced launch. */
    const val MISSING = "missing"
    const val OUTSIDE_SHARED_STORAGE = "outside-shared-storage"
    const val ANDROID_DATA = "android-data"
    const val VOLUME_ROOT = "volume-root"
    const val NOT_WRITABLE = "not-writable"

    /** What a launch made of the caller's `saveRoot`: the folder it takes, or why it took none. */
    sealed class Check {
        class Taken(val folder: File) : Check()
        class Refused(val reason: String) : Check()
    }

    /** Whether [inlineJson] flags this game's saves as synced by the caller ([SYNCED_KEY]). */
    fun synced(inlineJson: String?): Boolean =
        inlineJson?.let { runCatching { JSONObject(it).optBoolean(SYNCED_KEY, false) }.getOrNull() } == true

    /** The folder [inlineJson] names, made if needed and writable, or null when it names none or one that is refused. */
    fun usable(inlineJson: String?): File? = (check(inlineJson) as? Check.Taken)?.folder

    /** [usable], saying why when no folder is taken. */
    fun check(inlineJson: String?): Check {
        val path = inlineJson?.let { runCatching { JSONObject(it).optString(KEY) }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?: return Check.Refused(MISSING)
        refusal(path)?.let { return Check.Refused(it) }
        // The real path must be acceptable too: a symlink cannot lead out of shared storage.
        val real = runCatching { File(path).canonicalFile }.getOrNull() ?: return Check.Refused(OUTSIDE_SHARED_STORAGE)
        refusal(real.path)?.let { return Check.Refused(it) }
        if (!real.isDirectory && !real.mkdirs()) return Check.Refused(NOT_WRITABLE)
        return if (real.isDirectory && real.canWrite()) Check.Taken(real) else Check.Refused(NOT_WRITABLE)
    }

    /**
     * Whether [path] may be a save root: absolute, with no `.` or `..`
     * segment, in shared storage (`/storage/emulated/<user>/...` or a
     * removable volume's `/storage/<volume>/...`, or the `/sdcard` alias),
     * and not inside an app's `Android/data` or `Android/obb`.
     */
    internal fun acceptable(path: String): Boolean = refusal(path) == null

    /** Why [path] may not be a save root ([acceptable]), or null when it may. */
    internal fun refusal(path: String): String? {
        if (!path.startsWith("/")) return OUTSIDE_SHARED_STORAGE
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return OUTSIDE_SHARED_STORAGE
        val volumeDepth = when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" && parts[2].all(Char::isDigit) -> 3
            parts.size >= 2 && parts[0] == "storage" && parts[1] != "emulated" && parts[1] != "self" -> 2
            parts.isNotEmpty() && parts[0] == "sdcard" -> 1
            else -> return OUTSIDE_SHARED_STORAGE
        }
        // The volume itself is not a save root; a folder in it is.
        if (parts.size <= volumeDepth) return VOLUME_ROOT
        val inVolume = parts.drop(volumeDepth)
        return if (inVolume.size >= 2 && inVolume[0] == "Android" && (inVolume[1] == "data" || inVolume[1] == "obb")) ANDROID_DATA else null
    }
}
