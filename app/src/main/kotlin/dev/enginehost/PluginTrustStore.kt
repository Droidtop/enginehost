package dev.enginehost

import android.content.Context

enum class PluginTrustState {
    PENDING,
    APPROVED,
    DENIED,
}

/**
 * Trust is local security state, deliberately outside enginehost.json and caller data.
 * Approval binds a bundle ID and exact archive digest to its repository signing identity.
 */
class PluginTrustStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-trust-v1", Context.MODE_PRIVATE)

    fun state(plugin: InstalledPlugin): PluginTrustState {
        if (plugin.signerIdentity.isBlank()) return PluginTrustState.PENDING
        val stored = preferences.getString(decisionKey(plugin), null)
        // The origin lookups are only needed when the person has not decided.
        return effectiveState(stored, official = stored == null && isOfficial(plugin))
    }

    fun approve(plugin: InstalledPlugin) = decide(plugin, "approved")
    fun deny(plugin: InstalledPlugin) = decide(plugin, "denied")
    fun isApproved(plugin: InstalledPlugin): Boolean = state(plugin) == PluginTrustState.APPROVED

    /**
     * An update keeps the person's approval when it is signed by the same key
     * from the same repository as a build they approved (Droidtop/tracker#23):
     * the installer only ever replaces a build with a newer one from its own
     * origin, so what is left to check is that the signer did not change. A
     * different key, or a build the person denied, still asks.
     */
    fun carryApproval(previous: List<InstalledPlugin>, updated: InstalledPlugin) {
        if (updated.signerIdentity.isBlank()) return
        if (previous.any { shouldCarry(preferences.getString(decisionKey(it), null), it, updated) }) approve(updated)
    }

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

    companion object {
        /** Whether [updated] inherits [previous]'s stored decision. Only an explicit approval carries. */
        internal fun shouldCarry(stored: String?, previous: InstalledPlugin, updated: InstalledPlugin): Boolean =
            stored == "approved" && previous.signerIdentity.isNotBlank() &&
                previous.signerIdentity == updated.signerIdentity && previous.origin == updated.origin

        /**
         * The person's own decision always wins. With none, a bundle signed by
         * its origin's root-certified key is approved: Enginehost already
         * verified where it came from to give it the Official badge, so a fresh
         * install is not a wall of identical Approve prompts (Droidtop/tracker#34).
         * Third-party, community and developer-key builds stay pending until the
         * person approves them, exactly as before.
         */
        internal fun effectiveState(stored: String?, official: Boolean): PluginTrustState = when (stored) {
            "approved" -> PluginTrustState.APPROVED
            "denied" -> PluginTrustState.DENIED
            else -> if (official) PluginTrustState.APPROVED else PluginTrustState.PENDING
        }
    }

    private fun decide(plugin: InstalledPlugin, decision: String) {
        require(plugin.signerIdentity.isNotBlank()) { "A plugin without a verified signer cannot be trusted" }
        preferences.edit()
            .putString(decisionKey(plugin), decision)
            .apply()
    }

    private fun decisionKey(plugin: InstalledPlugin) =
        "decision:${plugin.bundleId}:${plugin.archiveSha256}:${plugin.signerIdentity}"
}
