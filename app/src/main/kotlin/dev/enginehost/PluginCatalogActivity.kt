package dev.enginehost

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Complete available-release list plus preloaded and custom GitHub origins. */
class PluginCatalogActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var origins: PluginOriginStore
    private lateinit var cache: PluginCatalogCache
    private var refreshing = false
    private var autoAttempted = false
    private var requestedConfig: EngineConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Plugin catalog"
        origins = PluginOriginStore(this)
        cache = PluginCatalogCache(this)
        intent.getStringExtra(EXTRA_GAME_PATH)?.let { path ->
            requestedConfig = runCatching {
                EngineConfigReader.resolve(File(path), intent.getStringExtra(EXTRA_CALLER_CONFIG))
            }.getOrNull()
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    private fun render(message: String? = null) {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = message ?: "All published plugin releases from preloaded and custom origins. Install only the versions you need."
        })
        val input = EditText(this).apply { hint = "https://github.com/owner/plugin-repo" }
        content.addView(input)
        content.addView(Button(this).apply {
            text = "Add custom origin"
            setOnClickListener {
                val requested = input.text.toString()
                isEnabled = false
                Thread {
                    runCatching {
                        val key = PluginOriginKeyClient.fetch(requested)
                        origins.add(requested, key)
                    }.onSuccess {
                        runOnUiThread { render("Origin and signing key added. Refresh to load its releases.") }
                    }.onFailure { error ->
                        runOnUiThread {
                            isEnabled = true
                            toast(error.message ?: "Could not import repository key")
                        }
                    }
                }.start()
            }
        })
        content.addView(Button(this).apply {
            text = if (refreshing) "Refreshing…" else "Refresh all releases"
            isEnabled = !refreshing
            setOnClickListener { refresh() }
        })

        content.addView(heading("Origins"))
        origins.all().forEach { origin ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = origin
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!origins.isDefault(origin)) row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener { origins.remove(origin); render("Custom origin removed") }
            })
            content.addView(row)
        }

        content.addView(heading("Available releases"))
        val allAvailable = cache.loadAll(origins.all())
            .sortedWith(compareBy<AvailablePlugin>({ it.info.engine }, { it.info.pluginVersion }, { it.bundleId }))
        val matches = requestedConfig?.let { config ->
            AvailablePluginResolver.compatible(
                allAvailable, config.engine, config.engineContext, config.engineVersion,
                config.runtimeRequirements, config.pluginVersionConstraint,
            ).map { it.first }.distinctBy { it.bundleId }
        }.orEmpty()
        val available = if (requestedConfig != null && matches.isNotEmpty()) matches else allAvailable
        if (requestedConfig != null) content.addView(TextView(this).apply {
            text = if (matches.isNotEmpty()) {
                "Showing releases compatible with this game."
            } else {
                "No confident match is available. Showing every release so you can choose by engine knowledge."
            }
        })
        if (available.isEmpty()) content.addView(TextView(this).apply { text = "Refresh to load release catalogs." })
        available.groupBy { it.origin }.toSortedMap().forEach { (origin, releases) ->
            content.addView(heading(origin.substringAfter("github.com/")))
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
        content.addView(TextView(this).apply {
            val contexts = plugin.info.capabilities.joinToString { "${it.engineContext} ${it.runtimeVersion}" }
            text = "${plugin.info.engine} · plugin ${plugin.info.pluginVersion}\n$contexts\n${plugin.releaseTag}"
            setPadding(0, 16, 0, 0)
        })
        if (intent.getBooleanExtra(EXTRA_SELECTION_ONLY, false)) {
            plugin.info.capabilities.forEach { capability ->
                content.addView(Button(this).apply {
                    text = "Use ${capability.engineContext} ${capability.runtimeVersion}"
                    setOnClickListener {
                        setResult(
                            RESULT_OK,
                            android.content.Intent()
                                .putExtra("engine", plugin.info.engine)
                                .putExtra("engineContext", capability.engineContext)
                                .putExtra("engineVersion", capability.runtimeVersion.toString()),
                        )
                        finish()
                    }
                })
            }
        } else {
            content.addView(Button(this).apply {
                val installed = isInstalled(plugin.bundleId)
                val supportedApi = plugin.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION
                text = when {
                    installed -> "Installed"
                    !supportedApi -> "Requires Enginehost API ${plugin.apiVersion}"
                    else -> "Install ${plugin.manifest.assetName}"
                }
                isEnabled = !installed && supportedApi
                setOnClickListener { PluginInstaller.install(this@PluginCatalogActivity, plugin, ::toast) }
            })
        }
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        render("Refreshing GitHub release histories…")
        Thread {
            val failures = mutableListOf<String>()
            origins.all().forEach { origin ->
                runCatching { GithubPluginCatalogClient(this).fetch(origin) }
                    .onSuccess { cache.save(origin, it) }
                    .onFailure { failures += origin.substringAfterLast('/') }
            }
            runOnUiThread {
                refreshing = false
                render(if (failures.isEmpty()) "Catalogs refreshed." else "Refreshed; unavailable: ${failures.joinToString()}")
            }
        }.start()
    }

    private fun isInstalled(bundleId: String): Boolean = PluginRegistry.discover(this).any { it.bundleId == bundleId }
    private fun heading(value: String) = TextView(this).apply {
        text = value
        textSize = 20f
        setPadding(0, 32, 0, 12)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_GAME_PATH = "dev.enginehost.catalog.GAME_PATH"
        const val EXTRA_CALLER_CONFIG = "dev.enginehost.catalog.CALLER_CONFIG"
        const val EXTRA_AUTOINSTALL = "dev.enginehost.catalog.AUTOINSTALL"
        const val EXTRA_SELECTION_ONLY = "dev.enginehost.catalog.SELECTION_ONLY"
    }
}
