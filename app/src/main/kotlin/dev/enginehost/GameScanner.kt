package dev.enginehost

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a scan keeps what it learned, so the next one only revisits what
 * changed and an interrupted one picks up where it stopped. The app's
 * implementation is SQLite ([LibraryDb]); [MemoryScanStore] serves tests.
 */
interface ScanStore {
    /** Path to directory signature for every game already found under a root, and for every folder looked at and found not to be one. */
    class Known(val games: Map<String, Long>, val negatives: Map<String, Long>)

    fun known(root: String): Known

    fun beginRun(root: String): Long

    /** Persists one batch: [findings] are new or changed, [unchanged] only confirms a known game is still there. */
    fun save(runId: Long, findings: List<GameFinding>, unchanged: List<String>, negatives: List<Pair<String, Long>>)

    fun remove(paths: List<String>)

    /** A complete run also forgets what it no longer finds under [root]; an interrupted one keeps everything. */
    fun finishRun(runId: Long, root: String, complete: Boolean)
}

class MemoryScanStore : ScanStore {
    val games = LinkedHashMap<String, GameFinding>()
    val negatives = HashMap<String, Long>()
    private val seen = HashMap<String, Long>()
    private var runs = 0L

    @Synchronized
    override fun known(root: String): ScanStore.Known = ScanStore.Known(
        games.filterKeys { under(root, it) }.mapValues { it.value.signature },
        negatives.filterKeys { under(root, it) },
    )

    @Synchronized
    override fun beginRun(root: String): Long = ++runs

    @Synchronized
    override fun save(runId: Long, findings: List<GameFinding>, unchanged: List<String>, negatives: List<Pair<String, Long>>) {
        findings.forEach {
            games[it.path] = it
            seen[it.path] = runId
        }
        unchanged.forEach { seen[it] = runId }
        negatives.forEach { this.negatives[it.first] = it.second }
    }

    @Synchronized
    override fun remove(paths: List<String>) {
        paths.forEach {
            games.remove(it)
            seen.remove(it)
        }
    }

    @Synchronized
    override fun finishRun(runId: Long, root: String, complete: Boolean) {
        if (!complete) return
        val gone = games.keys.filter { under(root, it) && seen[it] != runId }
        gone.forEach {
            games.remove(it)
            seen.remove(it)
        }
    }

    private fun under(root: String, path: String) = path == root || path.startsWith(root.trimEnd('/') + "/")
}

/**
 * Finds the game trees beneath a chosen root, built for a library of
 * thousands of games on a handheld:
 *
 * - **Parallel but bounded.** A small fixed pool of low-priority threads
 *   walks the tree breadth first; every callback to the [Listener] is
 *   serialized, so a caller needs no locking of its own.
 * - **Headers only.** A folder costs one listing with one attribute read per
 *   child, and a recognised or plausible game a few 64-byte header reads
 *   ([NativeBuilds], [EngineDetector]); no file is ever read whole.
 * - **Incremental and resumable.** Each folder is fingerprinted by its
 *   contents' names, sizes and modification times. A folder whose
 *   fingerprint matches what [store] already holds is not analysed again,
 *   and results are saved in batches as the walk goes, so a stopped scan
 *   loses nothing and the next one skips what is done.
 * - **One game once.** A game root is never searched for further games
 *   inside it, and an archive is dropped when the folder it unpacks to sits
 *   beside it.
 *
 * Shared storage is served through a FUSE layer that has hung before on
 * file names legal on ext4 but not on exFAT, so every filesystem touch is
 * guarded, unreadable folders are counted and skipped, and [cancel] stops
 * the walk between folders.
 */
