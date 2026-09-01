package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Configuration-first home screen and lightweight direct-use game library.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var library: GameLibraryStore
    private lateinit var gameList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        library = GameLibraryStore(this)
        gameList = findViewById(R.id.gameLibraryList)

        findViewById<Button>(R.id.createConfigButton).setOnClickListener {
            startActivity(Intent(this, ConfigEditorActivity::class.java))
        }
        findViewById<Button>(R.id.pickAndLaunchButton).setOnClickListener {
            openGamePickerWhenAllowed()
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
        gameList.removeAllViews()
        val games = library.games()
        if (games.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.item_hint, gameList, false) as TextView
            empty.setText(R.string.games_empty)
            gameList.addView(empty)
            return
        }
        games.forEach { folder ->
            val row = layoutInflater.inflate(R.layout.item_game, gameList, false)
            val title = folder.name.ifBlank { folder.absolutePath }
            row.findViewById<TextView>(R.id.gameTitle).text =
                if (folder.isDirectory) title else getString(R.string.game_row_unavailable, title)
            row.findViewById<TextView>(R.id.gamePath).text = folder.absolutePath
            row.contentDescription = getString(R.string.launch_game_description, folder.absolutePath)
            row.setOnClickListener { launchGame(folder) }
            row.setOnLongClickListener {
                confirmForget(folder)
                true
            }
            gameList.addView(row)
        }
    }

    private fun launchGame(folder: File) {
        if (!folder.isDirectory) {
            Toast.makeText(this, R.string.game_folder_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        library.remember(folder)
        if (!File(folder, CONFIG_FILE_NAME).isFile) {
            AlertDialog.Builder(this)
                .setTitle(R.string.configure_game_title)
                .setMessage(R.string.configure_game_message)
                .setPositiveButton(R.string.scan_and_configure) { _, _ ->
                    startActivity(
                        Intent(this, ConfigEditorActivity::class.java)
                            .putExtra(ConfigEditorActivity.EXTRA_PATH, folder.absolutePath),
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
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
    }
}
