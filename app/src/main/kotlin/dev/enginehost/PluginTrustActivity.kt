package dev.enginehost

import android.content.Context
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
    /** The Cores destination, except when it shows one plugin that was just installed. */
    override val destination: Destination?
        get() = if (intent.hasExtra(EXTRA_BUNDLE)) null else Destination.CORES

    /** The first plugin to decide on, else the first update; Add when there is neither. */
    override fun primaryAction(): View? =
        firstSelectable(findViewById(R.id.pluginList)) ?: firstSelectable(findViewById(R.id.updatesPanel))
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

    /** Brought forward by [afterInstall]: show what the new intent asks for, the whole list. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Leaving the screen a launch stopped at, with no install running, ends that launch. */
    override fun onDestroy() {
        if (isFinishing && intent.getBooleanExtra(PendingPluginLaunchStore.EXTRA_RESUMES, false) && !PluginInstaller.isInstalling()) {
            PendingPluginLaunchStore(this).clear()
        }
        super.onDestroy()
    }

    /** Bumped per render so a list read for an older one cannot be drawn. */
    private var listGeneration = 0

    /**
     * What one card shows about a build, worked out off the main thread with
     * the list: each fact reads preferences, a signer or a bundle file. With
     * every build of every line installed side by side after Update all
     * (about sixty cards), doing it card by card on the main thread, while
     * the installs kept the disk busy, ended in "Enginehost isn't
     * responding" on this screen (rig, 2026-10-23, Droidtop/tracker#604).
     */
    private class CardFacts(
        val plugin: InstalledPlugin,
        val ultimateBuild: Boolean,
        val official: Boolean,
        val thirdParty: ThirdPartyOrigin?,
        val state: PluginTrustState,
        val change: TrustChange?,
        val legacyRefused: Boolean,
        /** The licence text a bundle carries, or null when it has none or it cannot be read. */
        val licenseText: String?,
    )

    private fun factsOf(plugin: InstalledPlugin): CardFacts {
        val ultimateBuild = trust.isDeveloperDebug(plugin)
        val official = !ultimateBuild && trust.isOfficial(plugin)
        return CardFacts(
            plugin = plugin,
            ultimateBuild = ultimateBuild,
            official = official,
            thirdParty = if (ultimateBuild || official) null else trust.thirdParty(plugin),
            state = trust.state(plugin),
            change = trust.changeSinceApproval(plugin),
            // A core signed only the legacy way never starts while the root is pinned.
            legacyRefused = plugin.legacySigned && EnginehostRoot.get(this) != null,
            licenseText = plugin.license?.let { license ->
                runCatching {
                    java.io.File(plugin.directory, license.file).also {
                        require(it.canonicalPath.startsWith(plugin.directory.canonicalPath + java.io.File.separator))
                    }.readText()
                }.getOrNull()
            },
        )
    }

    /**
     * Reads the installed bundles and every card's facts off the main thread
     * (each bundle is a directory and a manifest to parse) and draws them.
     * Reading them on the main thread stalled the screen on every return to
     * it (review, 2026-10-16).
     */
    private fun render() {
        val generation = ++listGeneration
        val requestedPackage = intent.getStringExtra(EXTRA_BUNDLE)
        Thread {
            val cards = runCatching {
                // One card per installed build: several builds of a line can sit side
                // by side, each approved, denied and removed on its own. A line's
                // builds stay together, newest first.
                PluginRegistry.discoverAll(this)
                    .filter { requestedPackage == null || it.bundleId == requestedPackage }
                    .sortedWith(
                        compareBy<InstalledPlugin>({ it.info.engine }, { it.bundleId })
                            .thenByDescending { it.info.pluginVersion }
                            .thenBy { it.archiveSha256 },
                    )
                    .map(::factsOf)
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed || generation != listGeneration) return@runOnUiThread
                showPlugins(cards, requestedPackage)
            }
        }.start()
    }

    /**
     * Cards are added a few per main-thread message. All of them in one
     * message (about sixty after Update all) spent 4.77 s of CPU inflating
     * rows inside a single Handler message, and BlueStacks answered with
     * "Enginehost isn't responding" on the first open (rig ANR trace,
     * Droidtop/tracker#291 comment 6370357050: addPlugin under showPlugins).
     * Between batches the screen draws and input is handled; a newer render
     * stops an older one's remaining batches.
     */
    private fun showPlugins(cards: List<CardFacts>, requestedPackage: String?) {
        val generation = listGeneration
        list.removeAllViews()
        emptyState.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        // Showing one plugin (just installed) is not the place for the updates of the others.
        if (requestedPackage == null) renderUpdates(cards.map { it.plugin }) else updatesPanel.visibility = View.GONE
        addInBatches(cards, 0, generation)
    }

    private fun addInBatches(cards: List<CardFacts>, from: Int, generation: Int) {
        if (isDestroyed || generation != listGeneration || from >= cards.size) return
        val until = minOf(from + CARDS_PER_BATCH, cards.size)
        for (index in from until until) addPlugin(cards[index])
        if (until < cards.size) list.post { addInBatches(cards, until, generation) }
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
            // The line the installed cards above use, then the version this
            // row installs: "Ren'Py 8.5.x: 1.0.1-2" tells two lines of one
            // engine apart, where "Ren'Py: build N" did not.
            row.findViewById<TextView>(R.id.buildLabel).text = getString(
                R.string.update_row,
                update.manifest.component?.let { EngineNames.component(this, it) } ?: lineName(plugin?.info ?: update.info),
                update.info.pluginVersion.toString(),
            )
            val button = row.findViewById<Button>(R.id.buildInstallButton)
            button.setText(R.string.update)
            buttons += button
            updatesPanel.addView(row)
            update to button
        }
        // Update all is one press over the same per-row path, offered whenever
        // there is anything to update.
        val all = (layoutInflater.inflate(R.layout.item_primary_button, updatesPanel, false) as Button).also {
            it.setText(R.string.update_all)
            updatesPanel.addView(it)
            buttons += it
        }
        rows.forEach { (update, button) -> button.setOnClickListener { runUpdates(listOf(update), buttons, button) } }
        all.setOnClickListener { runUpdates(pending, buttons, all) }
    }

    /** Installs [updates] one after another, after the warning when any cannot run sandboxed. */
    private fun runUpdates(updates: List<AvailablePlugin>, buttons: List<Button>, pressed: Button) {
        if (updating) return
        UnsandboxedWarning.beforeInstall(this, updates, proceed = { installUpdates(updates, buttons, pressed) }, cancel = {})
    }

    /** Installs [updates] one after another, then redraws; the button pressed says what is happening. */
    private fun installUpdates(updates: List<AvailablePlugin>, buttons: List<Button>, pressed: Button) {
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
                    lastError = ErrorText.of(this, it, R.string.core_install_failed)
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

    private fun addPlugin(facts: CardFacts) {
        val plugin = facts.plugin
        val ultimateBuild = facts.ultimateBuild
        val official = facts.official
        val thirdParty = facts.thirdParty
        val state = facts.state
        val card = layoutInflater.inflate(R.layout.item_plugin_trust, list, false)

        // Named the way the catalog names it: the engine and the versions this
        // build runs ("Ren'Py 7.5.x"), so two lines of one engine tell apart.
        card.findViewById<TextView>(R.id.pluginTitle).text =
            plugin.component?.let { EngineNames.component(this, it) } ?: lineName(plugin.info)
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
        card.findViewById<TextView>(R.id.sandboxLine).text = plugin.component?.let {
            // A component runs inside the engine that loads it; that engine's
            // card says whether it is sandboxed.
            EngineNames.componentServes(this, it)
        } ?: getString(
            when {
                plugin.isolatable -> R.string.sandbox_yes
                plugin.lockdown -> R.string.sandbox_locked
                else -> R.string.sandbox_no
            },
        )
        showLicense(card, facts)
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

        val sandboxBadge = card.findViewById<TextView>(R.id.sandboxBadge)
        val (sandboxLabel, sandboxContainer, sandboxOnContainer) = if (plugin.sandboxed) {
            Triple(R.string.badge_sandboxed, R.color.eh_official_container, R.color.eh_on_official_container)
        } else {
            Triple(R.string.badge_unsandboxed, R.color.eh_danger_container, R.color.eh_on_danger_container)
        }
        sandboxBadge.setText(sandboxLabel)
        sandboxBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, sandboxContainer))
        sandboxBadge.setTextColor(ContextCompat.getColor(this, sandboxOnContainer))
        if (plugin.component != null) sandboxBadge.visibility = View.GONE

        card.findViewById<TextView>(R.id.originValue).text = "${getString(R.string.origin_label)}: " + if (thirdParty != null) {
            getString(R.string.origin_verified_third_party, plugin.origin, thirdParty.maintainerName)
        } else {
            getString(R.string.origin_verified, plugin.origin)
        }
        card.findViewById<TextView>(R.id.signerValue).text =
            "${getString(R.string.signer_label)}: ${plugin.signerIdentity}"
        val change = facts.change
        // A core signed only the legacy way never starts while the root is
        // pinned; its card offers Remove alone, not a decision that changes
        // nothing (review, 2026-10-16).
        val legacyRefused = facts.legacyRefused
        card.findViewById<TextView>(R.id.trustState).text =
            if (legacyRefused) {
                // Deprecated: it will not start, and the card says what to do (owner, 2026-10-16).
                getString(R.string.trust_state_legacy)
            } else {
                "${getString(R.string.trust_state_label)}: ${getString(stateLabel(state, change))}"
            }

        if (ultimateBuild) {
            // The primary developer's key proves origin more strongly than any
            // per-origin key can, but says nothing about fitness for use: this
            // is still a locally built bundle that skipped the release path.
            card.findViewById<TextView>(R.id.trustWarning).visibility = View.VISIBLE
        }

        card.findViewById<Button>(R.id.approveButton).apply {
            // Approving a bundle that carries its own licence is accepting it.
            if (plugin.license != null) setText(R.string.approve_accept_license)
            isEnabled = state != PluginTrustState.APPROVED && plugin.signerIdentity.isNotBlank()
            setOnClickListener {
                trust.approve(plugin)
                isEnabled = false
                // The launch that stopped here resumes only if the game now
                // resolves to this core; finding out reads bundles.
                Thread {
                    val pending = runCatching { GameRunner.pendingLaunchFor(this@PluginTrustActivity, plugin) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (pending != null) {
                            GameRunner.resume(this@PluginTrustActivity, pending)
                            finish()
                        } else {
                            render()
                        }
                    }
                }.start()
            }
        }
        card.findViewById<Button>(R.id.denyButton).apply {
            isEnabled = state != PluginTrustState.DENIED && plugin.signerIdentity.isNotBlank()
            setOnClickListener { trust.deny(plugin); render() }
        }
        if (legacyRefused) {
            card.findViewById<View>(R.id.approveButton).visibility = View.GONE
            card.findViewById<View>(R.id.denyButton).visibility = View.GONE
        }
        card.findViewById<Button>(R.id.uninstallButton).setOnClickListener { button ->
            // Removing deletes the bundle's whole tree: off the main thread.
            button.isEnabled = false
            Thread {
                runCatching { PluginRegistry.uninstall(this@PluginTrustActivity, plugin) }
                runOnUiThread { if (!isDestroyed) render() }
            }.start()
        }
        list.addView(card)
    }

    /**
     * A bundle with a licence of its own (a runtime component such as
     * Spine's) names it, and its text is on the card: open before the
     * person decides, one tap away once they have. The text is the signed
     * payload file the manifest names, read with the card's other facts.
     */
    private fun showLicense(card: View, facts: CardFacts) {
        val license = facts.plugin.license ?: return
        val state = facts.state
        val line = card.findViewById<TextView>(R.id.licenseLine)
        val toggle = card.findViewById<TextView>(R.id.licenseToggle)
        val text = card.findViewById<TextView>(R.id.licenseText)
        line.visibility = View.VISIBLE
        line.text = getString(R.string.license_line, license.name)
        toggle.visibility = View.VISIBLE
        fun shown(open: Boolean) {
            text.visibility = if (open) View.VISIBLE else View.GONE
            toggle.setText(if (open) R.string.license_hide else R.string.license_show)
        }
        shown(state != PluginTrustState.APPROVED)
        toggle.setOnClickListener { shown(text.visibility != View.VISIBLE) }
        text.text = facts.licenseText ?: getString(R.string.license_unreadable)
    }

    /**
     * The one name a plugin's line goes by on this screen, so the installed
     * cards and their update rows cannot disagree: the compatibility lines the
     * bundle serves, else its engine family.
     */
    private fun lineName(info: PluginInfo): String =
        EngineNames.compatibility(info.engine, info.capabilities)
            .ifEmpty { listOf(EngineNames.family(info.engine)) }
            .joinToString(" · ")

    private fun stateLabel(state: PluginTrustState, change: TrustChange?): Int = when (state) {
        PluginTrustState.PENDING -> when (change) {
            TrustChange.SIGNER -> R.string.trust_state_signer_changed
            TrustChange.ORIGIN -> R.string.trust_state_origin_changed
            null -> R.string.trust_state_pending
        }
        PluginTrustState.APPROVED -> R.string.trust_state_approved
        PluginTrustState.DENIED -> R.string.trust_state_denied
    }

    companion object {
        const val EXTRA_BUNDLE = "dev.enginehost.trust.BUNDLE"

        /** Cards inflated per main-thread message ([showPlugins]): a few frames' work, not seconds. */
        private const val CARDS_PER_BATCH = 4

        /**
         * Where an install lands. A core that still needs the person's
         * decision opens on its own, as the approval prompt. Anything already
         * approved (an update that carried its approval, an Official core)
         * brings the whole Cores list forward: opening a one-core screen on
         * top of it left the page listing only the updated core until Back.
         */
        fun afterInstall(context: Context, installed: InstalledPlugin, resumesLaunch: Boolean = false): Intent =
            if (PluginTrustStore(context).isApproved(installed)) {
                Intent(context, PluginTrustActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } else {
                Intent(context, PluginTrustActivity::class.java).putExtra(EXTRA_BUNDLE, installed.bundleId)
            }.putExtra(PendingPluginLaunchStore.EXTRA_RESUMES, resumesLaunch)
    }
}
