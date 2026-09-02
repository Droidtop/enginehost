package dev.enginehost

import java.io.File
import java.util.ArrayDeque

/** One game tree found on disk, classified by [EngineDetector]. */
data class GameCandidate(val folder: File, val detection: EngineDetection)

/**
 * Finds game trees beneath a chosen root so a library does not have to be
 * assembled one folder picker at a time.
 *
 * Classification is [EngineDetector]'s alone -- the same evidence-based
 * detection the config creator uses -- so scanning introduces no second
 * engine taxonomy and never guesses from folder names. The name screening
 * in [looksLikeGameRoot] only decides where running the detector is worth
 * it; a directory that passes screening but fails detection is simply
 * descended into.
 *
 * Shared storage is served through a FUSE layer that has hung before on
 * filenames legal on ext4 but not on exFAT, so every filesystem touch is
 * wrapped, unreadable entries are counted and skipped rather than fatal,
 * the walk is iterative with depth and volume caps, and [cancel] can stop
 * it between directories.
 */
class GameScanner(
    private val maxDepth: Int = MAX_DEPTH,
    private val maxDirectories: Int = MAX_DIRECTORIES,
) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    interface Listener {
        fun onProgress(directoriesExamined: Int, found: Int)
        fun onFound(candidate: GameCandidate)
        fun onFinished(directoriesExamined: Int, found: Int, stoppedEarly: Boolean, unreadable: Int)
    }

    fun scan(root: File, listener: Listener) {
        var examined = 0
        var found = 0
        var unreadable = 0
        var stoppedEarly = false
        val queue = ArrayDeque<Pair<File, Int>>()
        val seen = HashSet<String>()
        queue.add(root to 0)

        // Reported on every path through the loop below (including the
        // early `continue`s for a found game, an unreadable directory, or
        // an unresolvable path) so the listener's progress count always
        // matches the number of directories actually examined. Batched to
        // every PROGRESS_EVERY so it doesn't flood the UI thread on a big
        // tree, but the batching check itself must never be skippable --
        // that was the original bug: a `continue` taken before this call
        // reached its old position at the bottom of the loop meant a run
        // of game-root folders (found++'d) advanced `examined` internally
        // while the listener heard about none of it.
        fun reportProgress() {
            if (examined % PROGRESS_EVERY == 0) listener.onProgress(examined, found)
        }

        while (queue.isNotEmpty()) {
            if (cancelled || examined >= maxDirectories) {
                stoppedEarly = true
                break
            }
            val (directory, depth) = queue.removeFirst()
            val canonical = runCatching { directory.canonicalPath }.getOrNull()
            if (canonical == null) {
                // Could not even resolve the path (broken symlink, FUSE
                // hiccup). The scanner still attempted this directory, so
                // it counts toward "how much of the card has it looked
                // at" the same as any other unreadable entry.
                examined++
                unreadable++
                reportProgress()
                continue
            }
            if (!seen.add(canonical)) continue
            examined++
            val children = runCatching { directory.listFiles() }.getOrNull()
            if (children == null) {
                unreadable++
                reportProgress()
                continue
            }
            if (looksLikeGameRoot(children)) {
                val detection = runCatching { EngineDetector.detect(directory) }.getOrNull()
                if (detection != null) {
                    found++
                    listener.onFound(GameCandidate(directory, detection))
                    // A recognized game tree is one game; its interior is not
                    // searched for further games.
                    reportProgress()
                    continue
                }
            }
            if (depth < maxDepth) {
                children.forEach { child ->
                    val isDirectory = runCatching { child.isDirectory }.getOrDefault(false)
                    if (isDirectory && !child.name.startsWith(".")) queue.add(child to depth + 1)
                }
            }
            reportProgress()
        }
        listener.onFinished(examined, found, stoppedEarly, unreadable)
    }

    companion object {
        private const val MAX_DEPTH = 6
        private const val MAX_DIRECTORIES = 10_000
        private const val PROGRESS_EVERY = 25

        private val markerFiles = setOf(
            "game.ini", "project.godot", "rpg_rt.ldb", "rpg_rt.exe", "data.xp3", "startup.tjs",
            "data01000.arc", "gameassembly.dll",
        )
        private val markerExtensions = setOf(
            "rpa", "rpyc", "rgssad", "rgss2a", "rgss3a", "cst", "ps3", "ps2", "swf", "xp3", "pck",
        )
        private val markerJsCores = setOf("rpg_core.js", "rmmz_core.js", "main.js")

        /**
         * Name-only screening over one directory listing. It may say yes to a
         * non-game (detection then says no and the walk continues); its only
         * job is to keep the full-tree detector off directories with no
         * engine evidence at all.
         */
        fun looksLikeGameRoot(children: Array<File>): Boolean {
            var renpyDir = false
            var gameDir = false
            for (child in children) {
                val name = child.name.lowercase()
                val isDirectory = runCatching { child.isDirectory }.getOrDefault(false)
                if (isDirectory) {
                    when (name) {
                        "renpy" -> renpyDir = true
                        "game" -> gameDir = true
                        "js" -> {
                            val scripts = runCatching { child.list() }.getOrNull().orEmpty()
                            if (scripts.any { it.lowercase() in markerJsCores }) return true
                        }
                    }
                    if (name.endsWith("_data")) return true
                    continue
                }
                if (name in markerFiles) return true
                val extension = name.substringAfterLast('.', "")
                if (extension in markerExtensions) return true
                if (extension == "html" || extension == "htm") return true
            }
            return renpyDir && gameDir
        }
    }
}
