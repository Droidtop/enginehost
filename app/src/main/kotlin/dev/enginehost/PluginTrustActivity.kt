package dev.enginehost

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** First-class approval UI for code that will execute with Enginehost's permissions. */
class PluginTrustActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var trust: PluginTrustStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Plugin trust"
        trust = PluginTrustStore(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(text(
            "Approved plugins execute inside Enginehost and receive its file access. " +
                "Official means the bundle signer matches a key built into Enginehost; official bundles still require approval.",
        ))
        val requestedPackage = intent.getStringExtra(EXTRA_BUNDLE)
        val plugins = PluginRegistry.discover(this).filter {
            requestedPackage == null || it.bundleId == requestedPackage
        }
        if (plugins.isEmpty()) {
            list.addView(text("No Enginehost API plugins are installed."))
            return
        }
        plugins.sortedWith(compareBy({ it.info.engine }, { it.info.pluginVersion }, { it.bundleId }))
            .forEach { plugin -> addPlugin(plugin) }
    }

    private fun addPlugin(plugin: InstalledPlugin) {
        val official = trust.isOfficial(plugin)
        val state = trust.state(plugin)
        list.addView(text(
            buildString {
                append(plugin.info.engine).append(" · plugin ").append(plugin.info.pluginVersion)
                append(if (official) " · Official" else " · Community")
                append("\n").append(plugin.bundleId)
                append("\nOrigin: ").append(plugin.origin).append(" (verified)")
                append("\nTrust: ").append(state.name.lowercase().replace('_', ' '))
                append("\nSigner: ").append(plugin.signerIdentity.take(23)).append('…')
            },
            topMargin = 28,
        ))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Approve"
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
        })
        controls.addView(Button(this).apply {
            text = "Deny"
            isEnabled = state != PluginTrustState.DENIED && plugin.signerIdentity.isNotBlank()
            setOnClickListener { trust.deny(plugin); render() }
        })
        controls.addView(Button(this).apply {
            text = "Uninstall"
            setOnClickListener {
                PluginRegistry.uninstall(this@PluginTrustActivity, plugin.bundleId)
                render()
            }
        })
        list.addView(controls)
    }

    private fun text(value: String, topMargin: Int = 0) = TextView(this).apply {
        text = value
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }
        visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_BUNDLE = "dev.enginehost.trust.BUNDLE"
    }
}
