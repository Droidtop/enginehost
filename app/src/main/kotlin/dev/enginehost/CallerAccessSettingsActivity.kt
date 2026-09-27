package dev.enginehost

import android.content.Intent
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
        val decisions = store.all().toList().sortedBy { (key, _) -> displayName(key) }
        emptyState.visibility = if (decisions.isEmpty()) View.VISIBLE else View.GONE
        decisions.forEach { (key, decision) -> addRow(key, decision) }
    }

    private fun addRow(key: String, decision: CallerDecision) {
        val row = layoutInflater.inflate(R.layout.item_caller_access, list, false)
        row.findViewById<TextView>(R.id.callerName).text = displayName(key)
        row.findViewById<TextView>(R.id.callerDecisionValue).setText(
            if (decision == CallerDecision.ALLOW) R.string.launch_access_allowed else R.string.launch_access_blocked,
        )
        row.setOnClickListener { offerChange(key, decision) }
        list.addView(row)
    }

    private fun displayName(key: String): String {
        if (key == LaunchCaller.UNKNOWN_KEY) return getString(R.string.caller_access_unknown_label)
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(key, 0))
        }.getOrNull()
        return if (label != null) "$label ($key)" else key
    }

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

    /** Every launchable app on the device besides this one, droidtop and whatever already has a decision -- picking one starts deciding it. */
    private fun offerAddCaller() {
        val decided = store.all().keys
        val apps = runCatching {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .filter { it != packageName && it != TrustedCallers.DROIDTOP_PACKAGE && it !in decided }
                .sortedBy { displayName(it) }
        }.getOrDefault(emptyList())
        if (apps.isEmpty()) {
            Sheet(this).title(R.string.launch_access_add).message(R.string.launch_access_add_empty).choice(R.string.ok) {}.show()
            return
        }
        val sheet = Sheet(this).title(R.string.launch_access_add)
        apps.forEach { pkg -> sheet.choice(displayName(pkg)) { offerChange(pkg, null) } }
        sheet.show()
    }
}
