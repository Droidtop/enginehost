package dev.enginehost

import android.content.Context

enum class PluginTrustState {
    PENDING,
    APPROVED,
    DENIED,
}

/**
 * Trust is local security state, deliberately outside enginehost.json and caller data.
 * Approval binds a bundle ID, its origin, and its repository signing identity:
 * updates from the same origin signed by the same key carry the same approval
 * automatically; a different key or a different origin requires a new prompt.
 */
class PluginTrustStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-trust-v1", Context.MODE_PRIVATE)

    fun state(plugin: InstalledPlugin): PluginTrustState {
        if (plugin.signerIdentity.isBlank()) return PluginTrustState.PENDING
        val carried = carriedDecisions(plugin)
        return PluginTrustDecisions.state(carried, plugin)
    }

    /**
     * What a decision this build would ask for changes relative to the ones
     * its bundle ID already has on record; call it where a PENDING state is
     * about to be shown, so the card can name the change instead of only
     * asking again.
     */
    fun prior(plugin: InstalledPlugin): PluginTrustDecisions.Prior {
        if (plugin.signerIdentity.isBlank()) return PluginTrustDecisions.Prior.NONE
        return PluginTrustDecisions.prior(carriedDecisions(plugin), plugin)
    }

    /**
     * The stored decisions with any legacy digest-bound record carried onto
     * [plugin]'s line, persisted as it goes: the carry is what keeps an
     * already-approved plugin running -- and its next update prompt-free --
     * across the format change.
     */
    private fun carriedDecisions(plugin: InstalledPlugin): Map<String, String> {
        val before = decisions()
        val carried = PluginTrustDecisions.migrated(before, plugin)
        if (carried != before) {
            val editor = preferences.edit()
            (before.keys - carried.keys).forEach(editor::remove)
            carried.forEach { (key, value) -> if (before[key] != value) editor.putString(key, value) }
            editor.apply()
        }
        return carried
    }

    fun approve(plugin: InstalledPlugin) = decide(plugin, "approved")
    fun deny(plugin: InstalledPlugin) = decide(plugin, "denied")
    fun isApproved(plugin: InstalledPlugin): Boolean = state(plugin) == PluginTrustState.APPROVED

    /** Signed by its origin's root-certified key, compiled in or learned from the plugins index. */
    fun isOfficial(plugin: InstalledPlugin): Boolean {
        val keys = PluginOriginKeyStore(context)
        return plugin.signerFingerprints.any { keys.isOfficial(plugin.origin, it) }
    }

    /**
     * The listing a plugin's origin was added from, when it came from the
     * third-party list and is signed by the key that listing named. Shown as
     * Third party with the maintainer's name; never official, and never
     * approved by being listed.
     */
    fun thirdParty(plugin: InstalledPlugin): ThirdPartyOrigin? {
        if (isOfficial(plugin)) return null
        return PluginOriginStore(context).thirdParty(plugin.origin)
            ?.takeIf { listing -> plugin.signerFingerprints.any { it.equals(listing.keySha256, ignoreCase = true) } }
    }

    /**
     * Signed with the primary developer's key rather than the origin's own key.
     * Such a build is deliberately installable, and deliberately never official.
     */
    fun isDeveloperDebug(plugin: InstalledPlugin): Boolean {
        val keys = PluginOriginKeyStore(context)
        return plugin.signerFingerprints.any { keys.isDeveloperDebug(it) }
    }

    private fun decide(plugin: InstalledPlugin, decision: String) {
        require(plugin.signerIdentity.isNotBlank()) { "A plugin without a verified signer cannot be trusted" }
        preferences.edit()
            .putString(PluginTrustDecisions.decisionKey(plugin.bundleId, plugin.origin, plugin.signerIdentity), decision)
            // A decided line makes the digest-bound twin of this exact build dead.
            .remove(PluginTrustDecisions.legacyDecisionKey(plugin.bundleId, plugin.archiveSha256, plugin.signerIdentity))
            .apply()
    }

    private fun decisions(): Map<String, String> =
        preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}

