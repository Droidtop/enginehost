package dev.enginehost

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Over a running game that asked for a component it does not have ([ComponentRequests]): what it
 * needs, and the one step left to get it. Install it from a source that publishes it (the
 * installer then opens its trust screen, where accepting the licence approves it), accept the
 * licence of one already installed, or not now. Its answer goes back to the waiting game when it
 * closes, and the game carries on with the component or without it.
 *
 * The state is read again each time this screen comes back to the front, since the installer and
 * the trust screen it leads to open over it.
 */
class ComponentRequestActivity : EnginehostActivity() {
    private lateinit var heading: TextView
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var notNow: Button
    private var answered = false

    private val request get() = intent.getStringExtra(EXTRA_REQUEST).orEmpty()
    private val engine get() = intent.getStringExtra(EXTRA_ENGINE).orEmpty()
    private val name get() = intent.getStringExtra(EXTRA_NAME).orEmpty()
    private val version get() = intent.getStringExtra(EXTRA_VERSION)?.let { runCatching { Version.parse(it) }.getOrNull() }
    private val runtimeVersion get() = Version.parse(intent.getStringExtra(EXTRA_RUNTIME_VERSION) ?: "0")

    override fun primaryAction(): View? = if (::action.isInitialized && action.visibility == View.VISIBLE) action else notNow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_component_request)
        heading = findViewById(R.id.requestTitle)
        message = findViewById(R.id.requestMessage)
        progress = findViewById(R.id.requestProgress)
        action = findViewById(R.id.requestAction)
        notNow = findViewById(R.id.requestNotNow)
        notNow.setOnClickListener { answer(ComponentRequests.DECLINED) }
        val label = getString(R.string.component_title, EngineNames.componentName(name), version?.toString() ?: "").trim()
        heading.text = getString(R.string.component_request_title, label)
    }

    override fun onResume() {
        super.onResume()
        // While an install runs, its own trust screen comes next; this screen reads the state when it is back.
        if (!PluginInstaller.isInstalling()) render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        answer(ComponentRequests.DECLINED)
    }

    override fun onDestroy() {
        // Gone without an answer (the person left, or the process went): the game must not wait on.
        if (!answered && isFinishing) runCatching { ComponentRequests.outcome(this, request).writeText(ComponentRequests.DECLINED) }
        super.onDestroy()
    }

    private fun render() {
        Thread {
            val trust = PluginTrustStore(this)
            val components = PluginRegistry.components(this)
            val approved = ComponentRequests.pick(components, trust::isApproved, engine, name, version, runtimeVersion)
            val waiting = ComponentRequests.pick(components, { trust.state(it) == PluginTrustState.PENDING }, engine, name, version, runtimeVersion)
            val offered = if (approved == null && waiting == null) offered() else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                when {
                    approved != null -> answer(ComponentRequests.APPROVED)
                    waiting != null -> {
                        message.setText(R.string.component_request_accept_message)
                        action.visibility = View.VISIBLE
                        action.setText(R.string.component_request_accept)
                        action.setOnClickListener {
                            startActivity(Intent(this, PluginTrustActivity::class.java).putExtra(PluginTrustActivity.EXTRA_BUNDLE, waiting.bundleId))
                        }
                    }
                    offered != null -> {
                        message.text = getString(R.string.component_request_install_message, offered.manifest.license?.name ?: "")
                        action.visibility = View.VISIBLE
                        action.setText(R.string.component_request_install)
                        action.setOnClickListener { install(offered) }
                    }
                    else -> {
                        message.setText(R.string.component_request_unavailable)
                        action.visibility = View.GONE
                    }
                }
                selectPrimaryAction()
            }
        }.apply { isDaemon = true }.start()
    }

    /** The newest build any source publishes of what the game asked for, for this engine's series. */
    private fun offered(): AvailablePlugin? {
        val origins = PluginOriginStore(this)
        return PluginCatalogCache(this).loadAll(origins.all())
            .filter { available ->
                val component = available.manifest.component ?: return@filter false
                component.engine == engine && component.name == name && (version == null || component.version == version) &&
                    component.supportedSeries.any(runtimeVersion::belongsTo) &&
                    available.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION
            }
            .maxWithOrNull(compareBy<AvailablePlugin>({ it.manifest.component!!.version }, { it.info.pluginVersion }))
    }

    private fun install(plugin: AvailablePlugin) {
        action.isEnabled = false
        progress.visibility = View.VISIBLE
        PluginInstaller.install(
            this,
            plugin,
            onError = { error ->
                action.isEnabled = true
                progress.visibility = View.GONE
                message.text = error
            },
            onStatus = { status -> runOnUiThread { message.text = status } },
        )
        // The installer opens the trust screen over this one; coming back reads the state again.
    }

    private fun answer(outcome: String) {
        if (answered) return
        answered = true
        runCatching { ComponentRequests.outcome(this, request).writeText(outcome) }
        finish()
    }

    companion object {
        const val EXTRA_REQUEST = "dev.enginehost.component.REQUEST"
        const val EXTRA_ENGINE = "dev.enginehost.component.ENGINE"
        const val EXTRA_NAME = "dev.enginehost.component.NAME"
        const val EXTRA_VERSION = "dev.enginehost.component.VERSION"
        const val EXTRA_RUNTIME_VERSION = "dev.enginehost.component.RUNTIME_VERSION"
    }
}
