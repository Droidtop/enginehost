package dev.enginehost

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** First-class approval UI for code that will execute with Enginehost's permissions. */
class PluginTrustActivity : AppCompatActivity() {
    private lateinit var list: ViewGroup
    private lateinit var emptyState: TextView
    private lateinit var openCatalogButton: Button
    private lateinit var trust: PluginTrustStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.trust_title)
        trust = PluginTrustStore(this)
        setContentView(R.layout.activity_plugin_trust)
        wireBackButton()
        list = findViewById(R.id.pluginList)
        emptyState = findViewById(R.id.emptyState)
        openCatalogButton = findViewById(R.id.openCatalogButton)
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
        val empty = plugins.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        openCatalogButton.visibility = if (empty) View.VISIBLE else View.GONE
        plugins.sortedWith(compareBy({ it.info.engine }, { it.info.pluginVersion }, { it.bundleId }))
            .forEach { plugin -> addPlugin(plugin) }
    }

    private fun addPlugin(plugin: InstalledPlugin) {
        val ultimateBuild = trust.isDeveloperDebug(plugin)
        val official = !ultimateBuild && trust.isOfficial(plugin)
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
            else -> Triple(R.string.badge_community, R.color.eh_community_container, R.color.eh_on_community_container)
        }
        badge.setText(label)
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, container))
        badge.setTextColor(ContextCompat.getColor(this, onContainer))

        card.findViewById<TextView>(R.id.originValue).text =
            "${getString(R.string.origin_label)}: ${getString(R.string.origin_verified, plugin.origin)}"
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
                    GameRunner.run(this@PluginTrustActivity, java.io.File(pending.gamePath), pending.callerConfig)
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