/**
 * The trust-carry-over rule, split from the store's SharedPreferences so it
 * is unit-testable with none of Android's storage: an approval is recorded
 * under the bundle ID, origin and verified signer it was given, and every
 * later build of that bundle from that origin signed by that key inherits it.
 * An update therefore needs a new decision exactly when the origin or the
 * signing key changed, which is what a different decision key means.
 */
internal object PluginTrustDecisions {
    private const val KEY_PREFIX = "decision:"

    fun decisionKey(bundleId: String, origin: String, signerIdentity: String) =
        "$KEY_PREFIX$bundleId:$origin:$signerIdentity"

    /** The pre-carry-over form: a decision bound to one exact archive digest. */
    fun legacyDecisionKey(bundleId: String, archiveSha256: String, signerIdentity: String) =
        "$KEY_PREFIX$bundleId:$archiveSha256:$signerIdentity"

    /**
     * Carries decisions recorded before carry-over existed onto the bundle's
     * line. Those decisions were bound to one exact archive digest, but what
     * the person decided was this bundle ID signed by this key -- the line the
     * new key records -- so every such decision carries once and the
     * digest-bound records go. The current build's own record wins, then a
     * decision the line already has, then any remaining one.
     */
    fun migrated(decisions: Map<String, String>, plugin: InstalledPlugin): Map<String, String> {
        val prefix = "$KEY_PREFIX${plugin.bundleId}:"
        val line = decisionKey(plugin.bundleId, plugin.origin, plugin.signerIdentity)
        // A digest-bound key's middle segment is a SHA-256; a line key's is
        // the origin URL. Only the former are legacy records.
        val legacy = decisions.entries.filter { (key, _) ->
            key.startsWith(prefix) && !key.substringAfter(prefix).contains("://") &&
                key.substringAfterLast(':') == plugin.signerIdentity
        }
        if (legacy.isEmpty()) return decisions
        val carried = legacy.firstOrNull {
            it.key == legacyDecisionKey(plugin.bundleId, plugin.archiveSha256, plugin.signerIdentity)
        }?.value ?: decisions[line] ?: legacy.first().value
        var carriedDecisions = decisions
        legacy.forEach { (key, _) -> carriedDecisions = carriedDecisions - key }
        return carriedDecisions + (line to carried)
    }

    fun state(decisions: Map<String, String>, plugin: InstalledPlugin): PluginTrustState {
        if (plugin.signerIdentity.isBlank()) return PluginTrustState.PENDING
        return when (decisions[decisionKey(plugin.bundleId, plugin.origin, plugin.signerIdentity)]) {
            "approved" -> PluginTrustState.APPROVED
            "denied" -> PluginTrustState.DENIED
            else -> PluginTrustState.PENDING
        }
    }

    /**
     * How a pending decision relates to what this bundle ID already has on
     * record, so the Plugins screen can say what changed rather than only
     * asking again. A different key is a possible rotation or takeover, a
     * different origin a different publisher altogether; both still prompt.
     * Callers pass [migrated] decisions -- a raw digest-bound record of this
     * own signer would masquerade as a different origin.
     */
    enum class Prior { NONE, DIFFERENT_KEY, DIFFERENT_ORIGIN }

    fun prior(decisions: Map<String, String>, plugin: InstalledPlugin): Prior {
        val prefix = "$KEY_PREFIX${plugin.bundleId}:"
        val own = decisionKey(plugin.bundleId, plugin.origin, plugin.signerIdentity)
        // The signer is everything after the last ':' (fingerprints join
        // with '+', never ':'), so a recorded decision shares this build's
        // signer exactly when that suffix matches -- whatever form the key
        // was recorded in.
        val others = decisions.keys.filter { it.startsWith(prefix) && it != own }
        if (others.isEmpty()) return Prior.NONE
        return when {
            others.any { it.substringAfterLast(':') == plugin.signerIdentity } -> Prior.DIFFERENT_ORIGIN
            else -> Prior.DIFFERENT_KEY
        }
    }
}
