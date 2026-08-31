package dev.enginehost

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class SaveLocationStore(context: Context) {
    private val preferences = context.getSharedPreferences("save-location-v1", Context.MODE_PRIVATE)

    fun defaultRoot(): File = File(Environment.getExternalStorageDirectory(), "Enginehost")

    fun root(): File = preferences.getString(KEY_ROOT, null)?.let(::File) ?: defaultRoot()

    fun saveRoot(): File = File(root(), "saves").also {
        require(it.isDirectory || it.mkdirs()) { "Could not create Enginehost's save folder" }
        require(it.canWrite()) { "Enginehost's save folder is not writable" }
    }

    fun selectRoot(folder: File) {
        val canonical = folder.canonicalFile
        require(canonical.isDirectory || canonical.mkdirs()) { "Could not create the selected folder" }
        require(canonical.canWrite()) { "The selected folder is not writable" }
        preferences.edit().putString(KEY_ROOT, canonical.path).apply()
    }

    /** Legacy location used before the configurable shared save root existed. */
    @Suppress("DEPRECATION")
    fun legacyRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "Enginehost/saves",
    )

    fun migrate(source: File, destination: File = saveRoot()): MigrationResult {
        val from = source.canonicalFile
        val to = destination.canonicalFile
        if (!from.exists() || from == to) return MigrationResult(0, 0, 0)
        require(!to.toPath().startsWith(from.toPath())) { "The destination cannot be inside the source" }

        var copied = 0
        var conflicts = 0
        var failures = 0
        from.walkBottomUp().forEach { item ->
            val relative = item.relativeTo(from)
            val target = File(to, relative.path)
            if (item.isDirectory) {
                if (!target.exists() && !target.mkdirs()) failures++
            } else {
                try {
                    target.parentFile?.mkdirs()
                    if (target.exists()) {
                        if (sameContents(item, target)) item.delete() else conflicts++
                    } else {
                        FileInputStream(item).use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                        if (sameContents(item, target)) {
                            copied++
                            item.delete()
                        } else {
                            target.delete()
                            failures++
                        }
                    }
                } catch (_: Exception) {
                    failures++
                }
            }
        }
        from.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        return MigrationResult(copied, conflicts, failures)
    }

    private fun sameContents(left: File, right: File): Boolean {
        if (left.length() != right.length()) return false
        FileInputStream(left).use { a ->
            FileInputStream(right).use { b ->
                val ab = ByteArray(DEFAULT_BUFFER_SIZE)
                val bb = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val ac = a.read(ab)
                    val bc = b.read(bb)
                    if (ac != bc) return false
                    if (ac < 0) return true
                    if (!ab.copyOf(ac).contentEquals(bb.copyOf(bc))) return false
                }
            }
        }
    }

    data class MigrationResult(val copied: Int, val conflicts: Int, val failures: Int)

    companion object { private const val KEY_ROOT = "root" }
}
