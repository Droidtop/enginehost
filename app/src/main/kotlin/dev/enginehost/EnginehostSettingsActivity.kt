package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Global Enginehost configuration. Save storage is its first managed option. */
class EnginehostSettingsActivity : AppCompatActivity() {
    private lateinit var store: SaveLocationStore
    private lateinit var location: TextView
    private lateinit var browserStartStore: GameBrowserStartStore
    private lateinit var browserStartLocation: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        store = SaveLocationStore(this)
        browserStartStore = GameBrowserStartStore(this)
        setContentView(R.layout.activity_settings)
        location = findViewById(R.id.saveLocationValue)
        browserStartLocation = findViewById(R.id.browserStartValue)

        findViewById<Button>(R.id.chooseSaveFolderButton).setOnClickListener {
            startActivityForResult(StorageFolder.pickerIntent(), REQUEST_FOLDER)
        }
        findViewById<Button>(R.id.migrateSavesButton).setOnClickListener {
            migrate(store.legacyRoot())
        }
        findViewById<Button>(R.id.useDefaultSaveButton).setOnClickListener {
            changeRoot(store.defaultRoot())
        }
        findViewById<Button>(R.id.chooseBrowserStartButton).setOnClickListener {
            startActivityForResult(
                StorageFolder.pickerIntent(browserStartStore.initialUri()),
                REQUEST_BROWSER_START,
            )
        }
        findViewById<Button>(R.id.useDefaultBrowserStartButton).setOnClickListener {
            browserStartStore.clear()
            refresh()
        }
        refresh()
    }

    @Deprecated("Uses the platform folder picker result API available at the app's minimum SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        when (requestCode) {
            REQUEST_FOLDER -> {
                val folder = StorageFolder.absolutePath(uri)
                if (folder == null) {
                    Toast.makeText(this, R.string.choose_shared_storage_folder, Toast.LENGTH_LONG).show()
                } else changeRoot(folder)
            }
            REQUEST_BROWSER_START -> runCatching { browserStartStore.select(uri) }
                .onSuccess { refresh() }
                .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun changeRoot(newRoot: java.io.File) {
        val oldSaves = runCatching { store.saveRoot() }.getOrNull()
        runCatching { store.selectRoot(newRoot) }.onFailure {
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
        }.onSuccess {
            refresh()
            if (oldSaves != null && oldSaves.canonicalFile != store.saveRoot().canonicalFile && oldSaves.exists()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.move_saves_title)
                    .setMessage(R.string.move_saves_message)
                    .setPositiveButton(R.string.migrate) { _, _ -> migrate(oldSaves) }
                    .setNegativeButton(R.string.not_now, null)
                    .show()
            }
        }
    }

    private fun migrate(source: java.io.File) {
        val result = runCatching { store.migrate(source) }.getOrElse {
            Toast.makeText(this, getString(R.string.migration_failed, it.message), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this,
            getString(R.string.migration_result, result.copied, result.conflicts, result.failures),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun refresh() {
        location.text = store.root().path
        val tree = browserStartStore.treeUri()
        browserStartLocation.text = if (tree == null) {
            getString(R.string.android_default)
        } else {
            StorageFolder.absolutePath(tree)?.path ?: tree.toString()
        }
    }

    companion object {
        private const val REQUEST_FOLDER = 30
        private const val REQUEST_BROWSER_START = 31
    }
}
