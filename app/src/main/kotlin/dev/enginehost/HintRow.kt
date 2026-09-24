package dev.enginehost

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/** One button's hint: the button as printed, what it does here, and the doing. */
class Hint(val button: String, @StringRes val label: Int, val press: () -> Unit)

/**
 * The row of hints docked under a screen or a sheet: the printed buttons
 * it answers to and what each does there. It is also the touch route to
 * those buttons (the design language's rule), so each hint is pressable.
 * Screens ([EnginehostActivity]) and sheets ([Sheet]) both draw theirs
 * here, so the row looks and behaves the same wherever it is.
 */
object HintRow {
    fun create(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.eh_surface))
        val h = resources.getDimensionPixelSize(R.dimen.eh_screen_padding_h)
        setPadding(h, 0, h, 0)
    }

    fun fill(row: LinearLayout, hints: List<Hint>) {
        val context = row.context
        row.removeAllViews()
        val space = context.resources.getDimensionPixelSize(R.dimen.eh_space_s)
        for (hint in hints) {
            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = resources.getDimensionPixelSize(R.dimen.eh_touch_target)
                setPadding(space, 0, space, 0)
                setBackgroundResource(R.drawable.eh_row_bg)
                // Pressable by touch, never a stop for the D-pad: the row
                // names the pad's buttons, it is not somewhere to move to.
                isClickable = true
                isFocusable = false
                contentDescription = context.getString(hint.label)
                setOnClickListener { hint.press() }
            }
            pill.addView(TextView(context, null, 0, R.style.Widget_Enginehost_Chip).apply { text = hint.button })
            pill.addView(
                TextView(context).apply {
                    setText(hint.label)
                    setTextAppearance(R.style.TextAppearance_Enginehost_Caption)
                    setPadding(space, 0, 0, 0)
                },
            )
            row.addView(pill)
        }
    }
}
