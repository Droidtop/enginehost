package dev.enginehost

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import java.io.File

/**
 * One game's own screen, opened from its card on Home: the [GamePage] filling
 * the window, as it does on a narrow one. On a wide window Home shows the same
 * page in a pane beside its list instead of opening this.
 */
class GameActivity : EnginehostActivity() {
    /** Play. */
    override fun primaryAction(): View? =
        findViewById(R.id.playButton)

    private var page: GamePage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_game)
        wireBackButton()
        page = GamePage(this, findViewById(android.R.id.content), onRemoved = { finish() }).also { it.show(File(path)) }
    }

    private var resumedBefore = false

    /** Setup may have changed while this screen was covered, so the state is read again. */
    override fun onResume() {
        super.onResume()
        // The first resume follows the page that onCreate has just started reading.
        if (resumedBefore) page?.refresh()
        resumedBefore = true
    }

    companion object {
        private const val EXTRA_PATH = "dev.enginehost.game.PATH"

        fun intent(context: Context, folder: File): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_PATH, folder.absolutePath)
    }
}
