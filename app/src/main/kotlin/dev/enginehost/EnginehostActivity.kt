package dev.enginehost

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Every screen a person moves through in Enginehost, with the [HintRow]
 * docked under it: the printed buttons this screen answers to and what
 * each does here.
 *
 * Every screen names its [primaryAction], and that is where the pad
 * starts: the screen opens with it selected, the first D-pad press after
 * a touch lands on it, and A with nothing selected presses it. Without
 * that, a screen reached by touch (the window is then in touch mode, where
 * nothing is focused) ignored A entirely: the game screen's Play did not
 * answer the pad until something had been selected some other way (rig,
 * dq-coordinator-23 F12), and on Home the first press woke the update
 * notice rather than the library.
 *
 * All four face buttons are taken here rather than left to Android's
 * fallbacks. A's fallback is DPAD_CENTER, which in touch mode only leaves
 * touch mode and presses nothing; X's and Y's are DEL and SPACE, and SPACE
 * presses the focused view, so Y on a game row started the game, and X on
 * a text field deleted a character. B is left to Android, which turns it
 * into Back. A screen gives X and Y a meaning by listing them in [hints].
 * Keys reach these handlers only when no view took them, so a focused
 * view that answers a button itself still gets it first.
 */
abstract class EnginehostActivity : AppCompatActivity() {

    private var hintRow: LinearLayout? = null

    /** The view held down by the A press in progress, released and pressed on its up edge. */
    private var pressing: View? = null

    /**
     * The action this screen exists for, where the pad starts: Play on a
     * game's screen, the first game on Home. Null only while the screen
     * has nothing to act on yet (it is still loading).
     */
    protected abstract fun primaryAction(): View?

    /** This screen's buttons, A first. A screen with a button of its own adds it. */
    protected open fun hints(): List<Hint> = listOf(
        Hint("A", R.string.hint_select) { selection()?.let(::press) },
        Hint("B", R.string.back) { onBackPressedDispatcher.onBackPressed() },
    )

    /** Redraws the row, for a screen whose buttons depend on what is focused. */
    protected fun refreshHints() {
        hintRow?.let(::fill)
    }

    /**
     * Selects [primaryAction] when nothing is selected. A screen whose
     * primary action appears later (a list filled off the main thread)
     * calls this once it is there.
     */
    protected fun selectPrimaryAction() {
        if (currentFocus?.isShown != true) primaryAction()?.requestFocus()
    }

    /** The first view the pad can select inside [group], for a screen whose primary action is a list's first row. */
    protected fun firstSelectable(group: ViewGroup?): View? {
        group ?: return null
        val views = ArrayList<View>()
        group.addFocusables(views, View.FOCUS_DOWN, View.FOCUSABLES_ALL)
        return views.firstOrNull { it.isShown }
    }

    /** Set once the screen has first been shown, when its selection is the primary action whatever Android chose. */
    private var opened = false

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        window.decorView.viewTreeObserver.addOnTouchModeChangeListener(touchModeListener)
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnTouchModeChangeListener(touchModeListener)
        super.onDestroy()
    }

    /** The view currently marked as the window's default focus. */
    private var defaultFocus: View? = null

    /**
     * Leaving touch mode is announced before Android picks the view to
     * focus, so the primary action is made the window's default focus here
     * and Android's own choice lands on it. Focusing it directly instead
     * (as this did first) left Android nothing to choose, so it did not
     * consume the D-pad press that left touch mode, and that press then
     * moved on from the primary action: the ring landed on the SECOND item
     * of Home, Settings and Controller, and on Test in Game setup (rig,
     * dq-ehfix-01).
     */
    private val touchModeListener = ViewTreeObserver.OnTouchModeChangeListener { inTouchMode ->
        if (inTouchMode || currentFocus?.isShown == true) return@OnTouchModeChangeListener
        val primary = primaryAction() ?: return@OnTouchModeChangeListener
        if (defaultFocus !== primary) {
            defaultFocus?.isFocusedByDefault = false
            primary.isFocusedByDefault = true
            defaultFocus = primary
        }
    }

    /**
     * Whether this screen tells the person about a game that crashed while it
     * was away. The launch screen normally does that itself, but Android
     * finishes the launch screen too when the game crashes before it draws
     * (it finishes every not-yet-stopped activity under a crashed one), and
     * the person then lands here with nothing said (rig, dq-ehfix-02).
     */
    protected open val reportsRuntimeCrashes = true

    override fun onResume() {
        super.onResume()
        if (!reportsRuntimeCrashes) return
        val crash = CrashWatch.consume(this) ?: return
        Sheet(this)
            .title(getString(R.string.game_stopped_title, crash.gameFolder.name))
            .message(getString(R.string.launch_crashed, crash.reason))
            .choice(R.string.action_report) {
                startActivity(ProblemReportActivity.intent(this, crash.gameFolder, crash))
            }
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (opened) {
            selectPrimaryAction()
        } else {
            // Android's own first choice is simply the first focusable view
            // (a back arrow, a notice), so the screen's is put in its place.
            opened = true
            primaryAction()?.requestFocus()
        }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in OWNED_BUTTONS) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A && event.repeatCount == 0) {
            pressing = selection()?.also { it.isPressed = true }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in OWNED_BUTTONS) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            val target = pressing
            pressing = null
            target?.isPressed = false
            if (target != null && !event.isCanceled) press(target)
            return true
        }
        val button = if (keyCode == KeyEvent.KEYCODE_BUTTON_X) "X" else "Y"
        hints().firstOrNull { it.button == button }?.press?.invoke()
        return true
    }

    /**
     * What A acts on: the focused view once the pad is in use, and the
     * primary action while the screen is still being touched (the focus a
     * touch leaves behind, such as a text field Android focused on its
     * own, is not a selection the person made).
     */
    private fun selection(): View? {
        val focused = currentFocus?.takeIf { it.isShown }
        return if (window.decorView.isInTouchMode) primaryAction() ?: focused else focused ?: primaryAction()
    }

    /** Shows [target] as the selection (leaving touch mode) and presses it. */
    private fun press(target: View) {
        if (!target.isFocused) target.requestFocusFromTouch()
        target.performClick()
    }

    private fun withHintRow(content: View): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.eh_background))
        }
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = HintRow.create(this)
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hintRow = row
        fill(row)
        return column
    }

    private fun fill(row: LinearLayout) = HintRow.fill(row, hints())

    private companion object {
        val OWNED_BUTTONS = setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)
    }
}
