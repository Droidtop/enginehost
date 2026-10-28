package dev.enginehost

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * The accent-coloured drawables, built for an [AccentPalette] other than the shipped
 * one: the same states, shapes and insets as their XML in res/drawable, with the
 * palette's colours in place of `eh_accent*`, `eh_focus_ring` and `eh_focus_fill`. The
 * XML stays the look for Enginehost's own accent and for every view the painter does
 * not reach ([AccentViewInflater]); a change to one of these drawables is made in both,
 * and `scripts/check-accent-references.py` keeps the accent colours to these files.
 */
class AccentDrawables(private val context: Context, private val palette: AccentPalette) {
    /** The painted stand-in for drawable [id], or null when it carries no accent. */
    fun forDrawable(@DrawableRes id: Int): Drawable? = when (id) {
        R.drawable.eh_btn_primary_bg -> primaryButton()
        R.drawable.eh_btn_secondary_bg -> secondaryButton()
        R.drawable.eh_btn_tonal_bg -> tonalButton()
        R.drawable.eh_card_focusable_bg -> card(focusedState = android.R.attr.state_focused)
        R.drawable.eh_card_selectable_bg -> card(focusedState = android.R.attr.state_selected)
        R.drawable.eh_row_bg -> row()
        R.drawable.eh_edittext_bg -> editText()
        else -> null
    }

    /** The painted stand-in for colour [id] (a colour or a selector), or null when it is no accent colour. */
    fun forColour(@ColorRes id: Int): ColorStateList? = when (id) {
        R.color.eh_accent, R.color.eh_focus_ring -> ColorStateList.valueOf(palette.accent)
        R.color.eh_accent_pressed -> ColorStateList.valueOf(palette.accentPressed)
        R.color.eh_on_accent -> ColorStateList.valueOf(palette.onAccent)
        R.color.eh_accent_container, R.color.eh_focus_fill -> ColorStateList.valueOf(palette.container)
        R.color.eh_accent_container_pressed -> ColorStateList.valueOf(palette.containerPressed)
        R.color.eh_on_accent_container -> ColorStateList.valueOf(palette.onContainer)
        R.color.eh_button_primary_text -> ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colour(R.color.eh_text_secondary), palette.onAccent),
        )
        else -> null
    }

    private fun primaryButton() = states(
        intArrayOf(-android.R.attr.state_enabled) to shape(colour(R.color.eh_surface_variant)),
        intArrayOf(android.R.attr.state_pressed) to shape(palette.accentPressed),
        // The ring outside the fill with ground between, as in the XML: an accent ring on an accent fill would not show.
        intArrayOf(android.R.attr.state_focused) to LayerDrawable(
            arrayOf(
                shape(Color.TRANSPARENT, strokeWidth = px(R.dimen.eh_focus_stroke), stroke = palette.accent),
                shape(palette.accentPressed, radius = dp(6)),
            ),
        ).apply { setLayerInset(1, dp(5), dp(5), dp(5), dp(5)) },
        intArrayOf() to shape(palette.accent),
    )

    private fun secondaryButton() = states(
        intArrayOf(-android.R.attr.state_enabled) to
            shape(colour(R.color.eh_surface_variant), strokeWidth = dp(1), stroke = colour(R.color.eh_outline)),
        intArrayOf(android.R.attr.state_pressed) to
            shape(colour(R.color.eh_surface_variant), strokeWidth = dp(1), stroke = palette.accent),
        intArrayOf(android.R.attr.state_focused) to
            shape(palette.container, strokeWidth = px(R.dimen.eh_focus_stroke), stroke = palette.accent),
        intArrayOf() to shape(colour(R.color.eh_surface), strokeWidth = dp(1), stroke = colour(R.color.eh_outline)),
    )

    private fun tonalButton() = states(
        intArrayOf(-android.R.attr.state_enabled) to shape(colour(R.color.eh_surface_variant)),
        intArrayOf(android.R.attr.state_pressed) to shape(palette.containerPressed),
        intArrayOf(android.R.attr.state_focused) to
            shape(palette.containerPressed, strokeWidth = px(R.dimen.eh_focus_stroke), stroke = palette.accent),
        intArrayOf() to shape(palette.container),
    )

    private fun card(focusedState: Int): Drawable {
        val radius = px(R.dimen.eh_corner)
        return states(
            intArrayOf(android.R.attr.state_pressed) to
                shape(colour(R.color.eh_surface_variant), radius = radius, strokeWidth = dp(1), stroke = palette.accent),
            intArrayOf(focusedState) to
                shape(palette.container, radius = radius, strokeWidth = px(R.dimen.eh_focus_stroke), stroke = palette.accent),
            intArrayOf() to
                shape(colour(R.color.eh_surface), radius = radius, strokeWidth = dp(1), stroke = colour(R.color.eh_outline)),
        )
    }

    private fun row() = states(
        intArrayOf(android.R.attr.state_pressed) to shape(colour(R.color.eh_surface_variant)),
        // The fill with the 4 dp leading bar, 8 dp in from top and bottom, as the XML.
        intArrayOf(android.R.attr.state_focused) to LayerDrawable(
            arrayOf(shape(palette.container), shape(palette.accent, radius = dp(2))),
        ).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerWidth(1, px(R.dimen.eh_focus_bar))
            setLayerInsetTop(1, dp(8))
            setLayerInsetBottom(1, dp(8))
        },
        intArrayOf() to ColorDrawable(Color.TRANSPARENT),
    )

    private fun editText() = states(
        intArrayOf(android.R.attr.state_focused) to
            shape(palette.container, strokeWidth = px(R.dimen.eh_focus_stroke), stroke = palette.accent),
        intArrayOf() to shape(colour(R.color.eh_surface), strokeWidth = dp(1), stroke = colour(R.color.eh_outline)),
    )

    private fun states(vararg items: Pair<IntArray, Drawable>) = StateListDrawable().apply {
        items.forEach { (state, drawable) -> addState(state, drawable) }
    }

    private fun shape(fill: Int, radius: Int = px(R.dimen.eh_corner_small), strokeWidth: Int = 0, stroke: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, stroke)
        }

    private fun colour(@ColorRes id: Int) = ContextCompat.getColor(context, id)
    private fun px(@DimenRes id: Int) = context.resources.getDimensionPixelSize(id)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
