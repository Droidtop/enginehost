package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Complete available-release list plus preloaded and custom GitHub origins. */
class PluginCatalogActivity : AppCompatActivity() {
    private lateinit var origins: PluginOriginStore
    private lateinit var cache: PluginCatalogCache
    private lateinit var directory: OriginDirectory

    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var releaseFilterNote: TextView
    private lateinit var releaseList: LinearLayout
    private lateinit var releasesEmptyState: TextView
    private lateinit var originList: LinearLayout
    private lateinit var originInput: EditText
    private lateinit var addOriginButton: Button

    private var refreshing = false
    private var autoAttempted = false
    private var requestedConfig: EngineConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.catalog_title)
        origins = PluginOriginStore(this)
        cache = PluginCatalogCache(this)
        directory = OriginDirectory(this)
        intent.getStringExtra(EXTRA_GAME_PATH)?.let { path ->
            requestedConfig = runCatching {
                EngineConfigReader.resolve(File(path), intent.getStringExtra(EXTRA_CALLER_CONFIG))
            }.getOrNull()
        }
        setContentView(R.layout.activity_plugin_catalog)
        wireBackButton()
        statusText = findViewById(R.id.statusText)
        refreshButton = findViewById(R.id.refreshButton)
        releaseFilterNote = findViewById(R.id.releaseFilterNote)
        releaseList = findViewById(R.id.releaseList)
        releasesEmptyState = findViewById(R.id.releasesEmptyState)
        originList = findViewById(R.id.originList)
        originInput = findViewById(R.id.originInput)
        addOriginButton = findViewById(R.id.addOriginButton)

        refreshButton.setOnClickListener { refresh() }
        findViewById<Button>(R.id.installedPluginsButton).setOnClickListener {
            startActivity(Intent(this, PluginTrustActivity::class.java))
        }
        val sourcesPanel = findViewById<View>(R.id.sourcesPanel)
        findViewById<TextView>(R.id.sourcesToggle).apply {
            text = getString(R.string.sources_toggle, origins.all().size)
            setOnClickListener {
                val open = sourcesPanel.visibility != View.VISIBLE
                sourcesPanel.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) getString(R.string.sources_hide) else getString(R.string.sources_toggle, origins.all().size)
            }
        }
        findViewById<Button>(R.id.installFromFileButton).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"),
                REQUEST_BUNDLE_FILE,
            )
        }
        addOriginButton.setOnClickListener { addCustomOrigin() }
        render()
    }

    private fun addCustomOrigin() {
        val requested = originInput.text.toString()
        addOriginButton.isEnabled = false
        Thread {
            runCatching {
                val key = PluginOriginKeyClient.fetch(requested)
                origins.add(requested, key)
            }.onSuccess {
                runOnUiThread {
                    addOriginButton.isEnabled = true
                    originInput.text.clear()
                    render(getString(R.string.origin_added))
                }
            }.onFailure { error ->
                runOnUiThread {
                    addOriginButton.isEnabled = true
                    toast(error.message ?: getString(R.string.origin_key_import_failed))
                }
            }
        }.start()
    }

    private fun render(message: String? = null) {
        statusText.text = message ?: getString(R.string.catalog_intro)
        refreshButton.setText(if (refreshing) R.string.refreshing else R.string.refresh_all)
        refreshButton.isEnabled = !refreshing
        renderOrigins()
        renderReleases()
    }

    private fun renderOrigins() {
        originList.removeAllViews()
        origins.all().forEach { origin ->
            val card = layoutInflater.inflate(R.layout.item_origin, originList, false)
            // A bare URL says nothing about what the user is trusting, so lead
            // with the repository's own name and description and keep the URL
            // underneath as the identity that actually matters.
            val described = directory.describe(origin)
            card.findViewById<TextView>(R.id.originName).text =
                described?.implementationName?.takeIf { it.isNotBlank() } ?: origin.substringAfterLast('/')
            val meta = card.findViewById<TextView>(R.id.originMeta)
            if (described == null) {
                meta.visibility = View.GONE
            } else {
                meta.text = buildString {
                    append(described.engine)
                    if (described.engineContexts.isNotEmpty()) {
                        append(" · ").append(described.engineContexts.joinToString(", "))
                    }
                }
            }
            val description = card.findViewById<TextView>(R.id.originDescription)
            described?.description?.takeIf { it.isNotBlank() }?.let {
                description.text = it
                description.visibility = View.VISIBLE
            }
            card.findViewById<TextView>(R.id.originUrl).text = origin
            val removeButton = card.findViewById<Button>(R.id.removeOriginButton)
            if (!origins.isDefault(origin)) {
                removeButton.visibility = View.VISIBLE
                removeButton.setOnClickListener {
                    origins.remove(origin)
                    render(getString(R.string.custom_origin_removed))
                }
            }
            originList.addView(card)
        }
    }

    private fun renderReleases() {
        releaseList.removeAllViews()
        val allOrigins = origins.all()
        val allAvailable = cache.loadAll(allOrigins)
            .sortedWith(compareBy<AvailablePlugin>({ it.info.engine }, { it.info.pluginVersion }, { it.bundleId }))
        val matches = requestedConfig?.let { config ->
            AvailablePluginResolver.compatible(
                allAvailable, config.engine, config.engineContext, config.engineVersion,
                config.runtimeRequirements, config.pluginVersionConstraint,
            ).map { it.first }.distinctBy { it.bundleId }
        }.orEmpty()
        val available = if (requestedConfig != null && matches.isNotEmpty()) matches else allAvailable
        requestedConfig?.let { config ->
            releaseFilterNote.visibility = View.VISIBLE
            releaseFilterNote.text = when {
                matches.isNotEmpty() -> getString(R.string.filtered_compatible)
                // A component requirement nothing here carries is the one
                // failure mode worth spelling out: the game names what it
                // needs, so say that rather than leaving the user to
                // guess which of these bundles is missing what.
                else -> unmetComponentNote(config, allAvailable) ?: getString(R.string.filtered_no_match)
            }
        }
        if (available.isEmpty()) {
            releasesEmptyState.visibility = View.VISIBLE
            // Whether a refresh has ever completed is the difference between
            // "you have not looked yet" and "there is genuinely nothing there".
            releasesEmptyState.setText(
                if (allOrigins.any(cache::hasFetched)) R.string.releases_none_published
                else R.string.releases_not_loaded,
            )
        } else {
            releasesEmptyState.visibility = View.GONE
        }
        // A store, not a ledger: one card per plugin showing its newest build,
        // ordered by the engine a person is looking for. Older builds stay a
        // tap away for whoever needs one; they are not thrown at everyone.
        available.groupBy { it.bundleId }.values
            .map { builds -> builds.maxBy { it.info.pluginVersion } to builds.sortedByDescending { it.info.pluginVersion }.drop(1) }
            .sortedWith(compareBy({ EngineNames.engines(it.first.manifest).firstOrNull() ?: "" }, { it.first.bundleId }))
            .forEach { (newest, older) -> addRelease(newest, older) }
        if (
            intent.getBooleanExtra(EXTRA_AUTOINSTALL, false) && !autoAttempted &&
            matches.isNotEmpty() && !isInstalled(matches.first().bundleId)
        ) {
            autoAttempted = true
            PluginInstaller.install(this, matches.first(), ::toast) { status ->
                runOnUiThread { statusText.text = status }
            }
        } else if (
            intent.getBooleanExtra(EXTRA_AUTOINSTALL, false) && matches.isEmpty() &&
            allAvailable.isEmpty() && !refreshing
        ) {
            refresh()
        }
    }

    /**
     * "This game needs spine-godot 4.2. No bundle here carries
     * spine-godot. Install one that lists it, or set
     * "runtimeRequirements" in the game's enginehost.json."
     *
     * Null when the requirements are all met and the mismatch is
     * somewhere else (engine family, version, plugin allowlist).
     */
    private fun unmetComponentNote(config: EngineConfig, available: List<AvailablePlugin>): String? {
        if (config.runtimeRequirements.isEmpty()) return null
        val capabilities = available.flatMap { it.info.capabilities } +
            PluginRegistry.discover(this).flatMap { it.info.capabilities }
        val unmet = RuntimeRequirementReport.unmet(config.runtimeRequirements, capabilities)
        if (unmet.isEmpty()) return null
        val needed = unmet.keys.sorted().joinToString(", ") { "$it ${config.runtimeRequirements[it]}" }
        val carried = unmet.entries.sortedBy { it.key }.joinToString(" ") { (name, versions) ->
            if (versions.isEmpty()) {
                getString(R.string.component_carried_none, name)
            } else {
                getString(R.string.component_carried, name, versions.joinToString(", "))
            }
        }
        return getString(R.string.filtered_missing_component, needed, carried)
    }

    private fun streamName(stream: PluginStream): String = when (stream) {
        PluginStream.STABLE -> getString(R.string.stream_stable)
        PluginStream.TESTING -> getString(R.string.stream_testing)
        PluginStream.UNSTABLE -> getString(R.string.stream_unstable)
    }

    /**
     * One plugin's card. What a person is deciding is "does this run my
     * game", so the engines it supports lead and the engine version it ships
     * sits on the right; what it bundles beyond the engine is the next line,
     * only when there is something to say; the plugin's own build number,
     * stream and repository are one small line at the bottom. [older]
     * builds are reachable through one control and otherwise out of the way.
     */
    private fun addRelease(plugin: AvailablePlugin, older: List<AvailablePlugin> = emptyList()) {
        val card = layoutInflater.inflate(R.layout.item_release, releaseList, false)
        card.findViewById<TextView>(R.id.releaseTitle).text = EngineNames.engines(plugin.manifest).joinToString(" · ")
        card.findViewById<TextView>(R.id.releaseVersion).apply {
            // A runtime that accepts any engine version ships no version worth a number.
            text = EngineNames.shippedVersions(plugin.info.capabilities)
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        card.findViewById<TextView>(R.id.releaseCompatibility).text = getString(
            R.string.release_runs,
            EngineNames.compatibility(plugin.info.engine, plugin.info.capabilities).joinToString("\n"),
        )
        card.findViewById<TextView>(R.id.releaseIncludes).apply {
            val includes = EngineNames.includes(plugin.info.capabilities)
            visibility = if (includes == null) View.GONE else View.VISIBLE
            includes?.let { text = getString(R.string.release_includes, it) }
        }
        card.findViewById<TextView>(R.id.releaseStream).apply {
            text = streamName(plugin.stream)
            // Stable is the expectation; only the other two need pointing out.
            visibility = if (plugin.stream == PluginStream.STABLE) View.GONE else View.VISIBLE
        }
        card.findViewById<TextView>(R.id.releaseMeta).text = getString(
            R.string.release_meta,
            PluginVersions.display(plugin.info.pluginVersion),
            streamName(plugin.stream),
        )
        val progress = card.findViewById<View>(R.id.releaseProgress)
        val olderList = card.findViewById<LinearLayout>(R.id.releaseOlderList)
        card.findViewById<TextView>(R.id.releaseOlderToggle).apply {
            visibility = if (older.isEmpty()) View.GONE else View.VISIBLE
            text = getString(R.string.release_other_versions, older.size)
            setOnClickListener {
                val open = olderList.visibility != View.VISIBLE
                olderList.visibility = if (open) View.VISIBLE else View.GONE
                text = getString(if (open) R.string.release_hide_other_versions else R.string.release_other_versions, older.size)
            }
        }
        if (!intent.getBooleanExtra(EXTRA_SELECTION_ONLY, false)) {
            older.forEach { build ->
                val row = layoutInflater.inflate(R.layout.item_release_build, olderList, false)
                row.findViewById<TextView>(R.id.buildLabel).text =
                    getString(R.string.release_build_line, PluginVersions.display(build.info.pluginVersion), streamName(build.stream))
                row.findViewById<Button>(R.id.buildInstallButton).setOnClickListener { view ->
                    view.isEnabled = false
                    PluginInstaller.install(
                        this@PluginCatalogActivity,
                        build,
                        onError = { message -> view.isEnabled = true; toast(message) },
                        onStatus = { status -> runOnUiThread { statusText.text = status } },
                    )
                }
                olderList.addView(row)
            }
        }
        val actions = card.findViewById<LinearLayout>(R.id.releaseActions)
        if (intent.getBooleanExtra(EXTRA_SELECTION_ONLY, false)) {
            plugin.info.capabilities.forEach { capability ->
                val button = layoutInflater.inflate(R.layout.item_action_button, actions, false) as Button
                button.text = getString(
                    R.string.use_capability, capability.engineContext, capability.runtimeVersion.toString(),
                )
                button.setOnClickListener {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra("engine", plugin.info.engine)
                            .putExtra("engineContext", capability.engineContext)
                            .putExtra("engineVersion", capability.runtimeVersion.toString()),
                    )
                    finish()
                }
                actions.addView(button)
            }
        } else {
            val button = layoutInflater.inflate(R.layout.item_primary_button, actions, false) as Button
            val installed = PluginRegistry.discover(this).filter { it.bundleId == plugin.bundleId }
            // A strictly newer build of an installed bundle is an update; the
            // installer replaces in place and the trust prompt re-appears for
            // the new archive before it can run.
            val update = installed.isNotEmpty() &&
                installed.all { PluginUpdates.isNewerBuildOf(it, plugin.manifest) }
            val supportedApi = plugin.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION
            val idleLabel = when {
                installed.isNotEmpty() && !update -> getString(R.string.installed)
                !supportedApi -> getString(R.string.requires_api, plugin.apiVersion)
                update -> getString(R.string.update_to_build, PluginVersions.build(plugin.info.pluginVersion))
                else -> getString(R.string.install)
            }
            button.text = idleLabel
            button.isEnabled = (installed.isEmpty() || update) && supportedApi
            button.setOnClickListener {
                // Progress belongs on the card being installed, not in a
                // status line somewhere else on the screen.
                button.isEnabled = false
                button.setText(R.string.installing)
                progress.visibility = View.VISIBLE
                PluginInstaller.install(
                    this@PluginCatalogActivity,
                    plugin,
                    onError = { message ->
                        runOnUiThread {
                            progress.visibility = View.GONE
                            button.isEnabled = true
                            button.text = idleLabel
                            toast(message)
                        }
                    },
                    onStatus = { status -> runOnUiThread { button.text = status } },
                )
            }
            actions.addView(button)
        }
        releaseList.addView(card)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        render(getString(R.string.refreshing_message))
        Thread {
            val failures = mutableListOf<String>()
            origins.all().forEach { origin ->
                directory.refresh(origin)
                runCatching { GithubPluginCatalogClient(this).fetch(origin, PluginUpdateCheck(this).stream) }
                    .onSuccess { cache.save(origin, it) }
                    .onFailure { failures += origin.substringAfterLast('/') }
            }
            runOnUiThread {
                refreshing = false
                render(
                    if (failures.isEmpty()) getString(R.string.refreshed_ok)
                    else getString(R.string.refreshed_partial, failures.joinToString()),
                )
            }
        }.start()
    }

    private fun isInstalled(bundleId: String): Boolean = PluginRegistry.discover(this).any { it.bundleId == bundleId }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BUNDLE_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        render(getString(R.string.verifying_bundle))
        PluginInstaller.installFromFile(this, uri, ::toast)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_GAME_PATH = "dev.enginehost.catalog.GAME_PATH"
        const val EXTRA_CALLER_CONFIG = "dev.enginehost.catalog.CALLER_CONFIG"
        const val EXTRA_AUTOINSTALL = "dev.enginehost.catalog.AUTOINSTALL"
        private const val REQUEST_BUNDLE_FILE = 4711
        const val EXTRA_SELECTION_ONLY = "dev.enginehost.catalog.SELECTION_ONLY"
    }
}
