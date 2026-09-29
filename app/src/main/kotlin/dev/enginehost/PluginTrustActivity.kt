package dev.enginehost

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * The Plugins screen (owner, 2026-09-27): what is installed comes first and
 * is the screen's primary content, the Updates section below offers what
 * those plugins may become (Update all, or one build at a time), and Add
 * plugins opens the catalog and its sources for adding new ones.
 *
 * The same screen serves as the one decision surface for a single bundle
 * (EXTRA_BUNDLE): it then shows exactly that plugin and nothing else, and
 * finishing the decision returns to the Plugins list rather than stacking
 * another screen on top of it.
 */
class PluginTrustActivity : EnginehostActivity() {
    /** The first plugin to act on; the Updates section or Add plugins when there is none. */
    override fun primaryAction(): View? =
        firstSelectable(findViewById(R.id.pluginList))
            ?: firstSelectable(findViewById(R.id.updateList))
            ?: findViewById<View>(R.id.openCatalogButton)?.takeIf { it.isShown }

    private lateinit var list: ViewGroup
    private lateinit var emptyState: TextView
    private lateinit var openCatalogButton: Button
    private lateinit var updatesSection: View
    private lateinit var updateAllButton: Button
    private lateinit var updateList: LinearLayout
    private lateinit var trust: PluginTrustStore

    /** The last answer to [refreshUpdates]; the Updates section renders from it. */
    private var pendingUpdates: List<AvailablePlugin> = emptyList()
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
        updatesSection = findViewById(R.id.updatesSection)
        updateAllButton = findViewById(R.id.updateAllButton)
        updateList = findViewById(R.id.updateList)
        openCatalogButton.setOnClickListener {
            startActivity(Intent(this, PluginCatalogActivity::class.java))
        }
        updateAllButton.setOnClickListener { updateAll() }
    }

    override fun onResume() {
        super.onResume()
        render()
        refreshUpdates()
    }

    /** One render path for the whole screen, so no part can disagree with another. */
    private fun render() {
        renderInstalled()
        renderUpdates()
    }

    private fun renderInstalled() {
        list.removeAllViews()
        val requestedPackage = intent.getStringExtra(EXTRA_BUNDLE)
        // A decision screen shows exactly its plugin: nothing to add beside
        // the question it exists to ask.
        openCatalogButton.visibility = if (requestedPackage == null) View.VISIBLE else View.GONE
        val plugins = PluginRegistry.discover(this).filter {
            requestedPackage == null || it.bundleId == requestedPackage
        }
        emptyState.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
        plugins.sortedWith(compareBy({ it.info.engine }, { it.info.pluginVersion }, { it.bundleId }))
            .forEach { plugin -> addPlugin(plugin) }
    }

    private fun renderUpdates() {
        // A decision screen shows exactly its plugin: no Updates section to
        // wander into mid-decision, and no Add plugins beside the question.
        val deciding = intent.hasExtra(EXTRA_BUNDLE)
        updatesSection.visibility =
            if (!deciding && pendingUpdates.isNotEmpty()) View.VISIBLE else View.GONE
        updateAllButton.isEnabled = !updating && pendingUpdates.isNotEmpty()
        updateAllButton.setText(if (updating) R.string.installing else R.string.update_all)
        updateList.removeAllViews()
        pendingUpdates.forEach { update -> addUpdateRow(update) }
    }

    /**
     * The Updates section's data, derived the way Home's notice is
     * (PluginUpdates.updatesFor over the cached catalogs), refreshed first
     * when those caches are older than this screen accepts -- the same pass
     * PluginUpdateCheck runs for Home, so the two screens cannot disagree
     * about whether updates exist.
     */
    private fun refreshUpdates() {
        val check = PluginUpdateCheck(this)
        Thread {
            if (check.catalogsStale()) {
                check.run { pending -> runOnUiThread { pendingUpdates = pending; render() } }
            } else {
                val pending = check.pending()
                runOnUiThread { pendingUpdates = pending; render() }
            }
        }.start()
    }

    private fun addUpdateRow(update: AvailablePlugin) {
        val row = layoutInflater.inflate(R.layout.item_plugin_update, updateList, false)
        row.findViewById<TextView>(R.id.updateTitle).text =
            EngineNames.engines(update.manifest).joinToString(" · ")
        row.findViewById<TextView>(R.id.updateMeta).text = getString(
            R.string.trust_build_line,
            PluginVersions.display(update.info.pluginVersion),
            update.origin.removePrefix("https://github.com/"),
        )
        val button = row.findViewById<Button>(R.id.updateButton)
        button.text = getString(R.string.update_to_build, PluginVersions.build(update.info.pluginVersion))
        button.isEnabled = !updating
        button.setOnClickListener { view ->
            // One decision per screen: the row installs its update, and only
            // a key or origin the line's approval does not cover opens the
            // one-plugin decision screen, which returns here when decided.
            view.isEnabled = false
            PluginInstaller.install(
                this,
                update,
                onError = { message -> runOnUiThread { view.isEnabled = true; toast(message) } },
                onStatus = { status -> runOnUiThread { button.text = status } },
                onInstalled = {
                    runOnUiThread {
                        pendingUpdates = pendingUpdates.filterNot { pending -> pending.bundleId == update.bundleId }
                        render()
                    }
                },
            )
        }
        updateList.addView(row)
    }

    /**
     * Every pending update, installed in one pass. Deliberately
     * prompt-free: the carry-over rule approves what it covers, and
     * anything whose key or origin changed appears on this very list as a
     * decision to make in place -- never as a stack of screens to Back out
     * of.
     */
    private fun updateAll() {
        if (updating || pendingUpdates.isEmpty()) return
        updating = true
        renderUpdates()
        val updates = pendingUpdates
        Thread {
            val failures = updates.mapNotNull { update ->
                runCatching {
                    val archive = PluginInstaller.fetch(this, update)
                    EngineBundleInstaller.install(this, archive, update.manifest)
                }.exceptionOrNull()?.let { failure -> "${update.manifest.assetName}: ${failure.message}" }
            }
            runOnUiThread {
                updating = false
                if (failures.isNotEmpty()) toast(failures.joinToString("\n"))
                refreshUpdates()
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
        card.findViewById<TextView>(R.id.trustBuildLine).text = getString(
            R.string.trust_build_line,
            PluginVersions.display(plugin.info.pluginVersion),
            plugin.origin.removePrefix("https://github.com/"),
        )
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
                when {
                    pending != null -> {
                        GameRunner.run(this@PluginTrustActivity, java.io.File(pending.gamePath), pending.callerConfig, testing = pending.testing)
                        finish()
                    }
                    // A decision screen exists to make exactly one decision;
                    // made, it returns to the Plugins list (or to wherever
                    // the install came from) instead of leaving a second
                    // screen to Back out of.
                    intent.hasExtra(EXTRA_BUNDLE) -> finish()
                    else -> render()
                }
            }
        }
        card.findViewById<Button>(R.id.denyButton).apply {
            isEnabled = state != PluginTrustState.DENIED && plugin.signerIdentity.isNotBlank()
            setOnClickListener {
                trust.deny(plugin)
                if (intent.hasExtra(EXTRA_BUNDLE)) finish() else render()
            }
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_BUNDLE = "dev.enginehost.trust.BUNDLE"
    }
}
