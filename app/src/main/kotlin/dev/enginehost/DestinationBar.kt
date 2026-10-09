package dev.enginehost

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The chrome that names the three [Destination]s and says which one this is:
 * a bar along the bottom of a narrow window, a rail along the left edge of a
 * wide or short one ([SizeClass.usesRail]). Touch moves between them; the pad
 * does it with L1 and R1, so no item takes focus (the D-pad moves within a
 * screen's content and never into its chrome).
 */
object DestinationBar {
    fun create(context: Context, current: Destination, rail: Boolean, onPick: (Destination) -> Unit): LinearLayout {
        val res = context.resources
        val bar = LinearLayout(context).apply {
            orientation = if (rail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (rail) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(context, R.color.eh_surface))
            val pad = res.getDimensionPixelSize(R.dimen.eh_space_s)
            setPadding(pad, pad, pad, pad)
        }
        Destination.entries.forEach { destination ->
            bar.addView(item(context, destination, selected = destination == current, rail = rail, onPick), itemParams(context, rail))
        }
        return bar
    }

    private fun itemParams(context: Context, rail: Boolean): LinearLayout.LayoutParams {
        val gap = context.resources.getDimensionPixelSize(R.dimen.eh_space_xs)
        return if (rail) {
            LinearLayout.LayoutParams(context.resources.getDimensionPixelSize(R.dimen.eh_rail_item_width), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, gap, 0, gap) }
        } else {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(gap, 0, gap, 0) }
        }
    }

    private fun item(context: Context, destination: Destination, selected: Boolean, rail: Boolean, onPick: (Destination) -> Unit): View {
        val res = context.resources
        val ink = ContextCompat.getColor(context, if (selected) R.color.eh_on_accent_container else R.color.eh_text_secondary)
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = res.getDimensionPixelSize(R.dimen.eh_touch_target)
            val v = res.getDimensionPixelSize(R.dimen.eh_space_xs) * 2
            setPadding(0, v, 0, v)
            // Touch only: the pad changes destination with L1 and R1.
            isFocusable = false
            isClickable = true
            isSelected = selected
            contentDescription = context.getString(destination.label)
            background = if (selected) {
                GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.eh_accent_container))
                    cornerRadius = res.getDimension(R.dimen.eh_corner_small)
                }
            } else {
                ContextCompat.getDrawable(context, R.drawable.eh_row_bg)
            }
            setOnClickListener { onPick(destination) }
        }
        item.addView(
            ImageView(context).apply {
                setImageResource(destination.icon)
                setColorFilter(ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(res.getDimensionPixelSize(R.dimen.eh_icon), res.getDimensionPixelSize(R.dimen.eh_icon)),
        )
        item.addView(
            TextView(context).apply {
                setText(destination.label)
                setTextAppearance(R.style.TextAppearance_Enginehost_Caption)
                setTextColor(ink)
                gravity = Gravity.CENTER
                maxLines = 1
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return item
    }
}
