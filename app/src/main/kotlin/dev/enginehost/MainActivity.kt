package dev.enginehost

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Configuration-first home screen and lightweight direct-use game library.
 */
class MainActivity : Activity() {
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
                Toast.makeText(
                    this,
                    "Game launching needs native folder access; config editing still works without it",
                    Toast.LENGTH_LONG,
                ).show()
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
            Toast.makeText(
                this,
                "Choose a folder on internal or removable shared storage",
                Toast.LENGTH_LONG,
            ).show()
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
            gameList.addView(TextView(this).apply {
                text = "No games added yet. Choose a game folder to get started."
                setPadding(0, 12, 0, 0)
            })
            return
        }
        games.forEach { folder ->
            gameList.addView(Button(this).apply {
                isAllCaps = false
                val title = folder.name.ifBlank { folder.absolutePath }
                text = if (folder.isDirectory) "$title\n${folder.absolutePath}"
                else "$title (unavailable)\n${folder.absolutePath}"
                contentDescription = "Launch ${folder.absolutePath}"
                setOnClickListener { launchGame(folder) }
                setOnLongClickListener {
                    confirmForget(folder)
                    true
                }
            })
        }
    }

    private fun launchGame(folder: File) {
        if (!folder.isDirectory) {
            Toast.makeText(this, "That game folder is not currently available", Toast.LENGTH_LONG).show()
            return
        }
        library.remember(folder)
        if (!File(folder, CONFIG_FILE_NAME).isFile) {
            AlertDialog.Builder(this)
                .setTitle("Configure this game?")
                .setMessage("Enginehost needs an enginehost.json in the game folder. Scan the folder and create one now?")
                .setPositiveButton("Scan and configure") { _, _ ->
                    startActivity(
                        Intent(this, ConfigEditorActivity::class.java)
                            .putExtra(ConfigEditorActivity.EXTRA_PATH, folder.absolutePath),
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        GameRunner.run(this, folder)
    }

    private fun confirmForget(folder: File) {
        AlertDialog.Builder(this)
            .setTitle("Remove from Enginehost?")
            .setMessage("This only removes the shortcut. The game and its saves stay on storage.")
            .setPositiveButton("Remove") { _, _ ->
                library.forget(folder)
                renderLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REQUEST_GAME_FOLDER = 10
        private const val REQUEST_NATIVE_FILES = 11
    }
}
