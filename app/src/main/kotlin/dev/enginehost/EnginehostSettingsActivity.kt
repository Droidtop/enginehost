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
import androidx.appcompat.widget.SwitchCompat
import java.io.File

/**
 * Global Enginehost configuration as a list of rows: where saves go (shared,
 * with per-engine exceptions), where the game browser starts, how updates
 * arrive, and the app itself. Each row shows its current value; a tap offers
 * the choices in a [Sheet], so the screen reads as settings, not as a form.
 */
class EnginehostSettingsActivity : EnginehostActivity() {
    override val destination = Destination.SETTINGS

    /** The first setting. */
    override fun primaryAction(): View? =
        findViewById(R.id.saveRootRow)

    private lateinit var store: SaveLocationStore
    private lateinit var location: TextView
    private lateinit var engineSaveRows: LinearLayout
    private lateinit var browserStartStore: GameBrowserStartStore
    private lateinit var browserStartLocation: TextView
    private lateinit var updateCheck: PluginUpdateCheck
    private lateinit var tokenStore: GithubTokenStore

    /** Engine families with an installed plugin, in the order their rows are shown. */
    private var engines: List<String> = emptyList()

    /**
     * Set while an app update is with the Android installer: ends the
     * hand-over (the button can be pressed again). A successful install
     * replaces this process, so coming back to this screen, or a minute
     * passing with the screen in front, means it did not happen.
     */
    private var endHandOver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        store = SaveLocationStore(this)
        browserStartStore = GameBrowserStartStore(this)
        updateCheck = PluginUpdateCheck(this)
        tokenStore = GithubTokenStore(this)
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
            Sheet(this).title(R.string.updates_frequency_label).apply {
                labels.forEachIndexed { index, label ->
                    choice(label, current = index == updateCheck.frequency.ordinal) {
                        updateCheck.frequency = PluginUpdateCheck.Frequency.entries[index]
                        refresh()
                    }
                }
            }.show()
        }
        findViewById<View>(R.id.pluginStreamRow).setOnClickListener {
            showStreamSheet(updateCheck) { refresh() }
        }
        findViewById<SwitchCompat>(R.id.unmeteredOnlySwitch).apply {
            isChecked = updateCheck.unmeteredOnly
            setOnCheckedChangeListener { _, checked -> updateCheck.unmeteredOnly = checked }
        }
        findViewById<SwitchCompat>(R.id.readAloudSwitch).apply {
            val text = TextSettings(this@EnginehostSettingsActivity)
            isChecked = text.readAloud
            setOnCheckedChangeListener { _, checked -> text.readAloud = checked }
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
        // Which droidtop-platforms commit the bundled engine registry is, so a
        // classification argument between the two apps can be settled by reading
        // the two snapshots rather than guessing. Only for whoever asks: holding
        // the row down (Droidtop/tracker#35). Bug reports carry it too.
        findViewById<View>(R.id.appVersionRow).setOnLongClickListener {
            Sheet(this)
                .title(R.string.build_details_title)
                .message(
                    getString(
                        R.string.build_details_message,
                        PlatformSnapshot.shortCommit(this) ?: getString(R.string.platform_snapshot_unrecorded),
                    ),
                )
                .choice(R.string.ok) {}
                .show()
            true
        }
        findViewById<View>(R.id.appearanceRow).setOnClickListener { chooseAccent() }
        findViewById<View>(R.id.launchAccessRow).setOnClickListener {
            startActivity(Intent(this, CallerAccessSettingsActivity::class.java))
        }
        findViewById<View>(R.id.githubTokenRow).setOnClickListener { showTokenSheet() }
        // Home's notice of a newer Enginehost opens this screen to install it:
        // check at once, so the Install button is there (review, 2026-10-16).
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_APP_UPDATE, false)) checkAppUpdate()
        // onResume, which always follows, draws the rows: drawing them here
        // too read the installed cores twice on every opening.
    }

    override fun onResume() {
        super.onResume()
        refresh()
        endHandOver?.invoke()
    }

    /** What the Appearance row says the accent is. */
    private fun showAccent() {
        val chosen = when (Accent.choice(this)) {
            Accent.Choice.ENGINEHOST -> getString(R.string.appearance_enginehost)
            Accent.Choice.DEVICE -> getString(R.string.appearance_device)
            Accent.Choice.PICKED -> getString(R.string.appearance_picked)
        }
        findViewById<TextView>(R.id.appearanceValue).text =
            if (Accent.sessionAccent != null) getString(R.string.appearance_session, chosen) else chosen
    }

    /** Settings > Appearance: Enginehost's blue, the device's colour, or a hue of the person's own. */
    private fun chooseAccent() {
        val current = Accent.choice(this)
        Sheet(this).title(R.string.appearance_accent).apply {
            choice(getString(R.string.appearance_enginehost), current = current == Accent.Choice.ENGINEHOST) {
                Accent.set(this@EnginehostSettingsActivity, Accent.Choice.ENGINEHOST)
                recreate()
            }
            if (Accent.deviceColourAvailable()) {
                choice(getString(R.string.appearance_device), getString(R.string.appearance_device_detail), current = current == Accent.Choice.DEVICE) {
                    Accent.set(this@EnginehostSettingsActivity, Accent.Choice.DEVICE)
                    recreate()
                }
            }
            choice(getString(R.string.appearance_picked), getString(R.string.appearance_picked_detail), current = current == Accent.Choice.PICKED) {
                pickHue()
            }
        }.show()
    }

    /**
     * A hue slider with the colour it comes out as beside it, already through the contrast
     * guard, so what the person sees is what the app will use. The pad moves it with left
     * and right.
     */
    private fun pickHue() {
        val size = resources.getDimensionPixelSize(R.dimen.eh_touch_target)
        val swatch = View(this)
        val slider = android.widget.SeekBar(this).apply {
            max = 359
            progress = Accent.pickedHue(this@EnginehostSettingsActivity).toInt()
            keyProgressIncrement = 5
            // Made in code, so it is painted here as the inflater paints a layout's bars.
            val accent = android.content.res.ColorStateList.valueOf(AccentViewInflater.colour(this@EnginehostSettingsActivity, R.color.eh_accent))
            progressTintList = accent
            thumbTintList = accent
        }
        fun preview(hue: Int) = swatch.setBackgroundColor(Accent.pickedPreview(this, hue.toFloat()))
        preview(slider.progress)
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) = preview(value)
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Unit
        })
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(swatch, android.widget.LinearLayout.LayoutParams(size, size))
            addView(slider, android.widget.LinearLayout.LayoutParams(0, size, 1f))
        }
        Sheet(this).title(R.string.appearance_pick_title).content(content).apply {
            choice(getString(R.string.appearance_use)) {
                Accent.set(this@EnginehostSettingsActivity, Accent.Choice.PICKED, slider.progress.toFloat())
                recreate()
            }
        }.show()
    }

    /** A short list of actions for one row. */
    private fun choose(title: CharSequence, actions: List<Pair<String, () -> Unit>>) {
        Sheet(this).title(title).apply { actions.forEach { (label, pick) -> choice(label, pick = pick) } }.show()
    }

    private fun choose(title: Int, actions: List<Pair<String, () -> Unit>>) = choose(getString(title), actions)

    private fun checkAppUpdate() {
        val row = findViewById<View>(R.id.appVersionRow)
        row.isEnabled = false
        findViewById<TextView>(R.id.lastCheckedValue).setText(R.string.app_update_checking)
        // "Check now" is the whole pass -- plugins, detection rules and the
        // app -- so the home screen's notice is current afterwards too. The
        // pass reads the newest build first and says it here; this screen
        // used to fetch it a second time on its own.
        updateCheck.run(onApp = { result -> runOnUiThread { showAppUpdate(result) } }) { }
    }

    private fun showAppUpdate(result: Result<AppUpdate.Info>) {
        if (isDestroyed || isFinishing) return
        findViewById<View>(R.id.appVersionRow).isEnabled = true
        refreshLastChecked()
        val installButton = findViewById<Button>(R.id.installAppUpdateButton)
        result.onSuccess { info ->
            if (info.versionCode > AppUpdate.installedVersionCode(this)) {
                installButton.visibility = View.VISIBLE
                installButton.isEnabled = sessionEnd == null
                if (sessionEnd == null) installButton.text = getString(R.string.app_update_install, info.versionName)
                installButton.setOnClickListener { startAppInstall(info) }
            } else {
                installButton.visibility = View.GONE
                Toast.makeText(this, R.string.app_update_none, Toast.LENGTH_LONG).show()
            }
        }.onFailure {
            Toast.makeText(this, R.string.app_update_check_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Set while an install session is handed over; the installer's failure ends it ([AppUpdate.sessionEnded]). */
    private var sessionEnd: ((String) -> Unit)? = null

    private fun startAppInstall(info: AppUpdate.Info) {
        val installButton = findViewById<Button>(R.id.installAppUpdateButton)
        installButton.isEnabled = false
        val ended: (String) -> Unit = { message ->
            endAppInstall(info)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        sessionEnd = ended
        AppUpdate.addSessionEndListener(ended)
        AppUpdate.downloadAndInstall(
            this,
            info,
            onStatus = { status -> installButton.text = status },
            onError = ended,
            onHandedOver = {
                endHandOver = {
                    endAppInstall(info)
                    Toast.makeText(this, R.string.app_update_not_installed, Toast.LENGTH_LONG).show()
                }
                // With no installer screen in front of this one the
                // system answered without asking; give it a minute.
                installButton.postDelayed({
                    if (hasWindowFocus()) endHandOver?.invoke()
                }, HAND_OVER_TIMEOUT_MS)
            },
        )
    }

    /** The install did not happen: the button offers it again. */
    private fun endAppInstall(info: AppUpdate.Info) {
        sessionEnd?.let(AppUpdate::removeSessionEndListener)
        sessionEnd = null
        endHandOver = null
        findViewById<Button>(R.id.installAppUpdateButton).apply {
            isEnabled = true
            text = getString(R.string.app_update_install, info.versionName)
        }
    }

    override fun onDestroy() {
        sessionEnd?.let(AppUpdate::removeSessionEndListener)
        super.onDestroy()
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
                .onFailure { Toast.makeText(this, ErrorText.of(this, it, R.string.folder_unusable_choice), Toast.LENGTH_LONG).show() }
            requestCode >= REQUEST_ENGINE_FOLDER -> {
                val engine = engines.getOrNull(requestCode - REQUEST_ENGINE_FOLDER) ?: return
                val folder = StorageFolder.absolutePath(uri)
                if (folder == null) {
                    Toast.makeText(this, R.string.choose_shared_storage_folder, Toast.LENGTH_LONG).show()
                } else {
                    runCatching { store.selectRootFor(engine, folder) }
                        .onSuccess { refresh() }
                        .onFailure { Toast.makeText(this, ErrorText.of(this, it, R.string.folder_unusable_choice), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun changeRoot(newRoot: File) {
        if (migrating) {
            Toast.makeText(this, R.string.migration_running, Toast.LENGTH_LONG).show()
            return
        }
        val oldSaves = runCatching { store.saveRoot() }.getOrNull()
        runCatching { store.selectRoot(newRoot) }.onFailure {
            Toast.makeText(this, ErrorText.of(this, it, R.string.folder_unusable_choice), Toast.LENGTH_LONG).show()
        }.onSuccess {
            refresh()
            if (oldSaves != null && oldSaves.canonicalFile != store.saveRoot().canonicalFile && oldSaves.exists()) {
                Sheet(this)
                    .title(R.string.move_saves_title)
                    .message(R.string.move_saves_message)
                    .choice(R.string.migrate) { migrate(oldSaves) }
                    .choice(R.string.not_now) {}
                    .show()
            }
        }
    }

    /** A save move is running; another, or a new save root, waits for it. */
    private var migrating = false

    /**
     * Moves the saves from [source] to the new root on a worker: it copies
     * and verifies the whole tree, which on the main thread froze the
     * screen for as long as that took (review, 2026-10-16). A sheet counts
     * the files; closing it leaves the move running, and its result comes
     * as a message either way.
     */
    private fun migrate(source: File) {
        if (migrating) return
        migrating = true
        val count = layoutInflater.inflate(R.layout.item_hint, null) as TextView
        count.text = getString(R.string.migration_progress, 0)
        val sheet = Sheet(this).title(R.string.move_saves_title).content(count).show()
        Thread {
            val result = runCatching {
                store.migrate(source) { done ->
                    if (done % PROGRESS_EVERY == 0) runOnUiThread { count.text = getString(R.string.migration_progress, done) }
                }
            }
            runOnUiThread {
                migrating = false
                if (isDestroyed) return@runOnUiThread
                if (sheet.isShowing) sheet.dismiss()
                val message = result.fold(
                    { getString(R.string.migration_result, it.copied, it.conflicts, it.failures) },
                    { getString(R.string.migration_failed, ErrorText.of(this, it, R.string.folder_unusable_choice)) },
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun refresh() {
        showAccent()
        val tokenValue = findViewById<TextView>(R.id.githubTokenValue)
        tokenValue.text = if (tokenStore.hasToken) getString(R.string.github_token_masked, tokenStore.masked()) else getString(R.string.github_token_hint)
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
        val decisions = CallerAccessStore(this).all()
        findViewById<TextView>(R.id.launchAccessValue).text = resources.getQuantityString(
            R.plurals.launch_access_summary, decisions.size, decisions.size,
        )
    }

    /** Bumped per refresh so an engine list read for an older one is dropped. */
    private var engineRowsGeneration = 0

    /**
     * One row per engine family that can actually run something here: the
     * installed plugins decide the list, so a person never sees a folder
     * setting for an engine they do not have. A family that lost its last
     * plugin but still has an override keeps its row until the override is
     * cleared, so the setting can always be undone. The installed engines are
     * read off the main thread, where every bundle's manifest is parsed.
     */
    private fun refreshEngineRows() {
        val generation = ++engineRowsGeneration
        Thread {
            val installed = runCatching { PluginRegistry.discover(this).map { it.info.engine } }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed || generation != engineRowsGeneration) return@runOnUiThread
                showEngineRows(installed)
            }
        }.start()
    }

    /**
     * One row for every engine's save folder (Droidtop/tracker#620, E9). Fifteen rows that each said
     * "Shared folder" buried the one setting most people change, so the row names only the engines
     * that save somewhere else, and its sheet lists every engine.
     */
    private fun showEngineRows(installed: List<String>) {
        engines = (installed + store.overrides().keys).distinct().sortedBy { EngineNames.family(it) }
        engineSaveRows.removeAllViews()
        if (engines.isNotEmpty()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_engine_save, engineSaveRows, false)
            row.findViewById<TextView>(R.id.engineName).setText(R.string.engine_save_elsewhere)
            val elsewhere = engines.filter { store.overrideFor(it) != null }.map { EngineNames.family(it) }
            row.findViewById<TextView>(R.id.engineSavePath).text =
                if (elsewhere.isEmpty()) getString(R.string.engine_save_elsewhere_none) else elsewhere.joinToString(", ")
            row.setOnClickListener { showEngineSheet() }
            engineSaveRows.addView(row)
        }
        findViewById<TextView>(R.id.engineSaveEmpty).visibility = if (engines.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEngineSheet() {
        val sheet = Sheet(this).title(R.string.engine_save_elsewhere).message(R.string.engine_save_elsewhere_message)
        engines.forEachIndexed { index, engine ->
            val override = store.overrideFor(engine)
            sheet.choice(EngineNames.family(engine), override?.path ?: getString(R.string.engine_save_uses_shared)) {
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
                choose(EngineNames.family(engine), actions)
            }
        }
        sheet.show()
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

    private fun showTokenSheet() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (tokenStore.hasToken) {
            actions += getString(R.string.github_token_edit) to { editToken() }
            actions += getString(R.string.github_token_test) to { testToken() }
            actions += getString(R.string.github_token_remove) to {
                tokenStore.remove()
                refresh()
            }
        } else {
            actions += getString(R.string.github_token_set) to { editToken() }
        }
        choose(R.string.github_token_row, actions)
    }

    private fun editToken() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.github_token_paste_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        Sheet(this).title(R.string.github_token_row).content(input).apply {
            choice(getString(android.R.string.ok)) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    tokenStore.set(text)
                    refresh()
                }
            }
        }.show()
    }

    private fun testToken() {
        val token = tokenStore.get() ?: return
        Thread {
            val result = runCatching {
                val connection = java.net.URL("https://api.github.com/user").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "enginehost/0.1")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.responseCode in 200..299
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result) getString(R.string.github_token_test_ok, "user") else getString(R.string.github_token_test_fail),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    companion object {
        /** Opened from Home's app update notice: check for the update at once. */
        const val EXTRA_APP_UPDATE = "dev.enginehost.settings.APP_UPDATE"
        private const val REQUEST_FOLDER = 30
        private const val REQUEST_BROWSER_START = 31
        /** Per-engine folder picks: this plus the row's index in [engines]. */
        private const val REQUEST_ENGINE_FOLDER = 100
        /** How long an app update may sit with the installer, this screen in front, before the button comes back. */
        private const val HAND_OVER_TIMEOUT_MS = 60_000L

        /** How many saved files between two updates of the move's count. */
        private const val PROGRESS_EVERY = 25
    }
}
