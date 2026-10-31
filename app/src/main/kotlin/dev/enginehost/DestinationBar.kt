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
 * The tab bar along the top of every destination screen: the four [Destination]s in a row, this
 * one marked, between the L1 and R1 glyphs that move left and right along it. The bar runs the way
 * the shoulder buttons move, on every window size (owner, Droidtop/tracker#644: "move the
 * enginehost tab bar to the top or bottom, so it's more consistent with how it actually moves,
 * mapped to l1 and r1"). Touch picks a tab or presses a glyph; no item takes focus, so the D-pad
 * moves within a screen's content and never into its chrome.
 */
object DestinationBar {
    fun create(context: Context, current: Destination, onPick: (Destination) -> Unit): LinearLayout {
        val res = context.resources
        val gap = res.getDimensionPixelSize(R.dimen.eh_space_xs)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.eh_surface))
            val h = res.getDimensionPixelSize(R.dimen.eh_screen_padding_h)
            setPadding(h, gap, h, gap)
        }
        bar.addView(shoulder(context, "L1") { onPick(Destinations.neighbour(current, -1)) })
        Destination.entries.forEach { destination ->
            bar.addView(
                tab(context, destination, selected = destination == current, onPick),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(gap, 0, gap, 0) },
            )
        }
        bar.addView(shoulder(context, "R1") { onPick(Destinations.neighbour(current, 1)) })
        return bar
    }

    /** L1 or R1 as the hint row prints a button, pressable by touch like a hint. */
    private fun shoulder(context: Context, button: String, press: () -> Unit): View =
        TextView(context, null, 0, R.style.Widget_Enginehost_HintGlyph).apply {
            text = button
            gravity = Gravity.CENTER
            minimumHeight = resources.getDimensionPixelSize(R.dimen.eh_touch_target)
            isClickable = true
            isFocusable = false
            contentDescription = context.getString(if (button == "L1") R.string.tab_previous else R.string.tab_next)
            setOnClickListener { press() }
        }

    private fun tab(context: Context, destination: Destination, selected: Boolean, onPick: (Destination) -> Unit): View {
        val res = context.resources
        val ink = AccentViewInflater.colour(context, if (selected) R.color.eh_on_accent_container else R.color.eh_text_secondary)
        val space = res.getDimensionPixelSize(R.dimen.eh_space_xs)
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = res.getDimensionPixelSize(R.dimen.eh_touch_target)
            setPadding(space, 0, space, 0)
            // Touch only: the pad changes destination with L1 and R1.
            isFocusable = false
            isClickable = true
            isSelected = selected
            contentDescription = context.getString(destination.label)
            background = if (selected) {
                GradientDrawable().apply {
                    setColor(AccentViewInflater.colour(context, R.color.eh_accent_container))
                    cornerRadius = res.getDimension(R.dimen.eh_corner_small)
                }
            } else {
                AccentViewInflater.drawable(context, R.drawable.eh_row_bg)
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
                maxLines = 1
                setPadding(space, 0, 0, 0)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return item
    }
}
