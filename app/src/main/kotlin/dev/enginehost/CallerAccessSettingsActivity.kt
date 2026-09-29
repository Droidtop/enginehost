package dev.enginehost

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * Every caller package a person has an explicit `LAUNCH` decision for
 * (owner, 2026-09-27), and a way to add one for an app that has not asked
 * yet, or that [CallerDefaults] would otherwise decide silently (a
 * browser, say, before the person ever tries it against a game). See
 * docs/security/2026-09-27-launch-entry-consent.md.
 */
class CallerAccessSettingsActivity : EnginehostActivity() {
    override fun primaryAction(): View? =
        firstSelectable(findViewById(R.id.callerAccessList)) ?: findViewById(R.id.addCallerButton)

    private lateinit var store: CallerAccessStore
    private lateinit var list: ViewGroup
    private lateinit var emptyState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.launch_access_title)
        store = CallerAccessStore(this)
        setContentView(R.layout.activity_caller_access)
        wireBackButton()
        list = findViewById(R.id.callerAccessList)
        emptyState = findViewById(R.id.callerAccessEmpty)
        findViewById<Button>(R.id.addCallerButton).setOnClickListener { offerAddCaller() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val decided = store.all()
        // Every package that has an explicit decision, plus every real
        // caller LaunchActivity has actually seen and not yet decided
        // (CallerSightingsStore) -- package visibility can keep a genuine
        // caller out of offerAddCaller's own query, but it should never
        // keep it off this list once it has really called (owner, build
        // 234 follow-up).
        val keys = (decided.keys + CallerSightingsStore(this).all()).distinct().sortedBy { displayName(it) }
        emptyState.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
        keys.forEach { key -> addRow(key, decided[key]) }
    }

    private fun addRow(key: String, decision: CallerDecision?) {
        val row = layoutInflater.inflate(R.layout.item_caller_access, list, false)
        row.findViewById<TextView>(R.id.callerName).text = displayName(key)
        row.findViewById<TextView>(R.id.callerDecisionValue).setText(
            when (decision) {
                CallerDecision.ALLOW -> R.string.launch_access_allowed
                CallerDecision.BLOCK -> R.string.launch_access_blocked
                null -> R.string.launch_access_ask
            },
        )
        row.setOnClickListener { offerChange(key, decision) }
        list.addView(row)
    }

    private fun displayName(key: String): String =
        if (key == LaunchCaller.UNKNOWN_KEY) getString(R.string.caller_access_unknown_label) else CallerLabels.of(packageManager, key)

    private fun offerChange(key: String, current: CallerDecision?) {
        Sheet(this)
            .title(displayName(key))
            .choice(getString(R.string.launch_access_allow), current = current == CallerDecision.ALLOW) {
                store.setDecision(key, CallerDecision.ALLOW)
                render()
            }
            .choice(
                getString(R.string.launch_access_block),
                current = current == CallerDecision.BLOCK,
                tone = Sheet.Tone.DANGER,
            ) {
                store.setDecision(key, CallerDecision.BLOCK)
                render()
            }
            .choice(R.string.launch_access_remove) {
                store.setDecision(key, null)
                render()
            }
            .show()
    }

    /**
     * The apps worth deciding about, named by their own names and ranked:
     * frontends and launchers, games, other installed apps. The device's own
     * apps wait behind "Show every app" (Droidtop/tracker#33). Read off the
     * main thread: it asks the package manager about every app.
     */
    private fun offerAddCaller() {
        val decided = store.all().keys
        Thread {
            val apps = runCatching { CallerPicker.load(this, decided) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                showAddCaller(apps)
            }
        }.start()
    }

    private fun showAddCaller(all: List<CallerCandidate>) {
        if (all.isEmpty()) {
            Sheet(this).title(R.string.launch_access_add).message(R.string.launch_access_add_empty).choice(R.string.ok) {}.show()
            return
        }
        val relevant = CallerPicker.relevant(all)
        val sheet = Sheet(this).title(R.string.launch_access_add)
        if (relevant.isEmpty()) sheet.message(R.string.launch_access_add_none)
        addCandidates(sheet, relevant)
        if (relevant.size < all.size) {
            sheet.choice(R.string.launch_access_show_all) { showEveryApp(all) }
        }
        sheet.show()
    }

    private fun showEveryApp(all: List<CallerCandidate>) {
        val sheet = Sheet(this).title(R.string.launch_access_add)
        addCandidates(sheet, CallerPicker.everything(all))
        sheet.show()
    }

    /** One row per app: its name, what kind of app it is when that helps, and its package id only where two apps share a name. */
    private fun addCandidates(sheet: Sheet, candidates: List<CallerCandidate>) {
        val labelCounts = candidates.groupingBy { it.label }.eachCount()
        candidates.forEach { candidate ->
            val role = when (candidate.role) {
                CallerCandidate.Role.FRONTEND -> getString(R.string.caller_role_frontend)
                CallerCandidate.Role.GAME -> getString(R.string.caller_role_game)
                CallerCandidate.Role.BLOCKED_BY_DEFAULT -> getString(R.string.caller_role_blocked)
                CallerCandidate.Role.APP -> null
            }
            val detail = listOfNotNull(role, candidate.packageName.takeIf { labelCounts.getValue(candidate.label) > 1 })
                .joinToString(" · ").ifEmpty { null }
            sheet.choice(candidate.label, detail) { offerChange(candidate.packageName, null) }
        }
    }
}
