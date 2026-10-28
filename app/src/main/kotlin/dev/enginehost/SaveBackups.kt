package dev.enginehost

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One game's save backups (Droidtop/tracker#238): a dated copy of its save folders,
 * listed and restored from its page.
 *
 * Enginehost does not change where an engine saves and cannot snapshot an engine, so
 * this is a copy of the files the engine keeps, never a save state. A backup is a
 * folder named for the moment it was taken under the game's own backups folder
 * ([folderFor]), with one subfolder per save folder backed up. Nothing here ever
 * overwrites a backup, follows a symbolic link, or deletes a save: a restore first
 * backs the current saves up, then copies the backup's files over them, so a save the
 * backup does not have stays where it is. Free of the Context, so it is unit tested.
 */
object SaveBackups {
    /** One backup: where it is, when it was taken (from its name) and its size. */
    class Backup(val folder: File, val takenAt: Long, val bytes: Long)

    /** What a copy did: files copied, and files that could not be (unreadable, or a link that was skipped). */
    class Copied(val files: Int, val skipped: Int)

    private const val BACKUPS = "save-backups"
    private const val STAMP = "yyyy-MM-dd'T'HH-mm-ss"

    /**
     * [gameFolder]'s backups under [root] (the save location's root, beside its `saves`):
     * named for the game's folder plus a short digest of its path, as its synced saves are,
     * so two games with one folder name keep apart.
     */
    fun folderFor(root: File, gameFolder: File): File =
        File(File(root, BACKUPS), SaveLocationStore.syncedFolderIn(root, gameFolder).name)

    /** Backs [sources] up into a new dated folder in [backups]; never into one that exists. */
    fun backUp(sources: List<File>, backups: File, now: Long, onFile: (Int) -> Unit = {}): Pair<File, Copied> {
        val base = SimpleDateFormat(STAMP, Locale.ROOT).format(Date(now))
        var target = File(backups, base)
        var suffix = 2
        while (target.exists()) target = File(backups, "$base-${suffix++}")
        if (!target.mkdirs()) throw IOException("Could not make $target")
        var files = 0
        var skipped = 0
        for ((source, name) in named(sources)) {
            val copied = copyTree(source, File(target, name)) { onFile(files + it) }
            files += copied.files
            skipped += copied.skipped
        }
        return target to Copied(files, skipped)
    }

    /** The backups in [backups], newest first. Sizes are measured, so not for the main thread. */
    fun list(backups: File): List<Backup> =
        (backups.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            .mapNotNull { folder ->
                val takenAt = runCatching {
                    SimpleDateFormat(STAMP, Locale.ROOT).parse(folder.name.take(STAMP.length - 2))?.time
                }.getOrNull() ?: return@mapNotNull null
                Backup(folder, takenAt, sizeOf(folder.toPath()))
            }
            .sortedByDescending { it.takenAt }

    /**
     * Puts [backup] back: [sources] are backed up into [backups] first, then the backup's
     * files are copied over them. Files the backup does not hold are left alone.
     */
    fun restore(backup: File, sources: List<File>, backups: File, now: Long, onFile: (Int) -> Unit = {}): Copied {
        backUp(sources.filter { it.isDirectory }, backups, now)
        var files = 0
        var skipped = 0
        for ((source, name) in named(sources)) {
            val from = File(backup, name)
            if (!from.isDirectory) continue
            val copied = copyTree(from, source, overwrite = true) { onFile(files + it) }
            files += copied.files
            skipped += copied.skipped
        }
        return Copied(files, skipped)
    }

    /** Copies [sources] into a new folder named for the game inside [destination], for the person to keep elsewhere. */
    fun copyTo(sources: List<File>, destination: File, name: String, onFile: (Int) -> Unit = {}): Pair<File, Copied> {
        var target = File(destination, name)
        var suffix = 2
        while (target.exists()) target = File(destination, "$name ${suffix++}")
        if (!target.mkdirs()) throw IOException("Could not make $target")
        var files = 0
        var skipped = 0
        for ((source, sub) in named(sources)) {
            val copied = copyTree(source, File(target, sub)) { onFile(files + it) }
            files += copied.files
            skipped += copied.skipped
        }
        return target to Copied(files, skipped)
    }

    /** Each source with the subfolder name it is kept under: its own name, made unique. */
    private fun named(sources: List<File>): List<Pair<File, String>> {
        val used = mutableSetOf<String>()
        return sources.map { source ->
            var name = source.name.ifBlank { "saves" }
            var n = 2
            while (!used.add(name)) name = "${source.name}-${n++}"
            source to name
        }
    }

    /**
     * Copies the regular files under [from] to [to], keeping their places. Symbolic links
     * are neither followed nor copied, so nothing outside [from] is read; an existing file
     * is replaced only with [overwrite].
     */
    private fun copyTree(from: File, to: File, overwrite: Boolean = false, onFile: (Int) -> Unit): Copied {
        val root = from.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Copied(0, 0)
        var files = 0
        var skipped = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(to.toPath().resolve(root.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) {
                    skipped++
                    return FileVisitResult.CONTINUE
                }
                val target = to.toPath().resolve(root.relativize(file).toString())
                try {
                    if (overwrite) {
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    } else if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    onFile(++files)
                } catch (_: IOException) {
                    skipped++
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                skipped++
                return FileVisitResult.CONTINUE
            }
        })
        return Copied(files, skipped)
    }

    private fun sizeOf(root: Path): Long {
        var bytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) bytes += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return bytes
    }
}
