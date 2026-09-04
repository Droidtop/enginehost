package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.io.File

/**
 * Global Enginehost configuration as a list of rows: where saves go (shared,
 * with per-engine exceptions), where the game browser starts, how updates
 * arrive, and the app itself. Each row shows its current value; a tap offers
 * the choices in a dialog, so the screen reads as settings, not as a form.
 */
class EnginehostSettingsActivity : AppCompatActivity() {
    private lateinit var store: SaveLocationStore
    private lateinit var location: TextView
    private lateinit var engineSaveRows: LinearLayout
    private lateinit var browserStartStore: GameBrowserStartStore
    private lateinit var browserStartLocation: TextView
    private lateinit var updateCheck: PluginUpdateCheck

    /** Engine families with an installed plugin, in the order their rows are shown. */
    private var engines: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        store = SaveLocationStore(this)
        browserStartStore = GameBrowserStartStore(this)
        updateCheck = PluginUpdateCheck(this)
        setContentView(R.layout.activity_settings)
        wireBackButton()
        location = findViewById(R.id.saveLocationValue)
        engineSaveRows = findViewById(R.id.engineSaveRows)
        browserStartLocation = findViewById(R.id.browserStartValue)

        findViewById<View>(R.id.saveRootRow).setOnClickListener {
            choose(
                R.string.settings_save_folder,
                listOf(
                    getString(R.string.choose_folder) to { startActivityForResult(StorageFolder.pickerIntent(), REQUEST_FOLDER) },
                    getString(R.string.use_default_internal) to { changeRoot(store.defaultRoot()) },
                    getString(R.string.migrate_saves) to { migrate(store.legacyRoot()) },
                ),
            )
        }
        findViewById<View>(R.id.browserStartRow).setOnClickListener {
            choose(
                R.string.browser_start,
                listOf(
                    getString(R.string.choose_folder) to {
                        startActivityForResult(StorageFolder.pickerIntent(browserStartStore.initialUri()), REQUEST_BROWSER_START)
                    },
                    getString(R.string.use_android_default) to {
                        browserStartStore.clear()
                        refresh()
                    },
                ),
            )
        }
        findViewById<View>(R.id.updateFrequencyRow).setOnClickListener {
            val labels = resources.getStringArray(R.array.update_frequency_entries)
            AlertDialog.Builder(this)
                .setTitle(R.string.updates_frequency_label)
                .setSingleChoiceItems(labels, updateCheck.frequency.ordinal) { dialog, which ->
                    updateCheck.frequency = PluginUpdateCheck.Frequency.entries[which]
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<View>(R.id.pluginStreamRow).setOnClickListener {
            val labels = arrayOf(
                getString(R.string.stream_stable_desc),
                getString(R.string.stream_testing_desc),
                getString(R.string.stream_unstable_desc),
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.updates_stream_label)
                .setSingleChoiceItems(labels, updateCheck.stream.ordinal) { dialog, which ->
                    dialog.dismiss()
                    val chosen = PluginStream.entries[which]
                    if (chosen != updateCheck.stream) {
                        updateCheck.stream = chosen
                        // The cached catalogs were filtered for the old choice;
                        // fetch again so the home screen and catalog reflect this one.
                        updateCheck.run { }
                    }
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<SwitchCompat>(R.id.unmeteredOnlySwitch).apply {
            isChecked = updateCheck.unmeteredOnly
            setOnCheckedChangeListener { _, checked -> updateCheck.unmeteredOnly = checked }
        }
        findViewById<SwitchCompat>(R.id.autoInstallPluginsSwitch).apply {
            isChecked = updateCheck.installAutomatically
            setOnCheckedChangeListener { _, checked -> updateCheck.installAutomatically = checked }
        }
        findViewById<TextView>(R.id.appVersionValue).text = getString(
            R.string.app_version_line,
            packageManager.getPackageInfo(packageName, 0).versionName,
            AppUpdate.installedVersionCode(this),
        )
        findViewById<View>(R.id.appVersionRow).setOnClickListener { checkAppUpdate() }
        refresh()
    }

    /** A short list of actions for one row. */
    private fun choose(title: Int, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkAppUpdate() {
        val row = findViewById<View>(R.id.appVersionRow)
        val lastChecked = findViewById<TextView>(R.id.lastCheckedValue)
        val installButton = findViewById<Button>(R.id.installAppUpdateButton)
        row.isEnabled = false
        lastChecked.setText(R.string.app_update_checking)
        // "Check now" is the whole pass -- plugins, detection rules and the
        // app -- so the home screen's notice is current afterwards too.
        updateCheck.run { }
        Thread {
            val result = runCatching { AppUpdate.fetch() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                row.isEnabled = true
                refreshLastChecked()
                result.onSuccess { info ->
                    if (info.versionCode > AppUpdate.installedVersionCode(this)) {
                        installButton.visibility = View.VISIBLE
                        installButton.text = getString(R.string.app_update_install, info.versionName)
                        installButton.setOnClickListener {
                            installButton.isEnabled = false
                            AppUpdate.downloadAndInstall(
                                this,
                                info,
                                onStatus = { status -> runOnUiThread { installButton.text = status } },
                                onError = { message ->
                                    runOnUiThread {
                                        installButton.isEnabled = true
                                        installButton.text = getString(R.string.app_update_install, info.versionName)
                                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                                    }
                                },
                            )
                        }
                    } else {
                        installButton.visibility = View.GONE
                        Toast.makeText(this, R.string.app_update_none, Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: getString(R.string.app_update_failed), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @Deprecated("Uses the platform folder picker result API available at the app's minimum SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        when {
            requestCode == REQUEST_FOLDER -> {
                val folder = StorageFolder.absolutePath(uri)
                if (folder == null) {
                    Toast.makeText(this, R.string.choose_shared_storage_folder, Toast.LENGTH_LONG).show()
                } else {
                    changeRoot(folder)
                }
            }
            requestCode == REQUEST_BROWSER_START -> runCatching { browserStartStore.select(uri) }
                .onSuccess { refresh() }
                .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            requestCode >= REQUEST_ENGINE_FOLDER -> {
                val engine = engines.getOrNull(requestCode - REQUEST_ENGINE_FOLDER) ?: return
                val folder = StorageFolder.absolutePath(uri)
                if (folder == null) {
                    Toast.makeText(this, R.string.choose_shared_storage_folder, Toast.LENGTH_LONG).show()
                } else {
                    runCatching { store.selectRootFor(engine, folder) }
                        .onSuccess { refresh() }
                        .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun changeRoot(newRoot: File) {
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

    private fun migrate(source: File) {
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
        findViewById<TextView>(R.id.updateFrequencyValue).text =
            resources.getStringArray(R.array.update_frequency_entries)[updateCheck.frequency.ordinal]
        findViewById<TextView>(R.id.pluginStreamValue).text =
            resources.getStringArray(R.array.plugin_stream_entries)[updateCheck.stream.ordinal]
        refreshEngineRows()
        refreshLastChecked()
    }

    /**
     * One row per engine family that can actually run something here: the
     * installed plugins decide the list, so a person never sees a folder
     * setting for an engine they do not have. A family that lost its last
     * plugin but still has an override keeps its row until the override is
     * cleared, so the setting can always be undone.
     */
    private fun refreshEngineRows() {
        val installed = PluginRegistry.discover(this).map { it.info.engine }
        engines = (installed + store.overrides().keys).distinct().sortedBy { EngineNames.family(it) }
        engineSaveRows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        engines.forEachIndexed { index, engine ->
            val row = inflater.inflate(R.layout.item_engine_save, engineSaveRows, false)
            row.findViewById<TextView>(R.id.engineName).text = EngineNames.family(engine)
            val override = store.overrideFor(engine)
            row.findViewById<TextView>(R.id.engineSavePath).text =
                override?.path ?: getString(R.string.engine_save_uses_shared)
            row.setOnClickListener {
                val actions = mutableListOf<Pair<String, () -> Unit>>(
                    getString(R.string.choose_folder) to {
                        startActivityForResult(StorageFolder.pickerIntent(), REQUEST_ENGINE_FOLDER + index)
                    },
                )
                if (override != null) {
                    actions += getString(R.string.use_shared_save_folder) to {
                        store.clearOverride(engine)
                        refresh()
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle(EngineNames.family(engine))
                    .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            engineSaveRows.addView(row)
        }
        findViewById<TextView>(R.id.engineSaveEmpty).visibility = if (engines.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshLastChecked() {
        val last = updateCheck.lastAttempt
        findViewById<TextView>(R.id.lastCheckedValue).text = if (last == null) {
            getString(R.string.updates_never_checked)
        } else {
            getString(
                R.string.updates_last_checked,
                DateUtils.getRelativeDateTimeString(this, last, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0),
            )
        }
    }

    companion object {
        private const val REQUEST_FOLDER = 30
        private const val REQUEST_BROWSER_START = 31
        /** Per-engine folder picks: this plus the row's index in [engines]. */
        private const val REQUEST_ENGINE_FOLDER = 100
    }
}
