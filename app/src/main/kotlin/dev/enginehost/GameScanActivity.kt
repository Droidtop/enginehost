package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Bulk library building: walk a chosen folder, find every game tree and
 * archive in it, and let the person filter what was found and add the
 * games they want. The results are the shared filterable list
 * ([LibraryBrowser]) over what [ScanController] stores, so a large library
 * is browsed, not scrolled through, and a rescan only revisits what changed.
 *
 * Exported as `dev.enginehost.SCAN` (optional "path" extra) so callers such
 * as droidtop can open a scan rooted at a folder they already know about.
 */
class GameScanActivity : EnginehostActivity() {
    /** The first result once there is one; choosing a folder before that. */
    override fun primaryAction(): View? =
        findViewById<View>(R.id.gameGrid)?.takeIf { ::browser.isInitialized && browser.shownRows().isNotEmpty() }
            ?: findViewById(R.id.chooseScanFolderButton)

    private lateinit var library: GameLibraryStore
    private lateinit var browser: LibraryBrowser
    private lateinit var chooseButton: Button
    private lateinit var rootLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: View
    private lateinit var cancelButton: Button
    private lateinit var addAllButton: Button

    private var root: String? = null
    private var lastPhase = ScanController.Phase.IDLE

    private val observer = ScanController.Observer { state -> onScanState(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.scan_title)
        library = GameLibraryStore(this)
        setContentView(R.layout.activity_game_scan)
        wireBackButton()
        chooseButton = findViewById(R.id.chooseScanFolderButton)
        rootLabel = findViewById(R.id.scanRoot)
        statusText = findViewById(R.id.scanStatus)
        progress = findViewById(R.id.scanProgress)
        cancelButton = findViewById(R.id.cancelScanButton)
        addAllButton = findViewById(R.id.addAllButton)
        browser = LibraryBrowser(
            activity = this,
            library = library,
            grid = findViewById<GridView>(R.id.gameGrid),
            searchField = findViewById<EditText>(R.id.gameSearch),
            filterButton = findViewById<Button>(R.id.gameFilterButton),
            summary = findViewById<TextView>(R.id.gameSummary),
            empty = findViewById<TextView>(R.id.gameEmpty),
            initial = LibraryFilter(scope = LibraryScope.UNDER_ROOT, sort = SortOrder.NAME),
            emptyText = R.string.scan_empty,
            rowMark = { row ->
                when {
                    row.kind == FindingKind.ARCHIVE -> 0
                    row.added -> R.string.scan_added
                    else -> R.string.scan_add
                }
            },
            onLoaded = {
                updateAddAll()
                selectPrimaryAction()
                refreshHints()
            },
            onOpen = { row -> openRow(row) },
        )

        chooseButton.setOnClickListener { openFolderPickerWhenAllowed() }
        cancelButton.setOnClickListener { ScanController.stop() }
        addAllButton.setOnClickListener { addShown() }

        val requested = intent.getStringExtra(EXTRA_PATH)?.let { File(it).absoluteFile }?.takeIf { it.isDirectory }
        when {
            requested != null -> startScan(requested, full = false)
            ScanController.state.root != null -> showRoot(ScanController.state.root!!)
            else -> Thread {
                val last = library.lastScanRoot()
                runOnUiThread { if (!isDestroyed && last != null && root == null) showRoot(last) }
            }.apply { isDaemon = true }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        ScanController.observe(observer)
    }

    override fun onPause() {
        ScanController.unobserve(observer)
        super.onPause()
    }

    override fun onDestroy() {
        // The scan belongs to the process, not to this screen: it carries on, and the screen reattaches.
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    override fun hints(): List<Hint> {
        // The base class draws the hint row while the content is being set, before the list exists.
        if (!::browser.isInitialized) return super.hints()
        return super.hints() +
            Hint("X", R.string.hint_filters) { browser.showFilters() } +
            Hint("Y", R.string.hint_scan_menu) { showScanMenu() }
    }

    private fun showRoot(path: String) {
        root = path
        rootLabel.text = path
        rootLabel.visibility = View.VISIBLE
        browser.showRoot(path)
    }

    private fun startScan(folder: File, full: Boolean) {
        showRoot(folder.path)
        ScanController.start(this, folder, full)
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
        startScan(folder, full = false)
    }

    /** Everything the pad can do here that is not a row: the scan's own controls, all in one sheet. */
    private fun showScanMenu() {
        val sheet = Sheet(this).title(R.string.scan_menu_title)
        val running = ScanController.running
        if (running) {
            sheet.choice(getString(R.string.scan_cancel), tone = Sheet.Tone.DANGER) { ScanController.stop() }
        } else {
            sheet.choice(getString(R.string.scan_menu_choose)) { openFolderPickerWhenAllowed() }
            root?.let { current ->
                val folder = File(current)
                sheet.choice(getString(R.string.scan_menu_rescan), getString(R.string.scan_menu_rescan_detail)) {
                    startScan(folder, full = false)
                }
                sheet.choice(getString(R.string.scan_menu_rescan_all), getString(R.string.scan_menu_rescan_all_detail)) {
                    startScan(folder, full = true)
                }
            }
        }
        val addable = addableRows()
        if (addable.isNotEmpty()) {
            sheet.choice(getString(R.string.scan_add_shown, addable.size)) { addShown() }
        }
        sheet.show()
    }

    private fun onScanState(state: ScanController.State) {
        val running = state.phase != ScanController.Phase.IDLE
        chooseButton.isEnabled = !running
        cancelButton.visibility = if (running) View.VISIBLE else View.GONE
        progress.visibility = if (running) View.VISIBLE else View.GONE
        val summary = state.summary
        val text = when {
            state.phase == ScanController.Phase.SCANNING && summary == null ->
                getString(R.string.scan_running, state.directoriesExamined, state.found)
            state.phase == ScanController.Phase.MEASURING -> getString(R.string.scan_measuring, state.measured)
            summary == null -> null
            summary.stoppedEarly -> getString(R.string.scan_stopped, summary.directoriesExamined, summary.found)
            summary.unreadable > 0 ->
                getString(R.string.scan_finished_skipped, summary.directoriesExamined, summary.found, summary.unreadable)
            summary.unchanged > 0 ->
                getString(R.string.scan_finished_unchanged, summary.directoriesExamined, summary.found, summary.unchanged)
            else -> getString(R.string.scan_finished, summary.directoriesExamined, summary.found)
        }
        statusText.visibility = if (text == null) View.GONE else View.VISIBLE
        statusText.text = text
        if (state.root != null && state.root != root) showRoot(state.root)
        // Results arrive as the scan goes; a settled phase gets one exact reload.
        if (running) browser.reloadSoon() else if (lastPhase != ScanController.Phase.IDLE) browser.reload()
        lastPhase = state.phase
    }

    private fun addableRows(): List<GameRow> = browser.shownRows().filter { it.kind == FindingKind.FOLDER && !it.added }

    private fun updateAddAll() {
        val addable = addableRows()
        addAllButton.visibility = if (addable.isEmpty()) View.GONE else View.VISIBLE
        addAllButton.text = getString(R.string.scan_add_shown, addable.size)
    }

    private fun addShown() {
        val paths = addableRows().map { it.path }
        if (paths.isEmpty()) return
        Thread {
            library.addPaths(paths)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Toast.makeText(this, resources.getQuantityString(R.plurals.scan_added_count, paths.size, paths.size), Toast.LENGTH_SHORT).show()
                browser.reload()
            }
        }.apply { isDaemon = true }.start()
    }

    /** A row adds its game; one already added opens its own screen. An archive has nothing to add yet. */
    private fun openRow(row: GameRow) {
        when {
            row.kind == FindingKind.ARCHIVE -> Toast.makeText(this, R.string.archive_needs_unpack, Toast.LENGTH_LONG).show()
            row.added -> startActivity(GameActivity.intent(this, File(row.path)))
            else -> Thread {
                library.addPaths(listOf(row.path))
                runOnUiThread { if (!isDestroyed) browser.reload() }
            }.apply { isDaemon = true }.start()
        }
    }

    companion object {
        const val ACTION_SCAN = "dev.enginehost.SCAN"
        const val EXTRA_PATH = "path"
        private const val REQUEST_SCAN_FOLDER = 40
        private const val REQUEST_NATIVE_FILES = 41
    }
}
