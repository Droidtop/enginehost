package dev.enginehost

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

/**
 * One game's own screen, opened from its card on Home: its name, engine
 * and state, the cover plate as the content region, Play as the primary
 * action, and everything else that applies to this one game -- its setup,
 * a problem report, taking it off the list.
 *
 * Game setup lives here and nowhere on Home (UI assessment 2026-09-24,
 * H9): it is always about one game, so it is reached from that game.
 */
class GameActivity : EnginehostActivity() {
    /** Play. */
    override fun primaryAction(): View? =
        findViewById(R.id.playButton)

    private lateinit var folder: File

    /** What the primary button does; Play until the state has been read. */
    private var primary = GamePrimary.PLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        folder = File(path)
        setContentView(R.layout.activity_game)
        wireBackButton()
        wireFolderLine(findViewById(R.id.gamePath))
        findViewById<Button>(R.id.playButton).setOnClickListener {
            // Play, Get the core and Approve the core are all the launch: its plan sends the person
            // to the catalog or the trust screen when that is the next step.
            if (primary == GamePrimary.SET_UP) openSetup() else play()
        }
        findViewById<Button>(R.id.setupButton).setOnClickListener { openSetup() }
        findViewById<Button>(R.id.favouriteButton).setOnClickListener { toggleFavourite() }
        findViewById<Button>(R.id.reportButton).setOnClickListener {
            startActivity(ProblemReportActivity.intent(this, folder))
        }
        findViewById<Button>(R.id.removeButton).setOnClickListener { confirmRemove() }
    }

    private var favourite = false

    /** Marks or unmarks this game as a favourite; the database is written off the main thread. */
    private fun toggleFavourite() {
        val mark = !favourite
        showFavourite(mark)
        val library = GameLibraryStore(applicationContext)
        Thread { library.setFavourite(folder, mark) }.apply { isDaemon = true }.start()
    }

    private fun showFavourite(on: Boolean) {
        favourite = on
        findViewById<Button>(R.id.favouriteButton).setText(if (on) R.string.action_remove_favourite else R.string.action_add_favourite)
    }

    private fun openSetup() {
        startActivity(
            Intent(this, ConfigEditorActivity::class.java)
                .putExtra(ConfigEditorActivity.EXTRA_PATH, folder.absolutePath),
        )
    }

    /**
     * The folder is named the way the person knows it; the full location (a raw
     * mount path on some devices) is one press away, not the first thing read
     * (Droidtop/tracker#36).
     */
    private fun wireFolderLine(view: TextView) {
        var expanded = false
        fun render() {
            view.text = if (expanded) folder.absolutePath else getString(R.string.game_folder_line, folder.name.ifBlank { folder.absolutePath })
        }
        view.isClickable = true
        view.isFocusable = true
        view.setBackgroundResource(R.drawable.eh_row_bg)
        view.setOnClickListener {
            expanded = !expanded
            render()
        }
        render()
    }

    /** Setup may have changed while this screen was covered, so the state is read again. */
    override fun onResume() {
        super.onResume()
        if (!::folder.isInitialized) return
        refresh()
    }

    /** Reads the game's state and its sections again; a choice made on this page runs it so the page shows the result. */
    private fun refresh() {
        showTestingRow()
        showTitle(null)
        Thread {
            val isFavourite = runCatching { GameLibraryStore(applicationContext).isFavourite(folder) }.getOrDefault(false)
            val status = GameStatus.of(this, folder)
            // The art is the game's own icon (GameIcon), read from the same
            // config the status just resolved. The executable can sit on
            // removable storage, so this stays off the thread that is drawing.
            val art = status.config?.let { GameIcon.load(folder, it) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                showTitle(status.title)
                showFavourite(isFavourite)
                primary = status.primary
                findViewById<Button>(R.id.playButton).apply {
                    setText(status.primary.label)
                    isEnabled = status.primary.enabled
                }
                findViewById<TextView>(R.id.gameStatus).apply {
                    text = status.text
                    setTextColor(
                        ContextCompat.getColor(this@GameActivity, if (status.ok) R.color.eh_text_secondary else R.color.eh_caution),
                    )
                }
                findViewById<TextView>(R.id.gameEngine).apply {
                    if (status.engine == null) {
                        visibility = View.GONE
                    } else {
                        text = status.chip
                        EngineHues.paintChip(this, status.engine)
                        visibility = View.VISIBLE
                    }
                }
                showPlate(art, status)
                loadSections(status)
            }
        }.start()
    }

    private val sectionsView by lazy {
        GameSectionsView(this, findViewById(R.id.gameSections), folder) { refresh() }
    }

    /** The sections read the plugin catalogs and the save folder, so they come a moment after the page, off the main thread. */
    private fun loadSections(status: GameStatus) {
        Thread {
            val sections = runCatching { GameSectionsLoader.load(applicationContext, folder, status) }.getOrNull() ?: return@Thread
            runOnUiThread { if (!isDestroyed && !isFinishing) sectionsView.show(sections) }
        }.apply { isDaemon = true }.start()
    }

    /** A pending testing configuration from Game setup, with Keep and Discard one press away. */
    private fun showTestingRow() {
        TestingConfigRow.show(this, findViewById(R.id.playButton), folder) { showTestingRow() }
    }

    private fun showTitle(configTitle: String?) {
        val title = configTitle ?: folder.name.ifBlank { folder.absolutePath }
        findViewById<TextView>(R.id.gameTitle).text = title
        findViewById<TextView>(R.id.plateTitle).text = title
    }

    /**
     * The cover plate that fills this screen's freed space, so the window
     * holds the thing and not flat background: the game's own art when it
     * has any, and when it does not, the same plate with the same two
     * lines -- its name and what it is -- rather than a stand-in cover
     * (design language, 2026-09-17: a made-up cover is a lie about the
     * thing). The name is the plate's title line, already shown; while
     * resolution has not answered, the plate shows that name alone.
     */
    private fun showPlate(art: Bitmap?, status: GameStatus) {
        findViewById<ImageView>(R.id.plateArt).apply {
            if (art != null) setImageBitmap(art)
            visibility = if (art == null) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.plateLine).apply {
            text = status.chip.ifBlank { status.text }
            visibility = View.VISIBLE
        }
        findViewById<View>(R.id.platePlaceholder).visibility = if (art == null) View.VISIBLE else View.GONE
    }

    private fun play() {
        if (!folder.isDirectory) {
            Toast.makeText(this, R.string.game_folder_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        GameRunner.run(this, folder)
    }

    private fun confirmRemove() {
        Sheet(this)
            .title(R.string.remove_game_title)
            .message(R.string.remove_game_message)
            .choice(R.string.remove, Sheet.Tone.DANGER) {
                val library = GameLibraryStore(applicationContext)
                Thread {
                    library.forget(folder)
                    runOnUiThread { finish() }
                }.apply { isDaemon = true }.start()
            }
            .show()
    }

    companion object {
        private const val EXTRA_PATH = "dev.enginehost.game.PATH"

        fun intent(context: Context, folder: File): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_PATH, folder.absolutePath)
    }
}
