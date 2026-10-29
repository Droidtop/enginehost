package dev.enginehost

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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
        if (::list.isInitialized) rows.cancel()
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
        Async.load(this, work = {
            runCatching {
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
        }) { cards ->
            if (generation == listGeneration) showPlugins(cards, requestedPackage)
        }
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
        list.removeAllViews()
        emptyState.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        // Showing one plugin (just installed) is not the place for the updates of the others.
        if (requestedPackage == null) renderUpdates(cards.map { it.plugin }) else updatesPanel.visibility = View.GONE
        // Builds of one line are one card, newest first; cards come sorted that way already.
        val lines = cards.groupBy { it.plugin.bundleId }.values.toList()
        rows.add(lines, ::addLine)
    }

    private val rows by lazy { RowBatches(list) }

    /**
     * Updates come from the catalogs already cached on the device (the same
     * count Home shows), read off the main thread because reading them
     * verifies every cached manifest signature.
     */
    private fun renderUpdates(installed: List<InstalledPlugin>) {
        val generation = ++updatesGeneration
        Async.load(this, work = { runCatching { PluginUpdateCheck(this).pending() }.getOrDefault(emptyList()) }) { pending ->
            if (generation == updatesGeneration) showUpdates(pending, installed)
        }
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
        Async.load(this, work = {
            var failed = 0
            var lastError = ""
            updates.forEach { update ->
                runCatching { PluginInstaller.installQuietly(this, update) }.onFailure {
                    failed++
                    lastError = ErrorText.of(this, it, R.string.core_install_failed)
                }
            }
            failed to lastError
        }) { (failed, lastError) ->
            updating = false
            if (failed > 0) {
                Toast.makeText(
                    this, getString(R.string.updates_done, updates.size - failed, failed, lastError), Toast.LENGTH_LONG,
                ).show()
            }
            render()
        }
    }

    /** One card per line (#620 E5): its newest build, with the older ones folded beneath. */
    private fun addLine(builds: List<CardFacts>) = addPlugin(builds.first(), builds.drop(1))

    private fun addPlugin(facts: CardFacts, older: List<CardFacts> = emptyList()) {
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
        // The build's version; where it came from is in Details (#620 E5: machinery stays out of the card).
        card.findViewById<TextView>(R.id.trustBuildLine).text = PluginVersions.display(plugin.info.pluginVersion)
        // A component's line says which engines load it; an engine core's sandbox is its badge (#620 E5).
        card.findViewById<TextView>(R.id.sandboxLine).apply {
            val serves = plugin.component?.let { EngineNames.componentServes(this@PluginTrustActivity, it) }
            visibility = if (serves == null) View.GONE else View.VISIBLE
            serves?.let { text = it }
        }
        showLicense(card, facts)
        // Bundle id, origin and signer are the Details sheet's, not the card's (#620 E5).
        card.findViewById<View>(R.id.trustDetails).visibility = View.GONE
        card.findViewById<TextView>(R.id.trustDetailsToggle).setOnClickListener { showDetails(facts) }

        // The only badges: Official or Unofficial, and Full access where the core runs unsandboxed.
        TrustBadges.origin(card.findViewById(R.id.trustBadge), official)
        TrustBadges.access(card.findViewById(R.id.sandboxBadge), plugin.sandboxed, plugin.component != null)

        val change = facts.change
        // A core signed only the legacy way never starts while the root is
        // pinned; its card offers Remove alone, not a decision that changes
        // nothing (review, 2026-10-16).
        val legacyRefused = facts.legacyRefused
        // A state line only when it is not the normal one: an approved core says nothing about it (#620 E5).
        card.findViewById<TextView>(R.id.trustState).apply {
            val line = when {
                legacyRefused -> getString(R.string.trust_state_legacy)
                state == PluginTrustState.APPROVED -> null
                else -> getString(stateLabel(state, change))
            }
            visibility = if (line == null) View.GONE else View.VISIBLE
            line?.let { text = it }
        }

        if (ultimateBuild) {
            // The primary developer's key proves origin more strongly than any
            // per-origin key can, but says nothing about fitness for use: this
            // is still a locally built bundle that skipped the release path.
            card.findViewById<TextView>(R.id.trustWarning).visibility = View.VISIBLE
        }

        // Only the buttons that apply: Approve while the build waits; Deny is in Details; Remove always.
        card.findViewById<Button>(R.id.approveButton).apply {
            // Approving a bundle that carries its own licence is accepting it.
            if (plugin.license != null) setText(R.string.approve_accept_license)
            visibility = if (!legacyRefused && state != PluginTrustState.APPROVED && plugin.signerIdentity.isNotBlank()) View.VISIBLE else View.GONE
            setOnClickListener { approve(plugin) }
        }
        card.findViewById<View>(R.id.denyButton).visibility = View.GONE
        card.findViewById<Button>(R.id.uninstallButton).setOnClickListener { button -> remove(plugin, button) }
        if (older.isNotEmpty()) addOlderBuilds(card as ViewGroup, older)
        list.addView(card)
    }

    private fun approve(plugin: InstalledPlugin) {
        trust.approve(plugin)
        // The launch that stopped here resumes only if the game now
        // resolves to this core; finding out reads bundles.
        Async.load(this, work = { runCatching { GameRunner.pendingLaunchFor(this@PluginTrustActivity, plugin) }.getOrNull() }) { pending ->
            if (pending != null) {
                GameRunner.resume(this@PluginTrustActivity, pending)
                finish()
            } else {
                render()
            }
        }
    }

    private fun remove(plugin: InstalledPlugin, button: View) {
        // Removing deletes the bundle's whole tree: off the main thread.
        button.isEnabled = false
        Async.load(this, work = { runCatching { PluginRegistry.uninstall(this@PluginTrustActivity, plugin) } }) { render() }
    }

    /** What a build is and who signed it, and Deny: one sheet instead of an inline block (#620 E5). */
    private fun showDetails(facts: CardFacts) {
        val plugin = facts.plugin
        val origin = facts.thirdParty?.let { getString(R.string.origin_verified_third_party, plugin.origin, it.maintainerName) }
            ?: getString(R.string.origin_verified, plugin.origin)
        val sheet = Sheet(this)
            .title(PluginVersions.display(plugin.info.pluginVersion))
            .message(
                listOf(
                    plugin.bundleId,
                    "${getString(R.string.origin_label)}: $origin",
                    "${getString(R.string.signer_label)}: ${plugin.signerIdentity.ifBlank { "-" }}",
                ).joinToString("\n"),
            )
        if (!facts.legacyRefused && facts.state != PluginTrustState.DENIED && plugin.signerIdentity.isNotBlank()) {
            sheet.choice(R.string.deny, Sheet.Tone.DANGER) { trust.deny(plugin); render() }
        }
        sheet.choice(R.string.close) {}
        sheet.show()
    }

    /** A line's older builds, one row each, folded under its newest (#620 E5). */
    private fun addOlderBuilds(card: ViewGroup, older: List<CardFacts>) {
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val toggle = layoutInflater.inflate(R.layout.item_action_button, card, false) as Button
        fun label(open: Boolean) {
            toggle.text = getString(if (open) R.string.older_builds_hide else R.string.older_builds_show, older.size)
        }
        label(false)
        toggle.setOnClickListener {
            val open = rows.visibility != View.VISIBLE
            rows.visibility = if (open) View.VISIBLE else View.GONE
            label(open)
        }
        older.forEach { facts ->
            val plugin = facts.plugin
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(
                TextView(this).apply {
                    val state = if (facts.state == PluginTrustState.APPROVED && !facts.legacyRefused) null
                    else if (facts.legacyRefused) getString(R.string.trust_state_legacy_short)
                    else getString(stateLabel(facts.state, facts.change))
                    text = listOfNotNull(PluginVersions.display(plugin.info.pluginVersion), state).joinToString(" · ")
                    setOnClickListener { showDetails(facts) }
                    isFocusable = true
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (!facts.legacyRefused && facts.state != PluginTrustState.APPROVED && plugin.signerIdentity.isNotBlank()) {
                row.addView(
                    (layoutInflater.inflate(R.layout.item_action_button, row, false) as Button).apply {
                        setText(if (plugin.license != null) R.string.approve_accept_license else R.string.approve)
                        setOnClickListener { approve(plugin) }
                    },
                )
            }
            row.addView(
                (layoutInflater.inflate(R.layout.item_action_button, row, false) as Button).apply {
                    setText(R.string.uninstall)
                    setOnClickListener { remove(plugin, it) }
                },
            )
            rows.addView(row)
        }
        card.addView(toggle)
        card.addView(rows)
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
