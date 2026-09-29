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
     * Carries the person's APPROVED decision from [previous], the build
     * being replaced, to [next], its replacement -- when [next] is provably
     * the same line: the same bundle ID, from the same origin, signed by
     * the same verified key as the build that earned the approval
     * (decided 2026-09-27, docs/plugin-catalog.md "Updates",
     * docs/ui-v2.md section 3). The carried decision is stored for the
     * new archive exactly like a manual Approve, so it still binds the
     * exact digest and signer it names. Nothing is carried from a DENIED
     * or PENDING build, and a different key or origin carries nothing --
     * those replacements prompt for a fresh approval as before.
     *
     * Returns whether [next] is trusted and so needs no prompt.
     */
    fun carryApprovalFrom(previous: InstalledPlugin, next: InstalledPlugin): Boolean {
        if (!isApproved(previous) || !approvalCarries(previous, next)) return false
        approve(next)
        return true
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

        /**
         * The carry-over rule (see [carryApprovalFrom]), split out so a
         * plain JVM test can exercise it the way [effectiveState]'s tests do.
         * [next] must be the same bundle ID from the same origin, and its
         * verified signer must be identical to [previous]'s. The origin is
         * part of the rule because a stored decision names no origin:
         * without the check, two origins sharing a signing key (the
         * developer key) could trade an approval neither of them earned.
         */
        internal fun approvalCarries(previous: InstalledPlugin, next: InstalledPlugin): Boolean =
            previous.signerIdentity.isNotBlank() &&
                previous.bundleId == next.bundleId &&
                previous.origin == next.origin &&
                previous.signerIdentity == next.signerIdentity
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
