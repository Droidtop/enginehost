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

    /** The folder [inlineJson] names, made if needed and writable, or null when it names none or one that is refused. */
    fun usable(inlineJson: String?): File? {
        val path = inlineJson?.let { runCatching { JSONObject(it).optString(KEY) }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?: return null
        if (!acceptable(path)) return null
        val folder = File(path)
        // The real path must be acceptable too: a symlink cannot lead out of shared storage.
        val real = runCatching { folder.canonicalFile }.getOrNull() ?: return null
        if (!acceptable(real.path)) return null
        if (!real.isDirectory && !real.mkdirs()) return null
        return real.takeIf { it.isDirectory && it.canWrite() }
    }

    /**
     * Whether [path] may be a save root: absolute, with no `.` or `..`
     * segment, in shared storage (`/storage/emulated/<user>/...` or a
     * removable volume's `/storage/<volume>/...`, or the `/sdcard` alias),
     * and not inside an app's `Android/data` or `Android/obb`.
     */
    internal fun acceptable(path: String): Boolean {
        if (!path.startsWith("/")) return false
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return false
        val volumeDepth = when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" && parts[2].all(Char::isDigit) -> 3
            parts.size >= 2 && parts[0] == "storage" && parts[1] != "emulated" && parts[1] != "self" -> 2
            parts.isNotEmpty() && parts[0] == "sdcard" -> 1
            else -> return false
        }
        // The volume itself is not a save root; a folder in it is.
        if (parts.size <= volumeDepth) return false
        val inVolume = parts.drop(volumeDepth)
        return !(inVolume.size >= 2 && inVolume[0] == "Android" && (inVolume[1] == "data" || inVolume[1] == "obb"))
    }
}
