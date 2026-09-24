package dev.enginehost

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Every screen a person moves through in Enginehost, with the hint row
 * docked under it: the printed buttons this screen answers to and what
 * each does here. The row is also the touch route to those buttons (the
 * design language's rule), so each hint is pressable.
 *
 * A and B are the pad's own: Android turns an unhandled A into a press
 * on the focused view and an unhandled B (or Escape) into Back, and every
 * screen and dialog here already answers those. X and Y are not left to
 * Android, whose fallbacks for them are DEL and SPACE: SPACE presses the
 * focused view, so Y on a game row started the game, and X on a text
 * field deleted a character. Both edges of both are taken here, and a
 * screen gives them a meaning by listing them in [hints].
 */
abstract class EnginehostActivity : AppCompatActivity() {

    /** One button's hint: the button as printed, what it does on this screen, and the doing. */
    class Hint(val button: String, @StringRes val label: Int, val press: () -> Unit)

    private var hintRow: LinearLayout? = null

    /** This screen's buttons, A first. A screen with a button of its own adds it. */
    protected open fun hints(): List<Hint> = listOf(
        Hint("A", R.string.hint_select) { currentFocus?.performClick() },
        Hint("B", R.string.back) { onBackPressedDispatcher.onBackPressed() },
    )

    /** Redraws the row, for a screen whose buttons depend on what is focused. */
    protected fun refreshHints() {
        hintRow?.let(::fill)
    }

    override fun setContentView(layoutResID: Int) {
        setContentView(layoutInflater.inflate(layoutResID, FrameLayout(this), false))
    }

    override fun setContentView(view: View) {
        super.setContentView(withHintRow(view))
    }

    override fun setContentView(view: View, params: ViewGroup.LayoutParams) {
        view.layoutParams = params
        super.setContentView(withHintRow(view))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode in OWNED_BUTTONS) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in OWNED_BUTTONS) return super.onKeyUp(keyCode, event)
        val button = if (keyCode == KeyEvent.KEYCODE_BUTTON_X) "X" else "Y"
        hints().firstOrNull { it.button == button }?.press?.invoke()
        return true
    }

    private fun withHintRow(content: View): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.eh_background))
        }
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.eh_surface))
            val h = resources.getDimensionPixelSize(R.dimen.eh_screen_padding_h)
            setPadding(h, 0, h, 0)
        }
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hintRow = row
        fill(row)
        return column
    }

    private fun fill(row: LinearLayout) {
        row.removeAllViews()
        val space = resources.getDimensionPixelSize(R.dimen.eh_space_s)
        for (hint in hints()) {
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = resources.getDimensionPixelSize(R.dimen.eh_touch_target)
                setPadding(space, 0, space, 0)
                setBackgroundResource(R.drawable.eh_row_bg)
                // Pressable by touch, never a stop for the D-pad: the row
                // names the pad's buttons, it is not somewhere to move to.
                isClickable = true
                isFocusable = false
                contentDescription = getString(hint.label)
                setOnClickListener { hint.press() }
            }
            pill.addView(TextView(this, null, 0, R.style.Widget_Enginehost_Chip).apply { text = hint.button })
            pill.addView(
                TextView(this).apply {
                    setText(hint.label)
                    setTextAppearance(R.style.TextAppearance_Enginehost_Caption)
                    setPadding(space, 0, 0, 0)
                },
            )
            row.addView(pill)
        }
    }

    private companion object {
        val OWNED_BUTTONS = setOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)
    }
}
