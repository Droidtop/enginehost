package dev.enginehost

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/**
 * Enginehost's one surface for a choice or a question: a sheet drawn in
 * the app's own look, on the bottom edge while the device is held upright
 * and on the right edge in landscape (design language, Layout). It replaced
 * the stock AlertDialog choosers, whose rows showed no focus, whose
 * all-caps CANCEL was a touch-only button, and whose look belonged to the
 * device rather than to Enginehost (UI assessment 2026-09-24, H5).
 *
 * It takes the pad whole: D-pad moves between the choices, A picks the
 * focused one, B or Escape closes the sheet, and X and Y do nothing rather
 * than falling back to DEL and SPACE ([EnginehostActivity] says why). Its
 * own hint row names A and B and is the touch route to them, so a sheet
 * never needs a Cancel button of its own.
 *
 * A plain platform [Dialog] under a platform theme, so the in-game menu
 * can draw it over any engine's Activity in the `:runtime` process, whose
 * themes Enginehost does not choose.
 */
class Sheet(private val activity: Activity) {
    enum class Tone { NORMAL, DANGER }

    private class Choice(
        val label: CharSequence,
        val detail: CharSequence?,
        val current: Boolean,
        val tone: Tone,
        val pick: () -> Unit,
    )

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private val choices = mutableListOf<Choice>()
    private var onCancel: (() -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null

    fun title(text: CharSequence): Sheet = apply { title = text }
    fun title(@StringRes text: Int): Sheet = title(activity.getString(text))
    fun message(text: CharSequence): Sheet = apply { message = text }
    fun message(@StringRes text: Int): Sheet = message(activity.getString(text))

    /** A view of the caller's own above the choices: a text field, say. */
    fun content(view: View): Sheet = apply { content = view }

    /**
     * One row. Picking it closes the sheet first and then runs [pick], so
     * a choice may open the next sheet. [current] marks the value a
     * single-choice list already holds, and takes the pad's first focus.
     */
    fun choice(
        label: CharSequence,
        detail: CharSequence? = null,
        current: Boolean = false,
        tone: Tone = Tone.NORMAL,
        pick: () -> Unit,
    ): Sheet = apply { choices += Choice(label, detail, current, tone, pick) }

    fun choice(@StringRes label: Int, tone: Tone = Tone.NORMAL, pick: () -> Unit): Sheet =
        choice(activity.getString(label), tone = tone, pick = pick)

    /** Closed without a choice: B, Back, Escape, or a tap outside. */
    fun onCancel(block: () -> Unit): Sheet = apply { onCancel = block }

    /** Closed by any route, a choice included. */
    fun onDismiss(block: () -> Unit): Sheet = apply { onDismiss = block }

    fun show(): Dialog {
        val dialog = Dialog(activity, R.style.Theme_Enginehost_Sheet)
        val root = dialog.layoutInflater.inflate(R.layout.sheet, FrameLayout(dialog.context), false) as LinearLayout
        root.findViewById<TextView>(R.id.sheetTitle).apply {
            text = title
            visibility = if (title == null) View.GONE else View.VISIBLE
        }
        root.findViewById<TextView>(R.id.sheetMessage).apply {
            text = message
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        content?.let { view ->
            root.findViewById<FrameLayout>(R.id.sheetContent).apply {
                visibility = View.VISIBLE
                addView(view)
            }
        }
        val list = root.findViewById<LinearLayout>(R.id.sheetChoices)
        var firstFocus: View? = null
        choices.forEach { choice ->
            val row = dialog.layoutInflater.inflate(R.layout.item_sheet_choice, list, false)
            row.findViewById<TextView>(R.id.choiceLabel).apply {
                text = choice.label
                if (choice.tone == Tone.DANGER) setTextColor(ContextCompat.getColor(activity, R.color.eh_danger))
            }
            row.findViewById<TextView>(R.id.choiceDetail).apply {
                text = choice.detail
                visibility = if (choice.detail == null) View.GONE else View.VISIBLE
            }
            row.findViewById<View>(R.id.choiceSelected).visibility =
                if (choice.current) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog.dismiss()
                choice.pick()
            }
            list.addView(row)
            if (firstFocus == null || choice.current) firstFocus = row
        }
        list.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE

        val hints = HintRow.create(dialog.context)
        HintRow.fill(
            hints,
            listOfNotNull(
                Hint("A", R.string.hint_select) { dialog.currentFocus?.performClick() }.takeIf { choices.isNotEmpty() },
                Hint("B", R.string.back) { dialog.cancel() },
            ),
        )
        root.findViewById<LinearLayout>(R.id.sheetHints).addView(
            hints, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // The hint row's plate is the sheet's own ground, not a screen's.
        hints.background = null

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.setOnKeyListener { _, keyCode, event -> onKey(dialog, keyCode, event) }
        place(dialog)
        dialog.show()
        firstFocus?.requestFocus()
        return dialog
    }

    /**
     * B and Escape close; A presses the focused row. Both edges are taken,
     * so nothing is left for Android's fallbacks to turn into a second,
     * different key. X and Y are swallowed: their fallbacks (DEL, SPACE)
     * would edit a text field or press a row.
     */
    private fun onKey(dialog: Dialog, keyCode: Int, event: KeyEvent): Boolean {
        val up = event.action == KeyEvent.ACTION_UP
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                if (up) dialog.cancel()
                true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (up) dialog.currentFocus?.performClick()
                true
            }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> true
            else -> false
        }
    }

    /** Bottom edge, full width, upright; right edge, full height, capped width, in landscape. */
    private fun place(dialog: Dialog) {
        val window = dialog.window ?: return
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            val metrics = activity.resources.displayMetrics
            val width = (metrics.widthPixels * LANDSCAPE_WIDTH_FRACTION).toInt()
                .coerceIn(activity.resources.getDimensionPixelSize(R.dimen.eh_sheet_min_width),
                    activity.resources.getDimensionPixelSize(R.dimen.eh_sheet_max_width))
                .coerceAtMost(metrics.widthPixels)
            window.setGravity(Gravity.RIGHT or Gravity.FILL_VERTICAL)
            window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            window.setGravity(Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // A sheet with content of its own holds a text field: bring the keyboard with it.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                if (content != null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0,
        )
        // Over a fullscreen game the sheet must not bring the status bar back.
        if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private companion object {
        const val LANDSCAPE_WIDTH_FRACTION = 0.42f
    }
}