class GameScanner(
    rows: List<EngineRow>,
    private val maxDepth: Int = MAX_DEPTH,
    private val maxDirectories: Int = MAX_DIRECTORIES,
    private val workers: Int = DEFAULT_WORKERS,
    private val store: ScanStore = MemoryScanStore(),
    /** Test seam for deterministic unreadable-directory coverage. */
    private val lister: (File) -> List<DirEntry>? = DirEntries::list,
) {
    private val analyzer = FolderAnalyzer(rows)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    data class Summary(
        val directoriesExamined: Int,
        val found: Int,
        /** Of [found], games a previous scan already knew and that had not changed. */
        val unchanged: Int,
        val stoppedEarly: Boolean,
        val unreadable: Int,
    )

    /** Called one at a time, from whichever scan thread has something to say. */
    interface Listener {
        fun onProgress(directoriesExamined: Int, found: Int)

        /** A new or changed game; unchanged ones are only counted. */
        fun onFound(finding: GameFinding)

        fun onFinished(summary: Summary)
    }

    private class PendingArchive(val path: String, val parent: String, val key: String, val finding: GameFinding?)

    /** [full] ignores what the store knows, so every folder is analysed again. */
    fun scan(root: File, listener: Listener, full: Boolean = false) {
        val rootPath = root.path
        val known = if (full) ScanStore.Known(emptyMap(), emptyMap()) else store.known(rootPath)
        val runId = store.beginRun(rootPath)

        val lock = Any()
        val examined = AtomicInteger()
        val foundCount = AtomicInteger()
        val unchangedCount = AtomicInteger()
        val unreadable = AtomicInteger()
        val outstanding = AtomicInteger()
        val stopped = AtomicBoolean()
        val done = CountDownLatch(1)
        val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val archives = ConcurrentLinkedQueue<PendingArchive>()
        val gameKeys = ConcurrentHashMap<String, MutableSet<String>>()

        val batchFindings = ArrayList<GameFinding>()
        val batchUnchanged = ArrayList<String>()
        val batchNegatives = ArrayList<Pair<String, Long>>()

        fun flush() {
            if (batchFindings.isEmpty() && batchUnchanged.isEmpty() && batchNegatives.isEmpty()) return
            runCatching { store.save(runId, batchFindings.toList(), batchUnchanged.toList(), batchNegatives.toList()) }
            batchFindings.clear()
            batchUnchanged.clear()
            batchNegatives.clear()
        }

        fun overBatch() = batchFindings.size + batchUnchanged.size + batchNegatives.size >= BATCH

        // Reported on every path through a visit, including the early returns for a
        // game root, an unreadable folder and an unresolvable path, so the count a
        // listener hears always matches the folders actually examined. Each thread
        // reports the value it got, so no multiple of PROGRESS_EVERY is skipped.
        fun progress(n: Int) {
            if (n % PROGRESS_EVERY == 0) synchronized(lock) { listener.onProgress(n, foundCount.get()) }
        }

        fun noteGameFolder(dir: File) {
            val parent = dir.parent ?: return
            gameKeys.computeIfAbsent(parent) { ConcurrentHashMap.newKeySet() }.add(ArchiveFiles.releaseKey(dir.name))
        }

        fun collectArchives(dir: File, entries: List<DirEntry>) {
            for (entry in entries) {
                if (!ArchiveFiles.candidate(entry)) continue
                val path = File(dir, entry.name).path
                val key = ArchiveFiles.releaseKey(ArchiveFiles.stem(entry.name))
                val signature = analyzer.archiveSignature(entry)
                if (known.games[path] == signature) {
                    archives += PendingArchive(path, dir.path, key, null)
                    continue
                }
                val magic = runCatching {
                    RandomAccessFile(File(dir, entry.name), "r").use { ArchiveFiles.hasMagic(RandomAccessFileReadAt(it)) }
                }.getOrDefault(false)
                if (magic) archives += PendingArchive(path, dir.path, key, analyzer.archive(dir, entry))
            }
        }

        lateinit var submit: (File, Int) -> Unit

        fun visit(dir: File, depth: Int) {
            if (cancelled || examined.get() >= maxDirectories) {
                stopped.set(true)
                return
            }
            val canonical = runCatching { dir.canonicalPath }.getOrNull()
            if (canonical == null) {
                // Could not even resolve the path (broken symlink, FUSE hiccup). The scanner still
                // attempted this folder, so it counts like any other unreadable entry.
                val n = examined.incrementAndGet()
                unreadable.incrementAndGet()
                progress(n)
                return
            }
            if (!seen.add(canonical)) return
            val n = examined.incrementAndGet()
            try {
                val entries = lister(dir)
                if (entries == null) {
                    unreadable.incrementAndGet()
                    return
                }
                val signature = DirEntries.signature(entries, analyzer.salt)
                val path = dir.path
                if (known.games[path] == signature) {
                    foundCount.incrementAndGet()
                    unchangedCount.incrementAndGet()
                    noteGameFolder(dir)
                    synchronized(lock) {
                        batchUnchanged += path
                        if (overBatch()) flush()
                    }
                    return
                }
                if (known.negatives[path] != signature) {
                    val finding = runCatching { analyzer.analyze(dir, entries, signature) }.getOrNull()
                    if (finding != null) {
                        foundCount.incrementAndGet()
                        noteGameFolder(dir)
                        synchronized(lock) {
                            batchFindings += finding
                            listener.onFound(finding)
                            if (overBatch()) flush()
                        }
                        // A recognised game tree is one game; its interior is not searched for more.
                        return
                    }
                }
                // Cached or not, a folder that is not a game is written again, which is what keeps it from being swept.
                synchronized(lock) {
                    batchNegatives += path to signature
                    if (overBatch()) flush()
                }
                collectArchives(dir, entries)
                if (depth < maxDepth) {
                    for (child in entries) {
                        if (child.isDirectory && !skipped(child.name, depth)) submit(File(dir, child.name), depth + 1)
                    }
                }
            } finally {
                progress(n)
            }
        }

        val pool = ThreadPoolExecutor(
            workers, workers, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
            { runnable ->
                Thread(runnable, "enginehost-scan").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY + 1
                }
            },
        )
        submit = { dir, depth ->
            outstanding.incrementAndGet()
            try {
                pool.execute {
                    try {
                        visit(dir, depth)
                    } catch (failure: Throwable) {
                        unreadable.incrementAndGet()
                    } finally {
                        if (outstanding.decrementAndGet() == 0) done.countDown()
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                if (outstanding.decrementAndGet() == 0) done.countDown()
            }
        }

        submit(root, 0)
        done.await()
        pool.shutdown()

        // Archives are decided last: whether the folder one unpacks to is also a game is only known now.
        val dropped = ArrayList<String>()
        for (archive in archives) {
            if (gameKeys[archive.parent]?.contains(archive.key) == true) {
                dropped += archive.path
                continue
            }
            foundCount.incrementAndGet()
            if (archive.finding != null) {
                synchronized(lock) {
                    batchFindings += archive.finding
                    listener.onFound(archive.finding)
                    if (overBatch()) flush()
                }
            } else {
                unchangedCount.incrementAndGet()
                synchronized(lock) { batchUnchanged += archive.path }
            }
        }
        synchronized(lock) { flush() }
        if (dropped.isNotEmpty()) runCatching { store.remove(dropped) }
        runCatching { store.finishRun(runId, rootPath, complete = !stopped.get() && !cancelled) }
        synchronized(lock) {
            listener.onFinished(
                Summary(examined.get(), foundCount.get(), unchangedCount.get(), stopped.get() || cancelled, unreadable.get()),
            )
        }
    }

    companion object {
        private const val MAX_DEPTH = 8
        private const val MAX_DIRECTORIES = 250_000
        private const val PROGRESS_EVERY = 25
        private const val BATCH = 200

        /** Enough to overlap storage latency, few enough not to starve a handheld's game or UI. */
        val DEFAULT_WORKERS: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 3)

        private val SKIPPED_DIRECTORIES = setOf("\$recycle.bin", "system volume information", "__macosx", "node_modules", "lost.dir")

        /** Hidden folders, trash, and the app-data tree at the top of shared storage hold no games a person put there. */
        fun skipped(name: String, depth: Int): Boolean {
            val lower = name.lowercase()
            return name.startsWith(".") || lower in SKIPPED_DIRECTORIES || (depth == 0 && lower == "android")
        }
    }
}
