package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Bulk library building: walk a chosen folder, surface every game tree
 * [EngineDetector] recognizes, and let the user add the ones they want.
 *
 * Exported as `dev.enginehost.SCAN` (optional "path" extra) so callers such
 * as droidtop can open a scan rooted at a folder they already know about.
 */
class GameScanActivity : EnginehostActivity() {
    /** Adding what the scan found, once it found something; choosing a folder before that. */
    override fun primaryAction(): View? =
        findViewById<View>(R.id.addAllButton)?.takeIf { it.isShown } ?: findViewById(R.id.chooseScanFolderButton)

    private lateinit var library: GameLibraryStore
    private lateinit var chooseButton: Button
    private lateinit var rootLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var cancelButton: Button
    private lateinit var addAllButton: Button
    private lateinit var resultList: ViewGroup

    private var scanner: GameScanner? = null
    private var lastExamined = 0
    private val candidates = mutableListOf<GameCandidate>()
    private val addedPaths = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.scan_title)
        library = GameLibraryStore(this)
        setContentView(R.layout.activity_game_scan)
        wireBackButton()
        chooseButton = findViewById(R.id.chooseScanFolderButton)
        rootLabel = findViewById(R.id.scanRoot)
        statusText = findViewById(R.id.scanStatus)
        cancelButton = findViewById(R.id.cancelScanButton)
        addAllButton = findViewById(R.id.addAllButton)
        resultList = findViewById(R.id.resultList)

        chooseButton.setOnClickListener { openFolderPickerWhenAllowed() }
        cancelButton.setOnClickListener { scanner?.cancel() }
        addAllButton.setOnClickListener {
            candidates.forEach { candidate -> addCandidate(candidate) }
            renderAll()
        }

        intent.getStringExtra(EXTRA_PATH)?.let { path ->
            val folder = File(path).absoluteFile
            if (folder.isDirectory) startScan(folder)
        }
    }

    override fun onDestroy() {
        scanner?.cancel()
        super.onDestroy()
    }

    private fun openFolderPickerWhenAllowed() {
        if (StorageFolder.hasNativePathAccess()) {
            startActivityForResult(
                StorageFolder.pickerIntent(GameBrowserStartStore(this).initialUri()),
                REQUEST_SCAN_FOLDER,
            )
        } else {
            StorageFolder.requestNativePathAccess(this, REQUEST_NATIVE_FILES)
        }
    }

    @Deprecated("Uses the platform folder picker result API available at the app's minimum SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_NATIVE_FILES) {
            if (StorageFolder.hasNativePathAccess()) openFolderPickerWhenAllowed()
            else Toast.makeText(this, R.string.needs_native_access, Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode != REQUEST_SCAN_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val folder = StorageFolder.absolutePath(uri)
        if (folder == null) {
            Toast.makeText(this, R.string.choose_shared_storage_folder, Toast.LENGTH_LONG).show()
            return
        }
        startScan(folder)
    }

    private fun startScan(root: File) {
        scanner?.cancel()
        candidates.clear()
        addedPaths.clear()
        resultList.removeAllViews()
        addAllButton.visibility = View.GONE
        rootLabel.text = root.absolutePath
        rootLabel.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.scan_running, 0, 0)
        cancelButton.visibility = View.VISIBLE
        chooseButton.isEnabled = false
        val activeScanner = GameScanner(EngineRegistryStore.rows(this))
        scanner = activeScanner
        Thread {
            activeScanner.scan(root, object : GameScanner.Listener {
                override fun onProgress(directoriesExamined: Int, found: Int) {
                    runOnUiThread {
                        if (scanner !== activeScanner) return@runOnUiThread
                        lastExamined = directoriesExamined
                        statusText.text = getString(R.string.scan_running, directoriesExamined, found)
                    }
                }

                override fun onFound(candidate: GameCandidate) {
                    runOnUiThread {
                        if (scanner !== activeScanner) return@runOnUiThread
                        candidates += candidate
                        statusText.text = getString(R.string.scan_running, lastExamined, candidates.size)
                        addResultRow(candidate)
                        if (candidates.size > 1) addAllButton.visibility = View.VISIBLE
                    }
                }

                override fun onFinished(directoriesExamined: Int, found: Int, stoppedEarly: Boolean, unreadable: Int) {
                    runOnUiThread {
                        if (scanner !== activeScanner) return@runOnUiThread
                        chooseButton.isEnabled = true
                        cancelButton.visibility = View.GONE
                        statusText.text = when {
                            stoppedEarly -> getString(R.string.scan_stopped, directoriesExamined, found)
                            unreadable > 0 ->
                                getString(R.string.scan_finished_skipped, directoriesExamined, found, unreadable)
                            else -> getString(R.string.scan_finished, directoriesExamined, found)
                        }
                        if (found == 0) {
                            val empty = layoutInflater.inflate(R.layout.item_hint, resultList, false) as TextView
                            empty.setText(R.string.scan_empty)
                            resultList.addView(empty)
                        }
                    }
                }
            })
        }.start()
    }

    private fun addResultRow(candidate: GameCandidate) {
        val card = layoutInflater.inflate(R.layout.item_scan_result, resultList, false)
        bindResultRow(card, candidate)
        resultList.addView(card)
    }

    private fun renderAll() {
        candidates.forEachIndexed { index, candidate ->
            resultList.getChildAt(index)?.let { bindResultRow(it, candidate) }
        }
    }

    private fun bindResultRow(card: View, candidate: GameCandidate) {
        val detection = candidate.detection
        card.findViewById<TextView>(R.id.scanResultTitle).text = candidate.folder.name
        card.findViewById<TextView>(R.id.scanResultDetection).text = if (detection.hosted) {
            buildString {
                append(detection.engine)
                detection.engineContext?.let { append(' ').append(it) }
                detection.engineVersion?.let { append(' ').append(it) }
                append(" · ").append(detection.evidence)
            }
        } else {
            UnhostedEngine.explain(this, detection)
        }
        card.findViewById<TextView>(R.id.scanResultPath).text = candidate.folder.absolutePath
        val note = card.findViewById<TextView>(R.id.scanResultNote)
        val alreadyKnown = library.games().any { it.path == candidate.folder.path }
        val added = alreadyKnown || candidate.folder.path in addedPaths
        if (added && !configComplete(candidate)) {
            note.setText(R.string.scan_needs_config)
            note.visibility = View.VISIBLE
        }
        card.findViewById<Button>(R.id.scanResultAdd).apply {
            setText(if (added) R.string.scan_added else R.string.scan_add)
            isEnabled = !added
            setOnClickListener {
                addCandidate(candidate)
                bindResultRow(card, candidate)
            }
        }
    }

    private fun addCandidate(candidate: GameCandidate) {
        if (candidate.folder.path in addedPaths) return
        library.remember(candidate.folder)
        addedPaths += candidate.folder.path
    }

    /**
     * Whether Play can start this game as it is: the folder has its own
     * config, or detection answered every required field (the launch then
     * runs on the detected config in memory; see DetectedConfig). Adding a
     * game writes nothing into its folder.
     */
    private fun configComplete(candidate: GameCandidate): Boolean =
        File(candidate.folder, CONFIG_FILE_NAME).isFile ||
            DetectedConfig.documentFor(candidate.detection, candidate.folder.name, null) != null

    companion object {
        const val ACTION_SCAN = "dev.enginehost.SCAN"
        const val EXTRA_PATH = "path"
        private const val REQUEST_SCAN_FOLDER = 40
        private const val REQUEST_NATIVE_FILES = 41
    }
}
