package dev.enginehost

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

/**
 * One game's own screen, opened from its card on Home: its name, engine
 * and state, Play as the primary action, and everything else that applies
 * to this one game -- its setup, a problem report, taking it off the list.
 *
 * Game setup lives here and nowhere on Home (UI assessment 2026-09-24,
 * H9): it is always about one game, so it is reached from that game.
 */
class GameActivity : EnginehostActivity() {
    /** Play. */
    override fun primaryAction(): View? =
        findViewById(R.id.playButton)

    private lateinit var folder: File

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
        findViewById<TextView>(R.id.gamePath).text = folder.absolutePath
        findViewById<Button>(R.id.playButton).setOnClickListener { play() }
        findViewById<Button>(R.id.setupButton).setOnClickListener {
            startActivity(
                Intent(this, ConfigEditorActivity::class.java)
                    .putExtra(ConfigEditorActivity.EXTRA_PATH, folder.absolutePath),
            )
        }
        findViewById<Button>(R.id.reportButton).setOnClickListener {
            startActivity(ProblemReportActivity.intent(this, folder))
        }
        findViewById<Button>(R.id.removeButton).setOnClickListener { confirmRemove() }
    }

    /** Setup may have changed while this screen was covered, so the state is read again. */
    override fun onResume() {
        super.onResume()
        if (!::folder.isInitialized) return
        showTitle(null)
        Thread {
            val status = GameStatus.of(this, folder)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                showTitle(status.title)
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
            }
        }.start()
    }

    private fun showTitle(configTitle: String?) {
        findViewById<TextView>(R.id.gameTitle).text =
            configTitle ?: folder.name.ifBlank { folder.absolutePath }
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
                GameLibraryStore(this).forget(folder)
                finish()
            }
            .show()
    }

    companion object {
        private const val EXTRA_PATH = "dev.enginehost.game.PATH"

        fun intent(context: Context, folder: File): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_PATH, folder.absolutePath)
    }
}
