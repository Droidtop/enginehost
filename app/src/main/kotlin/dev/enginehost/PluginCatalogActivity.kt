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
        statusText = findViewById(R.id.statusText)
        refreshButton = findViewById(R.id.refreshButton)
        releaseFilterNote = findViewById(R.id.releaseFilterNote)
        releaseList = findViewById(R.id.releaseList)
        releasesEmptyState = findViewById(R.id.releasesEmptyState)
        originList = findViewById(R.id.originList)
        originInput = findViewById(R.id.originInput)
        addOriginButton = findViewById(R.id.addOriginButton)

        refreshButton.setOnClickListener { refresh() }
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
        if (requestedConfig != null) {
            releaseFilterNote.visibility = View.VISIBLE
            releaseFilterNote.setText(
                if (matches.isNotEmpty()) R.string.filtered_compatible else R.string.filtered_no_match,
            )
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
        available.groupBy { it.origin }.toSortedMap().forEach { (origin, releases) ->
            val heading = layoutInflater.inflate(R.layout.item_group_heading, releaseList, false) as TextView
            heading.text = origin.substringAfter("github.com/")
            releaseList.addView(heading)
            releases.forEach { plugin -> addRelease(plugin) }
        }
        if (
            intent.getBooleanExtra(EXTRA_AUTOINSTALL, false) && !autoAttempted &&
            matches.isNotEmpty() && !isInstalled(matches.first().bundleId)
        ) {
            autoAttempted = true
            PluginInstaller.install(this, matches.first(), ::toast)
        } else if (
            intent.getBooleanExtra(EXTRA_AUTOINSTALL, false) && matches.isEmpty() &&
            allAvailable.isEmpty() && !refreshing
        ) {
            refresh()
        }
    }

    private fun addRelease(plugin: AvailablePlugin) {
        val card = layoutInflater.inflate(R.layout.item_release, releaseList, false)
        card.findViewById<TextView>(R.id.releaseTitle).text =
            getString(R.string.plugin_line, plugin.info.engine, plugin.info.pluginVersion.toString())
        card.findViewById<TextView>(R.id.releaseContexts).text =
            plugin.info.capabilities.joinToString { "${it.engineContext} ${it.runtimeVersion}" }
        card.findViewById<TextView>(R.id.releaseTag).text = plugin.releaseTag
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
            val installed = isInstalled(plugin.bundleId)
            val supportedApi = plugin.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION
            button.text = when {
                installed -> getString(R.string.installed)
                !supportedApi -> getString(R.string.requires_api, plugin.apiVersion)
                else -> getString(R.string.install_asset, plugin.manifest.assetName)
            }
            button.isEnabled = !installed && supportedApi
            button.setOnClickListener { PluginInstaller.install(this@PluginCatalogActivity, plugin, ::toast) }
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
                runCatching { GithubPluginCatalogClient(this).fetch(origin) }
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
