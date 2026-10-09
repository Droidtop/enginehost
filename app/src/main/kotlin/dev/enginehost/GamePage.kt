package dev.enginehost

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

/**
 * One game's page: its name, engine and state, the cover plate, the one
 * primary button, its sections and the rest of what applies to this one game
 * (its setup, favourite, a problem report, taking it off the list). It draws
 * into whatever view tree it is given, so the same page is the
 * [GameActivity] on a narrow window and a pane beside Home's list on a wide
 * one (Droidtop/tracker#234); there is one page, not two.
 *
 * Game setup lives here and nowhere on Home (UI assessment 2026-09-24, H9):
 * it is always about one game, so it is reached from that game.
 */
class GamePage(
    private val activity: EnginehostActivity,
    private val root: View,
    /** Called after the game was taken off the list. */
    private val onRemoved: () -> Unit,
) {
    private var folder: File? = null

    /** What the primary button does; Play until the state has been read. */
    private var primary = GamePrimary.PLAY
    private var favourite = false
    private var folderLineExpanded = false

    val playButton: Button get() = root.findViewById(R.id.playButton)

    init {
        playButton.setOnClickListener {
            // Play, Get the core and Approve the core are all the launch: its plan sends the person
            // to the catalog or the trust screen when that is the next step.
            if (primary == GamePrimary.SET_UP) openSetup() else play()
        }
        root.findViewById<Button>(R.id.setupButton).setOnClickListener { openSetup() }
        root.findViewById<Button>(R.id.favouriteButton).setOnClickListener { toggleFavourite() }
        root.findViewById<Button>(R.id.reportButton).setOnClickListener {
            folder?.let { activity.startActivity(ProblemReportActivity.intent(activity, it)) }
        }
        root.findViewById<Button>(R.id.removeButton).setOnClickListener { confirmRemove() }
        root.findViewById<TextView>(R.id.gamePath).apply {
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.eh_row_bg)
            setOnClickListener {
                folderLineExpanded = !folderLineExpanded
                showFolderLine()
            }
        }
    }

    /** Shows [game]'s page. */
    fun show(game: File) {
        if (folder != game) folderLineExpanded = false
        folder = game
        refresh()
    }

    /** Forgets the game, for when it was taken off the list. */
    fun clear() {
        folder = null
    }

    /** The game this page shows, or null before one was chosen. */
    val game: File? get() = folder

    /**
     * The folder is named the way the person knows it; the full location (a raw
     * mount path on some devices) is one press away, not the first thing read
     * (Droidtop/tracker#36).
     */
    private fun showFolderLine() {
        val game = folder ?: return
        root.findViewById<TextView>(R.id.gamePath).text = if (folderLineExpanded) {
            game.absolutePath
        } else {
            activity.getString(R.string.game_folder_line, game.name.ifBlank { game.absolutePath })
        }
    }

    /** Reads the game's state and its sections again, for when setup or a choice on this page may have changed them. */
    fun refresh() {
        val game = folder ?: return
        showFolderLine()
        showTestingRow(game)
        showTitle(game, null)
        Thread {
            val isFavourite = runCatching { GameLibraryStore(activity.applicationContext).isFavourite(game) }.getOrDefault(false)
            val status = GameStatus.of(activity, game)
            // The art is the game's own icon (GameIcon), read from the same
            // config the status just resolved. The executable can sit on
            // removable storage, so this stays off the thread that is drawing.
            val art = status.config?.let { GameIcon.load(game, it) }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing || folder != game) return@runOnUiThread
                showTitle(game, status.title)
                showFavourite(isFavourite)
                primary = status.primary
                playButton.apply {
                    setText(status.primary.label)
                    isEnabled = status.primary.enabled
                }
                root.findViewById<TextView>(R.id.gameStatus).apply {
                    text = status.text
                    setTextColor(
                        ContextCompat.getColor(activity, if (status.ok) R.color.eh_text_secondary else R.color.eh_caution),
                    )
                }
                root.findViewById<TextView>(R.id.gameEngine).apply {
                    if (status.engine == null) {
                        visibility = View.GONE
                    } else {
                        text = status.chip
                        EngineHues.paintChip(this, status.engine)
                        visibility = View.VISIBLE
                    }
                }
                showPlate(art, status)
                loadSections(game, status)
            }
        }.start()
    }

    /** The sections read the plugin catalogs and the save folder, so they come a moment after the page, off the main thread. */
    private fun loadSections(game: File, status: GameStatus) {
        Thread {
            val sections = runCatching { GameSectionsLoader.load(activity.applicationContext, game, status) }.getOrNull()
                ?: return@Thread
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing || folder != game) return@runOnUiThread
                GameSectionsView(activity, root.findViewById(R.id.gameSections), game) { refresh() }.show(sections)
            }
        }.apply { isDaemon = true }.start()
    }

    /** A pending testing configuration from Game setup, with Keep and Discard one press away. */
    private fun showTestingRow(game: File) {
        TestingConfigRow.show(activity, playButton, game) { showTestingRow(game) }
    }

    private fun showTitle(game: File, configTitle: String?) {
        val title = configTitle ?: game.name.ifBlank { game.absolutePath }
        root.findViewById<TextView>(R.id.gameTitle).text = title
        root.findViewById<TextView>(R.id.plateTitle).text = title
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
        root.findViewById<ImageView>(R.id.plateArt).apply {
            if (art != null) setImageBitmap(art)
            visibility = if (art == null) View.GONE else View.VISIBLE
        }
        root.findViewById<TextView>(R.id.plateLine).apply {
            text = status.chip.ifBlank { status.text }
            visibility = View.VISIBLE
        }
        root.findViewById<View>(R.id.platePlaceholder).visibility = if (art == null) View.VISIBLE else View.GONE
    }

    /** Marks or unmarks this game as a favourite; the database is written off the main thread. */
    private fun toggleFavourite() {
        val game = folder ?: return
        val mark = !favourite
        showFavourite(mark)
        val library = GameLibraryStore(activity.applicationContext)
        Thread { library.setFavourite(game, mark) }.apply { isDaemon = true }.start()
    }

    private fun showFavourite(on: Boolean) {
        favourite = on
        root.findViewById<Button>(R.id.favouriteButton)
            .setText(if (on) R.string.action_remove_favourite else R.string.action_add_favourite)
    }

    private fun openSetup() {
        val game = folder ?: return
        activity.startActivity(
            Intent(activity, ConfigEditorActivity::class.java)
                .putExtra(ConfigEditorActivity.EXTRA_PATH, game.absolutePath),
        )
    }

    private fun play() {
        val game = folder ?: return
        if (!game.isDirectory) {
            Toast.makeText(activity, R.string.game_folder_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        GameRunner.run(activity, game)
    }

    private fun confirmRemove() {
        val game = folder ?: return
        Sheet(activity)
            .title(R.string.remove_game_title)
            .message(R.string.remove_game_message)
            .choice(R.string.remove, Sheet.Tone.DANGER) {
                val library = GameLibraryStore(activity.applicationContext)
                Thread {
                    library.forget(game)
                    activity.runOnUiThread { onRemoved() }
                }.apply { isDaemon = true }.start()
            }
            .show()
    }
}
