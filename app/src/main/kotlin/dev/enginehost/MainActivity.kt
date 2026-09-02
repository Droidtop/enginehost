package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Configuration-first home screen and direct-use game library manager.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var library: GameLibraryStore
    private lateinit var gameList: ViewGroup
    private lateinit var gameSearch: EditText

    /** Bumped per render so a stale background status pass cannot touch new rows. */
    private var renderGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        library = GameLibraryStore(this)
        gameList = findViewById(R.id.gameLibraryList)
        gameSearch = findViewById(R.id.gameSearch)
        gameSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderLibrary()
        })

        findViewById<Button>(R.id.createConfigButton).setOnClickListener {
            startActivity(Intent(this, ConfigEditorActivity::class.java))
        }
        findViewById<Button>(R.id.pickAndLaunchButton).setOnClickListener {
            openGamePickerWhenAllowed()
        }
        findViewById<Button>(R.id.scanFolderButton).setOnClickListener {
            startActivity(Intent(this, GameScanActivity::class.java))
        }
        findViewById<Button>(R.id.controllerConfigButton).setOnClickListener {
            startActivity(Intent(this, ControllerConfigActivity::class.java))
        }
        findViewById<Button>(R.id.enginehostSettingsButton).setOnClickListener {
            startActivity(Intent(this, EnginehostSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.pluginTrustButton).setOnClickListener {
            startActivity(Intent(this, PluginTrustActivity::class.java))
        }
        findViewById<Button>(R.id.pluginCatalogButton).setOnClickListener {
            startActivity(Intent(this, PluginCatalogActivity::class.java))
        }
        renderLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (::library.isInitialized) renderLibrary()
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
        library.remember(folder)
        renderLibrary()
        launchGame(folder)
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

    private fun renderLibrary() {
        val generation = ++renderGeneration
        gameList.removeAllViews()
        val allGames = library.games()
        gameSearch.visibility = if (allGames.size > SEARCH_THRESHOLD) View.VISIBLE else View.GONE
        val query = gameSearch.text.toString().trim()
        val games = if (query.isEmpty() || gameSearch.visibility != View.VISIBLE) allGames
        else allGames.filter { it.name.contains(query, ignoreCase = true) }
        if (games.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.item_hint, gameList, false) as TextView
            empty.setText(if (allGames.isEmpty()) R.string.games_empty else R.string.search_no_matches)
            gameList.addView(empty)
            return
        }
        val statusViews = mutableMapOf<String, TextView>()
        games.forEach { folder ->
            val row = layoutInflater.inflate(R.layout.item_game, gameList, false)
            val title = folder.name.ifBlank { folder.absolutePath }
            row.findViewById<TextView>(R.id.gameTitle).text =
                if (folder.isDirectory) title else getString(R.string.game_row_unavailable, title)
            row.findViewById<TextView>(R.id.gamePath).text = folder.absolutePath
            statusViews[folder.path] = row.findViewById(R.id.gameStatus)
            row.contentDescription = getString(R.string.launch_game_description, folder.absolutePath)
            row.setOnClickListener { launchGame(folder) }
            row.setOnLongClickListener {
                showGameActions(folder)
                true
            }
            gameList.addView(row)
        }
        resolveStatuses(generation, games, statusViews)
    }

    /**
     * Resolution touches disk and the plugin registry, so it runs off the UI
     * thread and each row fills in when its answer is known.
     */
    private fun resolveStatuses(generation: Int, games: List<File>, statusViews: Map<String, TextView>) {
        Thread {
            games.forEach { folder ->
                if (generation != renderGeneration) return@Thread
                val status = computeStatus(folder)
                runOnUiThread {
                    if (generation != renderGeneration) return@runOnUiThread
                    statusViews[folder.path]?.apply {
                        text = status.text
                        setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (status.ok) R.color.eh_text_secondary else R.color.eh_caution,
                            ),
                        )
                        visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    private data class GameStatus(val ok: Boolean, val text: String)

    private fun computeStatus(folder: File): GameStatus {
        if (!folder.isDirectory) return GameStatus(false, getString(R.string.status_missing))
        val config = try {
            EngineConfigReader.resolve(folder, null)
        } catch (e: InvalidEngineConfigException) {
            return if (!File(folder, CONFIG_FILE_NAME).isFile) {
                GameStatus(false, getString(R.string.status_no_config))
            } else {
                GameStatus(false, getString(R.string.status_bad_config, e.message))
            }
        }
        val resolved = runCatching {
            PluginRegistry.resolve(
                this, config.engine, config.engineContext, config.engineVersion,
                config.runtimeRequirements, config.pluginVersionConstraint,
            )
        }.getOrNull()
            ?: return GameStatus(
                false,
                getString(R.string.status_no_plugin, config.engine, config.engineVersion.toString()),
            )
        return GameStatus(
            true,
            getString(R.string.status_ready, config.engine, resolved.plugin.info.pluginVersion.toString()),
        )
    }

    private fun showGameActions(folder: File) {
        val actions = arrayOf(
            getString(R.string.action_launch),
            getString(R.string.action_edit_config),
            getString(R.string.action_remove),
        )
        AlertDialog.Builder(this)
            .setTitle(folder.name.ifBlank { folder.absolutePath })
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> launchGame(folder)
                    1 -> startActivity(
                        Intent(this, ConfigEditorActivity::class.java)
                            .putExtra(ConfigEditorActivity.EXTRA_PATH, folder.absolutePath),
                    )
                    2 -> confirmForget(folder)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchGame(folder: File) {
        if (!folder.isDirectory) {
            Toast.makeText(this, R.string.game_folder_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        library.remember(folder)
        GameRunner.run(this, folder)
    }

    private fun confirmForget(folder: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_game_title)
            .setMessage(R.string.remove_game_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                library.forget(folder)
                renderLibrary()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val REQUEST_GAME_FOLDER = 10
        private const val REQUEST_NATIVE_FILES = 11
        private const val SEARCH_THRESHOLD = 8
    }
}
