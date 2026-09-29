package dev.enginehost

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Gathers a summary of native crash tombstones if present.
 * Android writes tombstones to /data/tombstones/ for native crashes.
 */
object TombstoneSummary {
    private const val TAG = "enginehost"
    private const val MAX_TOMBSTONE_CHARS = 2000

    /**
     * Checks for recent tombstone files and returns a summary.
     * Only looks for tombstones related to this app's process.
     */
    fun gather(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ""
        }
        return runCatching {
            val tombstoneDir = File("/data/tombstones")
            if (!tombstoneDir.isDirectory) return@runCatching ""
            
            val packageName = context.packageName
            val tombstones = tombstoneDir.listFiles()
                ?.filter { it.name.matches(Regex("tombstone_\\d+")) }
                ?.filter { isOurTombstone(it, packageName) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(2) ?: return@runCatching ""
            
            tombstones.map { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                val trimmed = if (content.length > MAX_TOMBSTONE_CHARS) {
                    content.takeLast(MAX_TOMBSTONE_CHARS)
                } else {
                    content
                }
                "=== ${file.name} ===\n$trimmed"
            }.joinToString(sep = "\n\n")
        }.getOrElse { "" }
    }

    private fun isOurTombstone(file: File, packageName: String): Boolean {
        return runCatching {
            val reader = FileReader(file)
            try {
                // Read first few KB to check if it's our process
                val buffer = CharArray(4096)
                reader.read(buffer)
                val content = String(buffer)
                return content.contains(packageName)
            } finally {
                reader.close()
            }
        }.getOrDefault(false)
    }
}