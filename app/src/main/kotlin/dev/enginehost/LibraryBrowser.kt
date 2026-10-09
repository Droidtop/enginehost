package dev.enginehost

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The filterable game list, shared by Home (the games a person added) and
 * the scan screen (everything a scan found under a folder): one mechanism,
 * two scopes.
 *
 * It is built for thousands of rows. The list is a recycling grid over rows
 * held in memory; every query, count and plugin lookup runs on one worker
 * thread, and a newer request supersedes an older one; drawing a row reads
 * only stored facts (see [GameRow]). Filters are the stored columns the
 * database indexes, so changing one is a query, not a walk of the library.
 *
 * Selection is the grid's own, so the D-pad steps through rows and pages
 * the list, A opens the selected row, and touch taps a row directly.
 */
class LibraryBrowser(
    private val activity: Activity,
    private val library: GameLibraryStore,
    private val grid: GridView,
    private val searchField: EditText,
    private val filterButton: Button,
    private val summary: TextView,
    private val empty: TextView,
    initial: LibraryFilter,
    private val emptyText: Int,
    /** The text on the right of a row ("Added"), or 0 for none; scan results use it, Home does not. */
    private val rowMark: (GameRow) -> Int = { 0 },
    /** Called on the main thread after each fresh list is on screen. */
    private val onLoaded: () -> Unit = {},
    private val onOpen: (GameRow) -> Unit,
) {
    var filter: LibraryFilter = initial
        private set

    /** How many games the scope holds before any filter; zero means the list is empty because there is nothing, not because nothing matched. */
    var total: Int = -1
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "enginehost-library").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()
    private val adapter = RowAdapter()
    private var lastReload = 0L
    private var reloadScheduled = false
    private var applyingText = false
    private val defaultSort = initial.sort
    private val reloadNow = Runnable { reload() }

    /** The row the list was scrolled to before the screen was recreated, applied once the first list is drawn. */
    private var pendingFirst: Int? = null

    init {
        grid.adapter = adapter
        // Columns follow the width the grid really has, now and after every resize or rotation.
        val minColumn = activity.resources.getDimensionPixelSize(R.dimen.eh_card_min_width)
        val gap = activity.resources.getDimensionPixelSize(R.dimen.eh_column_gap)
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val columns = SizeClass.columns(right - left - grid.paddingLeft - grid.paddingRight, minColumn, gap)
            if (columns != grid.numColumns) grid.numColumns = columns
        }
        grid.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ -> adapter.rowAt(position)?.let(onOpen) }
        filterButton.setOnClickListener { showFilters() }
        searchField.setText(initial.text)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingText) return
                filter = filter.copy(text = s?.toString().orEmpty().trim())
                // Typing is not a query per key: wait for the person to pause.
                main.removeCallbacks(reloadNow)
                main.postDelayed(reloadNow, SEARCH_DELAY_MS)
            }
        })
    }

    /** Runs [block] on the worker thread; once the screen is gone there is no worker and nothing to do. */
    private fun background(block: () -> Unit) {
        runCatching { worker.execute { block() } }
    }

    fun destroy() {
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    /** What a rotation or a window resize must not lose: the filters, the sort and the place in the list. */
    fun saveState(out: Bundle) {
        out.putString(STATE_ENGINE, filter.engine)
        out.putString(STATE_PLATFORM, filter.platform?.name)
        out.putString(STATE_SUPPORT, filter.support?.name)
        out.putString(STATE_FOLDER, filter.folder)
        out.putString(STATE_TEXT, filter.text)
        out.putString(STATE_SORT, filter.sort.name)
        out.putBoolean(STATE_FAVOURITES, filter.favourites)
        out.putInt(STATE_FIRST, grid.firstVisiblePosition)
    }

    /** Puts back what [saveState] kept; call before the first [reload]. */
    fun restoreState(state: Bundle) {
        filter = filter.copy(
            engine = state.getString(STATE_ENGINE),
            platform = state.getString(STATE_PLATFORM)?.let { name -> BuildPlatform.entries.firstOrNull { it.name == name } },
            support = state.getString(STATE_SUPPORT)?.let { name -> Support.entries.firstOrNull { it.name == name } },
            folder = state.getString(STATE_FOLDER),
            favourites = state.getBoolean(STATE_FAVOURITES),
            text = state.getString(STATE_TEXT).orEmpty(),
            sort = state.getString(STATE_SORT)?.let { name -> SortOrder.entries.firstOrNull { it.name == name } } ?: filter.sort,
        )
        setSearchText(filter.text)
        pendingFirst = state.getInt(STATE_FIRST, -1).takeIf { it > 0 }
    }

    /** The scanned folder this list shows, for the scan screen. */
    fun showRoot(root: String?) {
        filter = filter.copy(root = root)
        reload()
    }

    fun selectedRow(): GameRow? = adapter.rowAt(grid.selectedItemPosition)

    /** Every row the current filters show, for acting on all of them. */
    fun shownRows(): List<GameRow> = adapter.rows

    fun reload() {
        val gen = generation.incrementAndGet()
        val query = filter
        lastReload = System.currentTimeMillis()
        background {
            if (gen != generation.get()) return@background
            val result = runCatching {
                val all = library.query(query)
                val total = library.count(query.scope, query.root)
                val resolver = supportResolver()
                val supports = all.map { resolver.of(it) }
                if (query.support == null) {
                    Loaded(all, supports, total)
                } else {
                    val keep = all.indices.filter { supports[it] == query.support }
                    Loaded(keep.map { all[it] }, keep.map { supports[it] }, total)
                }
            }.getOrNull() ?: return@background
            if (gen != generation.get()) return@background
            activity.runOnUiThread {
                if (gen != generation.get() || activity.isDestroyed) return@runOnUiThread
                render(result)
            }
        }
    }

    /** The few games each of Home's shelves shows: the ones played last, and the ones marked as favourites. */
    class Shelves(val continuePlaying: List<GameRow>, val favourites: List<GameRow>)

    /** Reads the shelves on the worker thread and hands them to [onResult] on the main thread. */
    fun loadShelves(onResult: (Shelves) -> Unit) {
        background {
            val shelves = runCatching {
                Shelves(
                    continuePlaying = library.query(LibraryFilter(sort = SortOrder.RECENTLY_PLAYED), SHELF_SIZE).filter { it.playedAt > 0 },
                    favourites = library.query(LibraryFilter(favourites = true, sort = SortOrder.RECENTLY_PLAYED), SHELF_SIZE),
                )
            }.getOrNull() ?: return@background
            activity.runOnUiThread { if (!activity.isDestroyed) onResult(shelves) }
        }
    }

    /** For a list that changes while a scan runs: at most one reload per interval. */
    fun reloadSoon() {
        val wait = lastReload + RELOAD_INTERVAL_MS - System.currentTimeMillis()
        if (wait <= 0) {
            reload()
        } else if (!reloadScheduled) {
            reloadScheduled = true
            main.postDelayed({
                reloadScheduled = false
                reload()
            }, wait)
        }
    }

    /** Plugin discovery reads the disk, so a resolver is reused across the reloads a running scan causes. */
    @Volatile
    private var resolver: SupportResolver? = null

    @Volatile
    private var resolverAt = 0L

    private fun supportResolver(): SupportResolver {
        val now = System.currentTimeMillis()
        val current = resolver
        if (current != null && now - resolverAt < RESOLVER_MAX_AGE_MS) return current
        return SupportResolver(activity).also {
            resolver = it
            resolverAt = now
        }
    }

    /** Installed plugins may have changed (the person came back from the plugin screens): look again on the next load. */
    fun forgetSupport() {
        resolverAt = 0L
    }

    private class Loaded(val rows: List<GameRow>, val supports: List<Support>, val total: Int)

    private fun render(loaded: Loaded) {
        val keep = selectedRow()?.path
        total = loaded.total
        adapter.submit(loaded.rows, loaded.supports)
        if (loaded.total == 0) {
            summary.visibility = View.GONE
        } else {
            summary.visibility = View.VISIBLE
            summary.text = if (filter.unfiltered) {
                activity.getString(R.string.library_summary_all, loaded.total)
            } else {
                activity.getString(R.string.library_summary, loaded.rows.size, loaded.total)
            }
        }
        empty.visibility = if (loaded.rows.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (loaded.total == 0) emptyText else R.string.search_no_matches)
        filterButton.setText(if (filter.unfiltered && filter.sort == defaultSort) R.string.filter_button else R.string.filter_button_active)
        val index = keep?.let { path -> loaded.rows.indexOfFirst { it.path == path } } ?: -1
        val restored = pendingFirst
        pendingFirst = null
        if (restored != null && loaded.rows.isNotEmpty()) {
            grid.setSelection(restored.coerceAtMost(loaded.rows.size - 1))
        } else if (index >= 0) {
            grid.setSelection(index)
        }
        onLoaded()
    }

    // ---- Filters ----------------------------------------------------------------

    fun showFilters() {
        val f = filter
        val sheet = Sheet(activity).title(R.string.filter_title)
        sheet.choice(activity.getString(R.string.filter_search), f.text.ifBlank { null }) { pickSearch() }
        sheet.choice(activity.getString(R.string.filter_engine), engineName(f.engine)) { pickEngine() }
        sheet.choice(activity.getString(R.string.filter_platform), f.platform?.let { platformName(it) }) { pickPlatform() }
        sheet.choice(activity.getString(R.string.filter_status), f.support?.let { supportName(it) }) { pickSupport() }
        sheet.choice(activity.getString(R.string.filter_folder), f.folder?.let { folderName(it) }) { pickFolder() }
        sheet.choice(activity.getString(R.string.filter_sort), sortName(f.sort)) { pickSort() }
        if (f.scope == LibraryScope.ADDED) {
            sheet.choice(
                activity.getString(R.string.filter_favourites),
                activity.getString(if (f.favourites) R.string.filter_favourites_only else R.string.filter_favourites_all),
            ) {
                filter = filter.copy(favourites = !filter.favourites)
                reload()
            }
        }
        if (!f.unfiltered) {
            sheet.choice(activity.getString(R.string.filter_clear)) {
                filter = filter.copy(engine = null, platform = null, support = null, folder = null, text = "", favourites = false)
                setSearchText("")
                reload()
            }
        }
        sheet.show()
    }

    private fun setSearchText(text: String) {
        applyingText = true
        searchField.setText(text)
        applyingText = false
    }

    private fun pickSearch() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.search_hint)
            setSingleLine()
            setText(filter.text)
            setSelection(text.length)
        }
        Sheet(activity).title(R.string.filter_search).content(input).apply {
            choice(activity.getString(R.string.filter_apply)) {
                val text = input.text.toString().trim()
                filter = filter.copy(text = text)
                setSearchText(text)
                reload()
            }
            if (filter.text.isNotBlank()) {
                choice(activity.getString(R.string.filter_clear_search)) {
                    filter = filter.copy(text = "")
                    setSearchText("")
                    reload()
                }
            }
        }.show()
    }

    private fun pickEngine() {
        val scope = filter.scope
        val root = filter.root
        background {
            val counts = runCatching { library.engineCounts(scope, root) }.getOrDefault(emptyList())
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val sheet = Sheet(activity).title(R.string.filter_engine)
                sheet.choice(activity.getString(R.string.filter_all), current = filter.engine == null) { setEngine(null) }
                counts.forEach { (engine, count) ->
                    val key = engine ?: LibraryFilter.NO_ENGINE
                    sheet.choice(engineName(key) ?: key, "$count", current = filter.engine == key) { setEngine(key) }
                }
                sheet.show()
            }
        }
    }

    private fun setEngine(engine: String?) {
        filter = filter.copy(engine = engine)
        reload()
    }

    private fun pickPlatform() {
        val sheet = Sheet(activity).title(R.string.filter_platform)
        sheet.choice(activity.getString(R.string.filter_all), current = filter.platform == null) { setPlatform(null) }
        listOf(
            BuildPlatform.PORTABLE, BuildPlatform.WINDOWS, BuildPlatform.LINUX, BuildPlatform.WEB, BuildPlatform.ARCHIVE,
        ).forEach { platform ->
            sheet.choice(platformName(platform), current = filter.platform == platform) { setPlatform(platform) }
        }
        sheet.show()
    }

    private fun setPlatform(platform: BuildPlatform?) {
        filter = filter.copy(platform = platform)
        reload()
    }

    private fun pickSupport() {
        val sheet = Sheet(activity).title(R.string.filter_status)
        sheet.choice(activity.getString(R.string.filter_all), current = filter.support == null) { setSupport(null) }
        Support.values().forEach { support ->
            sheet.choice(supportName(support), current = filter.support == support) { setSupport(support) }
        }
        sheet.show()
    }

    private fun setSupport(support: Support?) {
        filter = filter.copy(support = support)
        reload()
    }

    private fun pickFolder() {
        val scope = filter.scope
        val root = filter.root
        background {
            val folders = runCatching { library.folderCounts(scope, root) }.getOrDefault(emptyList())
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val sheet = Sheet(activity).title(R.string.filter_folder)
                sheet.choice(activity.getString(R.string.filter_all), current = filter.folder == null) { setFolder(null) }
                folders.forEach { (path, count) ->
                    sheet.choice(folderName(path), "$count", current = filter.folder == path) { setFolder(path) }
                }
                sheet.show()
            }
        }
    }

    private fun setFolder(folder: String?) {
        filter = filter.copy(folder = folder)
        reload()
    }

    private fun pickSort() {
        val sheet = Sheet(activity).title(R.string.filter_sort)
        SortOrder.values().forEach { order ->
            sheet.choice(sortName(order), current = filter.sort == order) {
                filter = filter.copy(sort = order)
                reload()
            }
        }
        sheet.show()
    }

    // ---- Names ------------------------------------------------------------------

    private fun engineName(engine: String?): String? = when (engine) {
        null -> null
        LibraryFilter.NO_ENGINE -> activity.getString(R.string.engine_none)
        else -> EngineNames.family(engine)
    }

    private fun platformName(platform: BuildPlatform): String = activity.getString(
        when (platform) {
            BuildPlatform.PORTABLE -> R.string.platform_portable
            BuildPlatform.WINDOWS -> R.string.platform_windows
            BuildPlatform.LINUX -> R.string.platform_linux
            BuildPlatform.WEB -> R.string.platform_web
            BuildPlatform.ARCHIVE -> R.string.platform_archive
            BuildPlatform.UNKNOWN -> R.string.platform_unknown
        },
    )

    private fun supportName(support: Support): String = activity.getString(
        when (support) {
            Support.RUNS_HERE -> R.string.support_runs
            Support.NEEDS_PLUGIN -> R.string.support_plugin
            Support.NOT_SUPPORTED -> R.string.support_none
        },
    )

    private fun sortName(order: SortOrder): String = activity.getString(
        when (order) {
            SortOrder.NAME -> R.string.sort_name
            SortOrder.RECENTLY_ADDED -> R.string.sort_added
            SortOrder.RECENTLY_PLAYED -> R.string.sort_played
            SortOrder.SIZE -> R.string.sort_size
            SortOrder.PLAYTIME -> R.string.sort_playtime
        },
    )

    /** The last two segments of a path: enough to tell two "Games" folders apart without printing a path. */
    private fun folderName(path: String): String {
        val file = File(path)
        val parent = file.parentFile?.name
        return if (parent.isNullOrEmpty()) file.name.ifEmpty { path } else "$parent/${file.name}"
    }

    // ---- Rows -------------------------------------------------------------------

    private fun statusLine(row: GameRow, support: Support): String {
        val parts = ArrayList<String>(5)
        val builds = BuildPlatform.of(row.platforms).filter { it != BuildPlatform.WEB || row.platforms and BuildPlatform.PORTABLE.bit == 0 }
        parts += builds.joinToString("/") { platformName(it) }
        if (!row.hosted) {
            archLabel(row.architecture)?.let { parts += it }
            if (row.dotNet) parts += activity.getString(R.string.arch_dotnet)
        }
        parts += supportName(support)
        if (row.confidence != Confidence.HIGH && row.kind == FindingKind.FOLDER) parts += activity.getString(R.string.unconfirmed)
        if (row.sizeBytes >= 0) parts += Formatter.formatShortFileSize(activity, row.sizeBytes)
        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun archLabel(architecture: String?): String? = when (architecture) {
        "x86_64" -> activity.getString(R.string.arch_64)
        "x86" -> activity.getString(R.string.arch_32)
        "arm64" -> activity.getString(R.string.arch_arm64)
        "arm" -> activity.getString(R.string.arch_arm)
        "dos" -> activity.getString(R.string.arch_dos)
        else -> null
    }

    private fun chipText(row: GameRow): String {
        val engine = row.engine
        if (engine != null) {
            val line = EngineNames.line(engine, row.engineContext)
            return if (row.engineVersion.isNullOrBlank()) line else "$line ${row.engineVersion}"
        }
        val first = BuildPlatform.of(row.platforms).firstOrNull() ?: BuildPlatform.UNKNOWN
        return platformName(first)
    }

    private class Holder(view: View) {
        val title: TextView = view.findViewById(R.id.gameTitle)
        val status: TextView = view.findViewById(R.id.gameStatus)
        val chip: TextView = view.findViewById(R.id.gameEngine)
        val mark: TextView = view.findViewById(R.id.gameMark)
        val chevron: View = view.findViewById(R.id.gameChevron)
    }

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<GameRow> = emptyList()
            private set
        private var supports: List<Support> = emptyList()

        fun submit(newRows: List<GameRow>, newSupports: List<Support>) {
            rows = newRows
            supports = newSupports
            notifyDataSetChanged()
        }

        fun rowAt(position: Int): GameRow? = rows.getOrNull(position)

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_library_game, parent, false)
            val holder = (view.tag as? Holder) ?: Holder(view).also { view.tag = it }
            val row = rows[position]
            val support = supports[position]
            holder.title.text = row.name
            holder.status.text = statusLine(row, support)
            holder.status.setTextColor(
                ContextCompat.getColor(activity, if (support == Support.NEEDS_PLUGIN) R.color.eh_caution else R.color.eh_text_secondary),
            )
            holder.chip.text = chipText(row)
            EngineHues.paintChip(holder.chip, row.engine ?: "")
            val mark = rowMark(row)
            holder.mark.visibility = if (mark == 0) View.GONE else View.VISIBLE
            if (mark != 0) holder.mark.setText(mark)
            holder.chevron.visibility = if (mark == 0) View.VISIBLE else View.GONE
            view.contentDescription = activity.getString(R.string.open_game_description, row.name)
            return view
        }
    }

    companion object {
        private const val STATE_ENGINE = "library.engine"
        private const val STATE_PLATFORM = "library.platform"
        private const val STATE_SUPPORT = "library.support"
        private const val STATE_FOLDER = "library.folder"
        private const val STATE_TEXT = "library.text"
        private const val STATE_SORT = "library.sort"
        private const val STATE_FAVOURITES = "library.favourites"
        const val SHELF_SIZE = 6
        private const val STATE_FIRST = "library.first"
        private const val SEARCH_DELAY_MS = 250L
        private const val RELOAD_INTERVAL_MS = 1500L
        private const val RESOLVER_MAX_AGE_MS = 15_000L
    }
}
