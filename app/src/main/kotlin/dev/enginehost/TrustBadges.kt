package dev.enginehost

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The badges a core's card may carry, and only these (#620 E5, after droidtop's
 * design language): Official or Unofficial, and Full access where a core runs
 * without a sandbox. "Sandboxed" is the absence of Full access, never a badge of
 * its own; a runtime component runs inside the engine that loads it and carries no
 * access badge.
 */
object TrustBadges {
    fun origin(badge: TextView, official: Boolean) {
        val (label, container, onContainer) = if (official) {
            Triple(R.string.badge_official, R.color.eh_official_container, R.color.eh_on_official_container)
        } else {
            Triple(R.string.badge_unofficial, R.color.eh_community_container, R.color.eh_on_community_container)
        }
        paint(badge, label, container, onContainer)
    }

    fun access(badge: TextView, sandboxed: Boolean, component: Boolean) {
        if (sandboxed || component) {
            badge.visibility = android.view.View.GONE
            return
        }
        badge.visibility = android.view.View.VISIBLE
        paint(badge, R.string.badge_full_access, R.color.eh_danger_container, R.color.eh_on_danger_container)
    }

    private fun paint(badge: TextView, label: Int, container: Int, onContainer: Int) {
        badge.setText(label)
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(badge.context, container))
        badge.setTextColor(ContextCompat.getColor(badge.context, onContainer))
    }
}
