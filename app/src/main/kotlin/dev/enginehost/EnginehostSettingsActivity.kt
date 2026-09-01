package dev.enginehost

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Global Enginehost configuration. Save storage is its first managed option. */
class EnginehostSettingsActivity : Activity() {
    private lateinit var store: SaveLocationStore
    private lateinit var location: TextView
    private lateinit var browserStartStore: GameBrowserStartStore
    private lateinit var browserStartLocation: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SaveLocationStore(this)
        browserStartStore = GameBrowserStartStore(this)
        location = TextView(this)
        browserStartLocation = TextView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "Enginehost settings"; textSize = 24f })
            addView(TextView(context).apply { text = "\nSave location"; textSize = 20f })
            addView(TextView(context).apply {
                text = "Engines store saves beneath this shared folder using their own game naming."
            })
            addView(location)
            addView(Button(context).apply {
                text = "Choose folder"
                setOnClickListener { startActivityForResult(StorageFolder.pickerIntent(), REQUEST_FOLDER) }
            })
            addView(Button(context).apply {
                text = "Migrate old Enginehost saves"
                setOnClickListener { migrate(store.legacyRoot()) }
            })
            addView(Button(context).apply {
                text = "Use default internal storage"
                setOnClickListener { changeRoot(store.defaultRoot()) }
            })
            addView(TextView(context).apply { text = "\nGame browser start folder"; textSize = 20f })
            addView(TextView(context).apply {
                text = "Game and config folder pickers open here first. You can still browse anywhere."
            })
            addView(browserStartLocation)
            addView(Button(context).apply {
                text = "Choose game browsing folder"
                setOnClickListener {
                    startActivityForResult(
                        StorageFolder.pickerIntent(browserStartStore.initialUri()),
                        REQUEST_BROWSER_START,
                    )
                }
            })
            addView(Button(context).apply {
                text = "Use Android's default browsing location"
                setOnClickListener {
                    browserStartStore.clear()
                    refresh()
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(layout) })
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
                    Toast.makeText(this, "Choose a folder on internal or removable shared storage", Toast.LENGTH_LONG).show()
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
                    .setTitle("Move existing saves?")
                    .setMessage("Copy verified saves to the new location and remove only successfully copied originals?")
                    .setPositiveButton("Migrate") { _, _ -> migrate(oldSaves) }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }
    }

    private fun migrate(source: java.io.File) {
        val result = runCatching { store.migrate(source) }.getOrElse {
            Toast.makeText(this, "Migration failed: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this,
            "Migrated ${result.copied} files; ${result.conflicts} conflicts kept at the source; ${result.failures} failures",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun refresh() {
        location.text = "\n${store.root().path}\n"
        val tree = browserStartStore.treeUri()
        browserStartLocation.text = if (tree == null) {
            "\nAndroid default\n"
        } else {
            val path = StorageFolder.absolutePath(tree)?.path ?: tree.toString()
            "\n$path\n"
        }
    }

    companion object {
        private const val REQUEST_FOLDER = 30
        private const val REQUEST_BROWSER_START = 31
    }
}
