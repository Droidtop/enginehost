package dev.enginehost

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList

/** The bytes a game takes, found by walking it once; bounded so a pathological tree cannot hold the walk forever. */
object FolderSize {
    const val MAX_ENTRIES = 200_000

    fun measure(target: File, cancelled: () -> Boolean = { false }): Long {
        if (!target.exists()) return 0L
        if (target.isFile) return target.length()
        var total = 0L
        var entries = 0
        runCatching {
            Files.walkFileTree(
                target.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        total += attrs.size()
                        return if (++entries > MAX_ENTRIES || cancelled()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    // A name the storage layer cannot serve is skipped, never fatal.
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        return total
    }
}

/**
 * The scan, held by the process rather than by a screen: leaving the scan
 * screen (or turning the device) does not stop it, the screen reattaches
 * to whatever is running, and a stopped or killed scan resumes cheaply
 * because [GameLibraryStore] keeps what it learned. Two phases on one
 * background thread: the walk, then sizes for the games it found (a size
 * needs a walk of the whole game, which is why it comes after the list is
 * usable, and why sorting by size fills in as it goes).
 */
object ScanController {
    enum class Phase { IDLE, SCANNING, MEASURING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val root: String? = null,
        val directoriesExamined: Int = 0,
        val found: Int = 0,
        val measured: Int = 0,
        /** Set when a walk ends; null while one is running or none has run. */
        val summary: GameScanner.Summary? = null,
    )

    fun interface Observer {
        fun onScanState(state: State)
    }

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private var scanner: GameScanner? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    var state: State = State()
        private set

    fun observe(observer: Observer) {
        observers += observer
        observer.onScanState(state)
    }

    fun unobserve(observer: Observer) {
        observers -= observer
    }

    val running: Boolean get() = state.phase != Phase.IDLE

    private fun publish(next: State) {
        state = next
        main.post { observers.forEach { it.onScanState(next) } }
    }

    /** Starts walking [root]; [full] ignores everything already learned. A scan already running is left alone. */
    @Synchronized
    fun start(context: Context, root: File, full: Boolean = false) {
        if (running) return
        stopRequested = false
        val app = context.applicationContext
        val rootPath = root.path
        publish(State(Phase.SCANNING, rootPath))
        Thread {
            val library = GameLibraryStore(app)
            val active = GameScanner(EngineRegistryStore.rows(app), store = library)
            synchronized(this) { scanner = active }
            if (stopRequested) active.cancel()
            var summary: GameScanner.Summary? = null
            runCatching {
                active.scan(
                    root,
                    object : GameScanner.Listener {
                        override fun onProgress(directoriesExamined: Int, found: Int) {
                            publish(State(Phase.SCANNING, rootPath, directoriesExamined, found))
                        }

                        override fun onFound(finding: GameFinding) = Unit

                        override fun onFinished(summary: GameScanner.Summary) {
                            publish(State(Phase.SCANNING, rootPath, summary.directoriesExamined, summary.found, summary = summary))
                        }
                    },
                    full,
                )
            }
            summary = state.summary
            // A stopped scan does not go on to measure sizes.
            if (!stopRequested) runCatching { measure(library, rootPath, summary) }
            synchronized(this) { scanner = null }
            publish(State(Phase.IDLE, rootPath, state.directoriesExamined, state.found, state.measured, summary))
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
            name = "enginehost-scan-control"
        }.start()
    }

    /** Stops the walk, or the size pass, between folders. What was found so far is kept. */
    @Synchronized
    fun stop() {
        stopRequested = true
        scanner?.cancel()
    }

    private fun measure(library: GameLibraryStore, root: String, summary: GameScanner.Summary?) {
        var measured = 0
        val attempted = HashSet<String>()
        publish(State(Phase.MEASURING, root, summary?.directoriesExamined ?: 0, summary?.found ?: 0, 0, summary))
        while (!stopRequested) {
            val batch = library.unmeasured(root, MEASURE_BATCH).filter { attempted.add(it) }
            if (batch.isEmpty()) break
            for (path in batch) {
                if (stopRequested) return
                library.setSize(path, FolderSize.measure(File(path)) { stopRequested })
                measured++
                if (measured % 5 == 0) publish(State(Phase.MEASURING, root, summary?.directoriesExamined ?: 0, summary?.found ?: 0, measured, summary))
            }
        }
        publish(State(Phase.MEASURING, root, summary?.directoriesExamined ?: 0, summary?.found ?: 0, measured, summary))
    }

    private const val MEASURE_BATCH = 100
}
