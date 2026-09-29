package dev.enginehost

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Plugins: what is installed, updates for it, and the approval decision for code
 * that will execute with Enginehost's permissions. Add opens the catalog.
 */
class PluginTrustActivity : EnginehostActivity() {
    /** The first update, else the first plugin to decide on; Add when there is none. */
    override fun primaryAction(): View? =
        firstSelectable(findViewById(R.id.updatesPanel)) ?: firstSelectable(findViewById(R.id.pluginList))
            ?: findViewById(R.id.openCatalogButton)

    private lateinit var list: ViewGroup
    private lateinit var emptyState: TextView
    private lateinit var openCatalogButton: Button
    private lateinit var trust: PluginTrustStore
    private lateinit var updatesPanel: ViewGroup

    /** Bumped per render so a stale background update lookup cannot redraw the panel. */
    private var updatesGeneration = 0
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.trust_title)
        trust = PluginTrustStore(this)
        setContentView(R.layout.activity_plugin_trust)
        wireBackButton()
        list = findViewById(R.id.pluginList)
        emptyState = findViewById(R.id.emptyState)
        openCatalogButton = findViewById(R.id.openCatalogButton)
        updatesPanel = findViewById(R.id.updatesPanel)
        openCatalogButton.setOnClickListener {
            startActivity(Intent(this, PluginCatalogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val requestedPackage = intent.getStringExtra(EXTRA_BUNDLE)
        val plugins = PluginRegistry.discover(this).filter {
            requestedPackage == null || it.bundleId == requestedPackage
        }
        emptyState.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
        plugins.sortedWith(compareBy({ it.info.engine }, { it.info.pluginVersion }, { it.bundleId }))
            .forEach { plugin -> addPlugin(plugin) }
        // Showing one plugin (just installed) is not the place for the updates of the others.
        if (requestedPackage == null) renderUpdates(plugins) else updatesPanel.visibility = View.GONE
    }

    /**
     * Updates come from the catalogs already cached on the device (the same
     * count Home shows), read off the main thread because reading them
     * verifies every cached manifest signature.
     */
    private fun renderUpdates(installed: List<InstalledPlugin>) {
        val generation = ++updatesGeneration
        Thread {
            val pending = runCatching { PluginUpdateCheck(this).pending() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed || generation != updatesGeneration) return@runOnUiThread
                showUpdates(pending, installed)
            }
        }.start()
    }

    private fun showUpdates(pending: List<AvailablePlugin>, installed: List<InstalledPlugin>) {
        updatesPanel.removeAllViews()
        updatesPanel.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        if (pending.isEmpty()) return
        val heading = layoutInflater.inflate(R.layout.item_group_heading, updatesPanel, false) as TextView
        heading.setText(R.string.updates_heading)
        updatesPanel.addView(heading)
        val buttons = mutableListOf<Button>()
        val rows = pending.map { update ->
            val row = layoutInflater.inflate(R.layout.item_release_build, updatesPanel, false)
            val plugin = installed.firstOrNull { it.bundleId == update.bundleId }
            val name = plugin?.let { EngineNames.family(it.info.engine) } ?: EngineNames.family(update.info.engine)
            row.findViewById<TextView>(R.id.buildLabel).text =
                getString(R.string.update_row, name, PluginVersions.build(update.info.pluginVersion))
            val button = row.findViewById<Button>(R.id.buildInstallButton)
            button.setText(R.string.update)
            buttons += button
            updatesPanel.addView(row)
            update to button
        }
        // Update all is one more button over the same per-row path, offered once there is a choice to skip.
        val all = if (pending.size > 1) {
            (layoutInflater.inflate(R.layout.item_primary_button, updatesPanel, false) as Button).also {
                it.setText(R.string.update_all)
                updatesPanel.addView(it)
                buttons += it
            }
        } else null
        rows.forEach { (update, button) -> button.setOnClickListener { runUpdates(listOf(update), buttons, button) } }
        all?.setOnClickListener { runUpdates(pending, buttons, all) }
    }

    /** Installs [updates] one after another, then redraws; the button pressed says what is happening. */
    private fun runUpdates(updates: List<AvailablePlugin>, buttons: List<Button>, pressed: Button) {
        if (updating) return
        updating = true
        buttons.forEach { it.isEnabled = false }
        pressed.setText(R.string.updating)
        Thread {
            var failed = 0
            var lastError = ""
            updates.forEach { update ->
                runCatching { PluginInstaller.installQuietly(this, update) }.onFailure {
                    failed++
                    lastError = it.message.orEmpty()
                }
            }
            runOnUiThread {
                updating = false
                if (isDestroyed) return@runOnUiThread
                if (failed > 0) {
                    Toast.makeText(
                        this, getString(R.string.updates_done, updates.size - failed, failed, lastError), Toast.LENGTH_LONG,
                    ).show()
                }
                render()
            }
        }.start()
    }

    private fun addPlugin(plugin: InstalledPlugin) {
        val ultimateBuild = trust.isDeveloperDebug(plugin)
        val official = !ultimateBuild && trust.isOfficial(plugin)
        val thirdParty = if (ultimateBuild || official) null else trust.thirdParty(plugin)
        val state = trust.state(plugin)
        val card = layoutInflater.inflate(R.layout.item_plugin_trust, list, false)

        // Named the way the catalog names it: the engine and the versions this
        // build runs ("Ren'Py 7.5.x"), so two lines of one engine tell apart.
        card.findViewById<TextView>(R.id.pluginTitle).text = EngineNames.compatibility(plugin.info.engine, plugin.info.capabilities)
            .ifEmpty { listOf(EngineNames.family(plugin.info.engine)) }
            .joinToString(" · ")
        // The repository adds nothing where the badge already says Official.
        card.findViewById<TextView>(R.id.trustBuildLine).text = if (official) {
            PluginVersions.display(plugin.info.pluginVersion)
        } else {
            getString(
                R.string.trust_build_line,
                PluginVersions.display(plugin.info.pluginVersion),
                plugin.origin.removePrefix("https://github.com/"),
            )
        }
        card.findViewById<TextView>(R.id.sandboxLine)
            .setText(if (plugin.isolatable) R.string.sandbox_yes else R.string.sandbox_no)
        card.findViewById<TextView>(R.id.bundleId).text = plugin.bundleId
        val details = card.findViewById<View>(R.id.trustDetails)
        card.findViewById<TextView>(R.id.trustDetailsToggle).setOnClickListener {
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val badge = card.findViewById<TextView>(R.id.trustBadge)
        val (label, container, onContainer) = when {
            ultimateBuild -> Triple(R.string.badge_ultimate, R.color.eh_caution_container, R.color.eh_on_caution_container)
            official -> Triple(R.string.badge_official, R.color.eh_official_container, R.color.eh_on_official_container)
            thirdParty != null -> Triple(R.string.badge_third_party, R.color.eh_community_container, R.color.eh_on_community_container)
            else -> Triple(R.string.badge_community, R.color.eh_community_container, R.color.eh_on_community_container)
        }
        badge.setText(label)
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, container))
        badge.setTextColor(ContextCompat.getColor(this, onContainer))

        card.findViewById<TextView>(R.id.originValue).text = "${getString(R.string.origin_label)}: " + if (thirdParty != null) {
            getString(R.string.origin_verified_third_party, plugin.origin, thirdParty.maintainerName)
        } else {
            getString(R.string.origin_verified, plugin.origin)
        }
        card.findViewById<TextView>(R.id.signerValue).text =
            "${getString(R.string.signer_label)}: ${plugin.signerIdentity}"
        card.findViewById<TextView>(R.id.trustState).text =
            "${getString(R.string.trust_state_label)}: ${getString(stateLabel(state))}"

        if (ultimateBuild) {
            // The primary developer's key proves origin more strongly than any
            // per-origin key can, but says nothing about fitness for use: this
            // is still a locally built bundle that skipped the release path.
            card.findViewById<TextView>(R.id.trustWarning).visibility = View.VISIBLE
        }

        card.findViewById<Button>(R.id.approveButton).apply {
            isEnabled = state != PluginTrustState.APPROVED && plugin.signerIdentity.isNotBlank()
            setOnClickListener {
                trust.approve(plugin)
                val pending = PendingPluginLaunchStore(this@PluginTrustActivity).consumeFor(plugin.bundleId)
                if (pending != null) {
                    GameRunner.run(this@PluginTrustActivity, java.io.File(pending.gamePath), pending.callerConfig, testing = pending.testing)
                    finish()
                } else {
                    render()
                }
            }
        }
        card.findViewById<Button>(R.id.denyButton).apply {
            isEnabled = state != PluginTrustState.DENIED && plugin.signerIdentity.isNotBlank()
            setOnClickListener { trust.deny(plugin); render() }
        }
        card.findViewById<Button>(R.id.uninstallButton).setOnClickListener {
            PluginRegistry.uninstall(this@PluginTrustActivity, plugin.bundleId)
            render()
        }
        list.addView(card)
    }

    private fun stateLabel(state: PluginTrustState): Int = when (state) {
        PluginTrustState.PENDING -> R.string.trust_state_pending
        PluginTrustState.APPROVED -> R.string.trust_state_approved
        PluginTrustState.DENIED -> R.string.trust_state_denied
    }

    companion object {
        const val EXTRA_BUNDLE = "dev.enginehost.trust.BUNDLE"
    }
}
