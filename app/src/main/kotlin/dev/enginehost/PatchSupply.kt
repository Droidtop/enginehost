package dev.enginehost

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Putting a user-supplied compatibility patch into a game folder.
 *
 * The whole mechanism is deliberately passive about where the file came
 * from. Enginehost does not download patches and does not go looking
 * through Downloads for something that resembles one: a file becomes a
 * patch because the user picked it. An in-app fetcher would make
 * Enginehost the delivery vehicle for whatever a hostile host served,
 * executed as engine script under Enginehost's own storage permission,
 * and a Downloads scanner would quietly make that same decision on the
 * user's behalf.
 *
 * Unpacking is safe in a way fetching is not, because the host decides
 * what comes out and where it goes. Both paths below refuse to write
 * outside the game folder.
 */
object PatchSupply {

    /**
     * Copies what the user picked into [gameFolder].
     *
     * A plain file keeps its own name unless the engine told us what it
     * was looking for ([requiredFile]), in which case it lands under that
     * name — the engine's patch loader is the authority on what it will
     * actually open.
     *
     * An archive is unpacked. When [requiredFile] is known, only the
     * matching entry is taken, which is the narrow case worth having:
     * patch archives routinely carry readmes, screenshots and installers
     * that have no business in a game folder. Otherwise every entry is
     * extracted, since without a target name there is nothing to select
     * on and the engine will find what it needs.
     *
     * @return true when at least one file was written.
     */
    fun install(
        context: Context,
        source: Uri,
        gameFolder: File,
        requiredFile: String?,
    ): Boolean = runCatching {
        context.contentResolver.openInputStream(source)?.use { stream ->
            val name = displayName(context, source)
            if (looksLikeZip(name)) {
                extractZip(stream, gameFolder, requiredFile)
            } else {
                val target = safeChild(gameFolder, requiredFile ?: name) ?: return@use false
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> stream.copyTo(out) }
                true
            }
        } ?: false
    }.getOrElse { error ->
        Log.e(TAG, "Patch install failed", error)
        false
    }

    private fun extractZip(stream: InputStream, gameFolder: File, requiredFile: String?): Boolean {
        var wrote = false
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val entryName = entry.name.substringAfterLast('/')
                // With a known target, take only that entry: patch archives
                // routinely carry readmes and installers we should not be
                // dropping into somebody's game folder.
                if (requiredFile != null && !entryName.equals(requiredFile, ignoreCase = true)) {
                    zip.closeEntry()
                    continue
                }
                // Zip-slip: entry names are attacker-controlled, so the
                // destination is validated against the game folder rather
                // than trusted. Absolute paths and ../ both fail here.
                val target = safeChild(gameFolder, if (requiredFile != null) requiredFile else entry.name)
                if (target == null) {
                    Log.w(TAG, "Refused archive entry escaping the game folder: ${entry.name}")
                    zip.closeEntry()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> zip.copyTo(out) }
                wrote = true
                zip.closeEntry()
            }
        }
        return wrote
    }

    /** Null when [relative] would land outside [root]. */
    private fun safeChild(root: File, relative: String): File? {
        if (relative.isBlank()) return null
        val candidate = File(root, relative)
        val canonicalRoot = root.canonicalFile
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        return canonical.takeIf { it.path.startsWith(prefix) }
    }

    private fun looksLikeZip(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true)

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "patch"
    }

    private const val TAG = "enginehost-patch"
}
