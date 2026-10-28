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
 * One game's page: its name, engine and state, the cover plate, Play, then Run
 * with, Controls and Saves, then Game setup and Manage (its favourite, a problem
 * report, taking it off the list). It draws
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

    /** Whether the person chose this game's picture, read with the rest of its state. */
    private var chosenPicture = false
    private var folderLineExpanded = false

    val playButton: Button get() = root.findViewById(R.id.playButton)

    init {
        playButton.setOnClickListener {
            // Play, Get the core and Approve the core are all the launch: its plan sends the person
            // to the catalog or the trust screen when that is the next step.
            if (primary == GamePrimary.SET_UP) openSetup() else play()
        }
        root.findViewById<Button>(R.id.setupButton).setOnClickListener { openSetup() }
        root.findViewById<Button>(R.id.manageButton).setOnClickListener { showManage() }
        root.findViewById<TextView>(R.id.gamePath).apply {
            isClickable = true
            isFocusable = true
            background = AccentViewInflater.drawable(context, R.drawable.eh_row_bg)
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

    /**
     * Bumped per refresh. A refresh answers only while it is the newest, so
     * an older one (onResume racing a change on the page) cannot land last
     * and put back a stale Play state or favourite mark (review, 2026-10-16).
     */
    private var generation = 0

    /** Reads the game's state and its sections again, for when setup or a choice on this page may have changed them. */
    fun refresh() {
        val game = folder ?: return
        val current = ++generation
        showFolderLine()
        showTitle(game, null)
        Thread {
            // The testing configuration is a file: read here, not on the main thread.
            val testing = runCatching { TestingConfigStore.of(activity).get(game) }.getOrNull()
            activity.runOnUiThread {
                if (!activity.isDestroyed && current == generation && folder == game) showTestingRow(game, testing)
            }
            val isFavourite = runCatching { GameLibraryStore(activity.applicationContext).isFavourite(game) }.getOrDefault(false)
            val hasChosenPicture = GameArt.hasChosen(activity, game)
            val status = GameStatus.of(activity, game)
            // The art is the game's own icon (GameIcon), read from the same
            // config the status just resolved. The executable can sit on
            // removable storage, so this stays off the thread that is drawing.
            val art = status.config?.let { GameArt.load(activity, game, it) }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing || folder != game || current != generation) return@runOnUiThread
                showTitle(game, status.title)
                showFavourite(isFavourite)
                chosenPicture = hasChosenPicture
                primary = status.primary
                playButton.apply {
                    setText(status.primary.label)
                    isEnabled = status.primary.enabled
                }
                root.findViewById<TextView>(R.id.gameStatus).apply {
                    text = status.text
                    // "Ready" said nothing Play does not (Droidtop/tracker#620, E10): the line shows only what stands in the way.
                    visibility = if (status.ok) View.GONE else View.VISIBLE
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
                loadSections(game, status, current)
            }
        }.start()
    }

    /** The sections read the plugin catalogs and the save folder, so they come a moment after the page, off the main thread. */
    private fun loadSections(game: File, status: GameStatus, current: Int) {
        Thread {
            val sections = runCatching { GameSectionsLoader.load(activity.applicationContext, game, status) }.getOrNull()
                ?: return@Thread
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing || folder != game || current != generation) return@runOnUiThread
                GameSectionsView(activity, root.findViewById(R.id.gameSections), game) { refresh() }.show(sections)
            }
        }.apply { isDaemon = true }.start()
    }

    /** A pending testing configuration from Game setup, with Keep and Discard one press away. */
    private fun showTestingRow(game: File, pending: TestingConfigStore.Pending?) {
        TestingConfigRow.show(activity, playButton, game, pending) { refresh() }
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
    }

    /**
     * Manage: what is done to the game rather than with it, in one sheet below Game setup
     * (Droidtop/tracker#644): its favourite mark, a problem report, and taking it off the list.
     */
    private fun showManage() {
        val game = folder ?: return
        Sheet(activity)
            .title(R.string.action_manage)
            .choice(if (favourite) R.string.action_remove_favourite else R.string.action_add_favourite) { toggleFavourite() }
            .choice(R.string.action_choose_picture) { choosePicture(game) }
            .apply {
                if (chosenPicture) choice(R.string.action_own_picture) { ownPicture(game) }
            }
            .choice(R.string.action_report) { activity.startActivity(ProblemReportActivity.intent(activity, game)) }
            .choice(R.string.action_remove, Sheet.Tone.DANGER) { confirmRemove() }
            .show()
    }

    /** A picture of the person's own for this game (Droidtop/tracker#240), from the device's picker. */
    private fun choosePicture(game: File) {
        val key = "game-art-${System.nanoTime()}"
        var launcher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
        launcher = activity.activityResultRegistry.register(
            key,
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher?.unregister()
            val uri = result.data?.data ?: return@register
            Thread {
                val failure = runCatching { GameArt.choose(activity.applicationContext, game, uri) }.exceptionOrNull()
                activity.runOnUiThread {
                    if (failure != null) {
                        Toast.makeText(activity, ErrorText.of(activity, failure, R.string.picture_unusable), Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }.apply { isDaemon = true }.start()
        }
        launcher.launch(StorageFolder.filePickerIntent(listOf("image/*")))
    }

    private fun ownPicture(game: File) {
        Thread {
            GameArt.clearChosen(activity.applicationContext, game)
            activity.runOnUiThread { refresh() }
        }.apply { isDaemon = true }.start()
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
