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
 * Home: the library of every game played here, the one way to add more,
 * and the app's destinations. The library is the shared filterable list
 * ([LibraryBrowser]); a game's card opens that game's own screen
 * ([GameActivity]), Y on a card plays it straight away, and X opens the
 * filters.
 */
class MainActivity : EnginehostActivity() {
    /** The first game in the library; adding games while there is none. */
    override fun primaryAction(): View? =
        findViewById<View>(R.id.gameGrid)?.takeIf { ::browser.isInitialized && browser.shownRows().isNotEmpty() }
            ?: findViewById(R.id.addGamesButton)

    private lateinit var library: GameLibraryStore
    private lateinit var browser: LibraryBrowser
    private lateinit var grid: GridView

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
            initial = LibraryFilter(sort = SortOrder.RECENTLY_PLAYED),
            emptyText = R.string.games_empty,
            onLoaded = {
                selectPrimaryAction()
                refreshHints()
            },
            onOpen = { row -> startActivity(GameActivity.intent(this, File(row.path))) },
        )

        findViewById<Button>(R.id.addGamesButton).setOnClickListener { chooseHowToAdd() }
        findViewById<Button>(R.id.controllerConfigButton).setOnClickListener {
            startActivity(Intent(this, ControllerConfigActivity::class.java))
        }
        findViewById<Button>(R.id.enginehostSettingsButton).setOnClickListener {
            startActivity(Intent(this, EnginehostSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.pluginCatalogButton).setOnClickListener {
            startActivity(Intent(this, PluginTrustActivity::class.java))
        }
        // Y is "play this game" only while a game has focus, so the hint
        // row follows focus.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> refreshHints() }
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    override fun hints(): List<Hint> {
        // The base class draws the hint row while the content is being set, before the list exists.
        if (!::browser.isInitialized) return super.hints()
        val hints = super.hints() + Hint("X", R.string.hint_filters) { browser.showFilters() }
        val focusedGame = browser.takeIf { currentFocus === grid }?.selectedRow()?.takeIf { it.kind == FindingKind.FOLDER }
            ?: return hints
        // A opens the game's own screen; Y skips it and plays.
        return hints + Hint("Y", R.string.hint_play) { launchGame(File(focusedGame.path)) }
    }

    override fun onResume() {
        super.onResume()
        if (::browser.isInitialized) {
            browser.forgetSupport()
            browser.reload()
            classifyWaitingGames()
        }
        val check = PluginUpdateCheck(this)
        check.maybeRun { pending ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val appUpdate = check.newerAppVersionName()
                val lines = mutableListOf<String>()
                if (pending.isNotEmpty()) {
                    lines += resources.getQuantityString(
                        R.plurals.plugin_updates_available, pending.size, pending.size,
                    )
                }
                appUpdate?.let { lines += getString(R.string.app_update_available, it) }
                findViewById<TextView>(R.id.updateNotice).apply {
                    visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
                    if (lines.isNotEmpty()) {
                        text = lines.joinToString("\n")
                        setOnClickListener {
                            startActivity(
                                if (pending.isNotEmpty()) {
                                    Intent(this@MainActivity, PluginTrustActivity::class.java)
                                } else {
                                    Intent(this@MainActivity, EnginehostSettingsActivity::class.java)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Games added by hand have no scan behind them; read each folder once, off the main thread, and redraw. */
    private fun classifyWaitingGames() {
        Thread {
            if (LibraryClassifier.classifyPending(applicationContext)) runOnUiThread { if (!isDestroyed) browser.reload() }
        }.apply { isDaemon = true }.start()
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
        Thread {
            library.remember(folder)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                browser.reload()
                launchGame(folder)
            }
        }.apply { isDaemon = true }.start()
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
        const val REQUEST_GAME_FOLDER = 10
        const val REQUEST_NATIVE_FILES = 11
    }
}
