package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Home, the Library destination: every game played here and the one way to
 * add more. The other destinations are the bar or rail ([DestinationBar]).
 * The library is the shared filterable list
 * ([LibraryBrowser]); a game's card opens that game's own screen
 * ([GameActivity]), Y on a card plays it straight away, and X opens the
 * filters.
 */
class MainActivity : EnginehostActivity() {
    override val destination = Destination.LIBRARY

    /** The first game in the library; the first step while there is none. */
    override fun primaryAction(): View? =
        findViewById<View>(R.id.gameGrid)?.takeIf { ::browser.isInitialized && browser.shownRows().isNotEmpty() }
            ?: findViewById<View>(R.id.emptyAddFolderButton)?.takeIf { it.isShown }
            ?: findViewById(R.id.addGamesButton)

    private lateinit var library: GameLibraryStore
    private lateinit var browser: LibraryBrowser
    private lateinit var grid: GridView

    /** The game page in the pane beside the list, on a window wide enough for both; null on a narrow one. */
    private var page: GamePage? = null
    private var pageView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        library = GameLibraryStore(this)
        grid = findViewById(R.id.gameGrid)
        browser = LibraryBrowser(
            activity = this,
            library = library,
            grid = grid,
            searchField = findViewById<EditText>(R.id.gameSearch),
            filterButton = findViewById<Button>(R.id.gameFilterButton),
            summary = findViewById<TextView>(R.id.gameSummary),
            empty = findViewById<TextView>(R.id.gameEmpty),
            initial = LibraryFilter(sort = SortOrder.NAME),
            emptyText = R.string.games_empty,
            onLoaded = {
                showEmptyState()
                showShelves()
                selectPrimaryAction()
                refreshHints()
            },
            onOpen = { row -> openGame(File(row.path)) },
        )
        savedInstanceState?.let { browser.restoreState(it) }
        if (resources.configuration.screenWidthDp >= SizeClass.EXPANDED_MIN_DP) setUpPane(savedInstanceState)

