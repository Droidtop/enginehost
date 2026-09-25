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
        return when (preferences.getString(decisionKey(plugin), null)) {
            "approved" -> PluginTrustState.APPROVED
            "denied" -> PluginTrustState.DENIED
            else -> PluginTrustState.PENDING
        }
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
            .putString(decisionKey(plugin), decision)
            .apply()
    }

    private fun decisionKey(plugin: InstalledPlugin) =
        "decision:${plugin.bundleId}:${plugin.archiveSha256}:${plugin.signerIdentity}"
}