        findViewById<Button>(R.id.addGamesButton).setOnClickListener { chooseHowToAdd() }
        findViewById<Button>(R.id.emptyAddFolderButton).setOnClickListener {
            startActivity(Intent(this, GameScanActivity::class.java))
        }
        findViewById<Button>(R.id.emptyInstallCoreButton).setOnClickListener {
            startActivity(Intent(this, PluginCatalogActivity::class.java))
        }
        // Y is "play this game" only while a game has focus, so the hint
        // row follows focus.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> refreshHints() }
    }

    /** Turning the device or resizing the window recreates Home; the search, filters, sort and place in the list stay. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::browser.isInitialized) browser.saveState(outState)
        page?.game?.let { outState.putString(STATE_PANE_GAME, it.absolutePath) }
    }

    /**
     * From 840dp wide the list and the selected game's page sit side by side (tracker#234): choosing a
     * game moves the pane instead of pushing a screen. The pane is the same [GamePage] the narrow
     * window opens as [GameActivity].
     */
    private fun setUpPane(savedInstanceState: Bundle?) {
        val pane = findViewById<FrameLayout>(R.id.gamePane)
        pane.visibility = View.VISIBLE
        val view = layoutInflater.inflate(R.layout.activity_game, pane, false)
        // The list is still on screen, so the page needs no way back.
        view.findViewById<View>(R.id.backButton).visibility = View.GONE
        view.visibility = View.GONE
        pane.addView(view)
        pageView = view
        page = GamePage(this, view, onRemoved = {
            closePane()
            browser.reload()
        })
        savedInstanceState?.getString(STATE_PANE_GAME)?.let { showInPane(File(it), focus = false) }
    }

    private fun showInPane(game: File, focus: Boolean) {
        val shown = page ?: return
        shown.show(game)
        pageView?.visibility = View.VISIBLE
        findViewById<View>(R.id.gamePaneEmpty).visibility = View.GONE
        if (focus) shown.playButton.post { shown.playButton.requestFocus() }
    }

    private fun closePane() {
        page?.clear()
        pageView?.visibility = View.GONE
        findViewById<View>(R.id.gamePaneEmpty).visibility = View.VISIBLE
    }

    /** A game chosen from the list or a shelf: its page in the pane when there is one, else its own screen. */
    private fun openGame(game: File) {
        if (page != null) showInPane(game, focus = true) else startActivity(GameActivity.intent(this, game))
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    override fun hints(): List<Hint> {
        // The base class draws the hint row while the content is being set, before the list exists.
        if (!::browser.isInitialized) return super.hints()
        val hints = super.hints() + Hint("X", R.string.hint_filters) { browser.showFilters() } +
            Hint(START, R.string.add_games) { chooseHowToAdd() }
        // A game on a shelf takes Y as a library row does.
        (currentFocus?.tag as? ShelfGame)?.let { shelf -> return hints + Hint("Y", R.string.hint_play) { launchGame(File(shelf.path)) } }
        val focusedGame = browser.takeIf { currentFocus === grid }?.selectedRow()?.takeIf { it.kind == FindingKind.FOLDER }
            ?: return hints
        // A opens the game's own screen; Y skips it and plays.
        return hints + Hint("Y", R.string.hint_play) { launchGame(File(focusedGame.path)) }
    }

    override fun onResume() {
        super.onResume()
        page?.refresh()
        if (::browser.isInitialized) {
            browser.forgetSupport()
            browser.reload()
            classifyWaitingGames()
        }
        val check = PluginUpdateCheck(this)
        check.maybeRun { pending ->
            Async.onMain(this) {
                val appUpdate = check.newerAppVersionName()
                // Core updates and Enginehost's own update are two short notices, each
                // opening where it is dealt with (#620 E2: one long blue line read as an
                // empty button).
                val cores = findViewById<TextView>(R.id.updateNotice)
                val app = findViewById<TextView>(R.id.appUpdateNotice)
                val wasShowing = cores.visibility == View.VISIBLE || app.visibility == View.VISIBLE
                cores.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
                if (pending.isNotEmpty()) {
                    cores.text = resources.getQuantityString(R.plurals.plugin_updates_available, pending.size, pending.size)
                    cores.setOnClickListener { startActivity(Intent(this@MainActivity, PluginTrustActivity::class.java)) }
                }
                app.visibility = if (appUpdate == null) View.GONE else View.VISIBLE
                appUpdate?.let { version ->
                    app.text = getString(R.string.app_update_available, version)
                    app.setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, EnginehostSettingsActivity::class.java)
                                .putExtra(EnginehostSettingsActivity.EXTRA_APP_UPDATE, true),
                        )
                    }
                }
                if (findViewById<LinearLayout>(R.id.shelves).visibility != View.VISIBLE) grid.nextFocusUpId = upFromList()
                // The first pad stop when it appears on a Home the pad has not moved on yet (#620 E2).
                val notice = firstNotice()
                if (!wasShowing && notice != null && !padMoved) findViewById<View>(notice).requestFocus()
            }
        }
    }

    /** Whether the pad has moved focus on Home since it opened; a notice then never takes focus from it. */
    private var padMoved = false

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in DPAD_KEYS) padMoved = true
        return super.dispatchKeyEvent(event)
    }

    /** The first update notice showing, if any. */
    private fun firstNotice(): Int? = listOf(R.id.updateNotice, R.id.appUpdateNotice)
        .firstOrNull { findViewById<View>(it).visibility == View.VISIBLE }

    /**
     * Where Up from the list goes when no shelf is above it: the update
     * notice while it shows, else Add games. It pointed at the list itself,
     * so with no shelf the pad could not leave the list upwards (review,
     * 2026-10-16).
     */
    private fun upFromList(): Int = firstNotice() ?: R.id.addGamesButton

    /** The game a shelf card stands for, held as the card's tag so Y can play it. */
    private class ShelfGame(val path: String)

    /**
     * Home's shelves (tracker#236): Continue playing, the games played last, and Favourites, above
     * the list while nothing narrows it. A window under 480dp tall (a handheld held sideways) has
     * room for one, so it shows the first that has games. The pad reaches a shelf by pressing up
     * from the list.
     */
    private fun showShelves() {
        // A library a shelf could hold whole would show every game twice, on the shelf and in the
        // list under it (owner's test, 2026-10-24): it is the list alone until it outgrows a shelf.
        if (!browser.filter.unfiltered || browser.total <= LibraryBrowser.SHELF_SIZE) {
            drawShelves(null)
            return
        }
        browser.loadShelves { loaded -> drawShelves(loaded) }
    }

    private fun drawShelves(loaded: LibraryBrowser.Shelves?) {
        val container = findViewById<LinearLayout>(R.id.shelves)
        container.removeAllViews()
        grid.nextFocusUpId = upFromList()
        if (loaded == null || !browser.filter.unfiltered) {
            container.visibility = View.GONE
            return
        }
        val short = resources.configuration.screenHeightDp < SizeClass.SHORT_HEIGHT_DP
        val shelves = listOf(R.string.shelf_continue to loaded.continuePlaying, R.string.shelf_favourites to loaded.favourites)
            .filter { it.second.isNotEmpty() }
            .let { if (short) it.take(1) else it }
        shelves.forEach { (title, games) ->
            container.addView(
                (layoutInflater.inflate(R.layout.item_group_heading, container, false) as TextView).apply { setText(title) },
            )
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            games.forEach { game -> strip.addView(shelfCard(game, strip)) }
            container.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(strip)
                },
            )
            // Up from the list lands on the last shelf's first card.
            grid.nextFocusUpId = strip.getChildAt(0).id
        }
        container.visibility = if (shelves.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shelfCard(game: GameRow, parent: ViewGroup): View {
        val card = layoutInflater.inflate(R.layout.item_shelf_card, parent, false)
        card.id = View.generateViewId()
        card.tag = ShelfGame(game.path)
        card.contentDescription = getString(R.string.open_game_description, game.name)
        card.findViewById<TextView>(R.id.shelfTitle).text = game.name
        GameArt.bind(card.findViewById(R.id.shelfArt), game)
        card.findViewById<TextView>(R.id.shelfEngine).apply {
            val engine = game.engine
            visibility = if (engine == null) View.GONE else View.VISIBLE
            if (engine != null) {
                text = EngineNames.line(engine, game.engineContext)
                EngineHues.paintChip(this, engine)
            }
        }
        card.setOnClickListener { openGame(File(game.path)) }
        return card
    }

    /**
     * A library with no games at all leads to the next step instead of a blank list (tracker#241):
     * add a folder of games, and install a core when none is installed. The installed cores are
     * read off the main thread, so the line and the second button appear a moment after the first.
     */
    private fun showEmptyState() {
        val firstRun = browser.total == 0
        findViewById<View>(R.id.emptyActions).visibility = if (firstRun) View.VISIBLE else View.GONE
        val coresLine = findViewById<TextView>(R.id.emptyCoresLine)
        val droidtopLine = findViewById<View>(R.id.emptyDroidtopLine)
        val droidtopButton = findViewById<Button>(R.id.emptyDroidtopButton)
        if (!firstRun) {
            coresLine.visibility = View.GONE
            findViewById<View>(R.id.emptyInstallCoreButton).visibility = View.GONE
            droidtopLine.visibility = View.GONE
            droidtopButton.visibility = View.GONE
            return
        }
        Async.load(this, work = {
            val cores = runCatching { PluginRegistry.discover(applicationContext).size }.getOrDefault(0)
            cores to DroidtopOffer.offer(applicationContext)
        }) { (cores, offer) ->
            coresLine.visibility = View.VISIBLE
            coresLine.text = if (cores == 0) {
                getString(R.string.empty_no_cores)
            } else {
                resources.getQuantityString(R.plurals.empty_cores_installed, cores, cores)
            }
            findViewById<View>(R.id.emptyInstallCoreButton).visibility = if (cores == 0) View.VISIBLE else View.GONE
            val offered = offer != DroidtopOffer.Offer.NONE
            droidtopLine.visibility = if (offered) View.VISIBLE else View.GONE
            droidtopButton.visibility = if (offered) View.VISIBLE else View.GONE
            droidtopButton.text = DroidtopOffer.label(this, offer)
            droidtopButton.setOnClickListener { DroidtopOffer.act(this, offer) }
        }
    }

    /** Games added by hand have no scan behind them; read each folder once, off the main thread, and redraw. */
    private fun classifyWaitingGames() {
        Async.load(this, work = {
            // droidtop's engine games first (Droidtop/tracker#547), so they are classified with the rest.
            val registered = runCatching { DroidtopLibrary.syncIfDue(applicationContext) }.getOrDefault(false)
            val classified = LibraryClassifier.classifyPending(applicationContext)
            registered || classified
        }) { changed -> if (changed) browser.reload() }
    }

    @Deprecated("Uses the platform folder picker result API available at the app's minimum SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_NATIVE_FILES) {
            if (StorageFolder.hasNativePathAccess()) {
                startActivityForResult(gamePickerIntent(), REQUEST_GAME_FOLDER)
            } else {
                Toast.makeText(this, R.string.needs_native_access, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != REQUEST_GAME_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        val folder = StorageFolder.absolutePath(uri)
        if (folder == null) {
            Toast.makeText(this, R.string.choose_shared_storage_folder, Toast.LENGTH_LONG).show()
            return
        }
        Async.load(this, work = { library.remember(folder) }) {
            browser.reload()
            launchGame(folder)
        }
    }

    /**
     * The one way to add games (UI assessment 2026-09-24, H9): one game by
     * its folder, or every game found inside a folder. Both end on Home's
     * list; a single game also starts, as it always has.
     */
    private fun chooseHowToAdd() {
        Sheet(this)
            .title(R.string.add_games)
            .choice(getString(R.string.add_one_game), getString(R.string.add_one_game_detail)) {
                openGamePickerWhenAllowed()
            }
            .choice(getString(R.string.add_folder_of_games), getString(R.string.add_folder_of_games_detail)) {
                startActivity(Intent(this, GameScanActivity::class.java))
            }
            .show()
    }

    private fun openGamePickerWhenAllowed() {
        if (StorageFolder.hasNativePathAccess()) {
            startActivityForResult(gamePickerIntent(), REQUEST_GAME_FOLDER)
        } else {
            StorageFolder.requestNativePathAccess(this, REQUEST_NATIVE_FILES)
        }
    }

    private fun gamePickerIntent(): Intent = StorageFolder.pickerIntent(
        GameBrowserStartStore(this).initialUri(),
    )

    private fun launchGame(folder: File) {
        if (!folder.isDirectory) {
            Toast.makeText(this, R.string.game_folder_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        GameRunner.run(this, folder)
    }

    private companion object {
        val DPAD_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        const val STATE_PANE_GAME = "pane.game"
        const val REQUEST_GAME_FOLDER = 10
        const val REQUEST_NATIVE_FILES = 11
    }
}
