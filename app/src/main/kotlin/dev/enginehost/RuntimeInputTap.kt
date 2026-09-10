package dev.enginehost

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * The host's first look at controller input, in both plugin shapes.
 *
 * Enginehost runs games two ways. A plugin-api plugin runs inside
 * [RuntimeActivity], which already sees every event in its own
 * `dispatchKeyEvent` before [RuntimeControllerRouter] applies the map. A
 * bundled-activity plugin (KiriKiri, and every other engine that brings
 * its own Activity) is that Activity: nothing of ours is on the path at
 * all, and the pad reaches the engine without the host ever being asked.
 *
 * The smallest hook that puts the host ahead of both is the window
 * callback. Every Activity installs itself as its own [Window.Callback] in
 * `attach()`, and the decor view hands key and generic-motion events to
 * that callback before the Activity's own dispatch, before any view, and
 * before any native surface. Wrapping the callback the moment an Activity
 * in the `:runtime` process is created therefore gives the host first look
 * without a line of plugin code changing and without a second dispatch
 * path: [RuntimeActivity] keeps its router, bundled activities keep their
 * own input handling, and both of them now sit downstream of this.
 *
 * Order here is the whole point, and it is RetroArch's order: the host
 * menu combination is checked first, ahead of the per-engine action map
 * and ahead of bypass's raw forwarding, so the shortcut means the same
 * thing in every engine whether or not that engine reads the pad itself.
 *
 * The one shape this does not reach is a plugin built on `NativeActivity`
 * or its own `InputQueue`, which takes events from the looper rather than
 * from the view hierarchy. No installed plugin is built that way.
 */
class RuntimeInputTap(private val activity: Activity) {
    private val combo = HostMenuCombo(HostMenuHotkeyStore(activity).combo())

    /**
     * @param forward how to deliver an event the host is synthesising --
     *   the release of a button whose press had already reached the game
     *   when the combination completed.
     * @return the event to hand on, or null when the host has taken it.
     */
    fun key(event: KeyEvent, forward: (KeyEvent) -> Unit): KeyEvent? {
        if (!event.isControllerInput()) return event
        val verdict = when (event.action) {
            KeyEvent.ACTION_DOWN -> combo.down(event.keyCode)
            KeyEvent.ACTION_UP -> combo.up(event.keyCode)
            else -> HostMenuCombo.Verdict.Pass
        }
        return when (verdict) {
            is HostMenuCombo.Verdict.Open -> {
                verdict.stuck.forEach { forward(release(event, it)) }
                HostMenu.show(activity)
                null
            }
            HostMenuCombo.Verdict.Consume -> null
            HostMenuCombo.Verdict.Pass -> event
        }
    }

    fun motion(event: MotionEvent): MotionEvent? = event

    private fun release(source: KeyEvent, keyCode: Int) = KeyEvent(
        source.downTime, source.eventTime, KeyEvent.ACTION_UP, keyCode, 0,
        source.metaState, source.deviceId, 0, source.flags, source.source,
    )
}

/**
 * Delegates the whole of [Window.Callback] to the Activity, having given
 * [tap] the two methods that carry controller input.
 */
private class HostWindowCallback(
    private val delegate: Window.Callback,
    private val tap: RuntimeInputTap,
) : Window.Callback {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val onward = tap.key(event) { delegate.dispatchKeyEvent(it) } ?: return true
        return delegate.dispatchKeyEvent(onward)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val onward = tap.motion(event) ?: return true
        return delegate.dispatchGenericMotionEvent(onward)
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = delegate.dispatchKeyShortcutEvent(event)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = delegate.dispatchTouchEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent): Boolean = delegate.dispatchTrackballEvent(event)
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
        delegate.dispatchPopulateAccessibilityEvent(event)
    override fun onCreatePanelView(featureId: Int): View? = delegate.onCreatePanelView(featureId)
    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = delegate.onCreatePanelMenu(featureId, menu)
    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
        delegate.onPreparePanel(featureId, view, menu)
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = delegate.onMenuOpened(featureId, menu)
    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean =
        delegate.onMenuItemSelected(featureId, item)
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) =
        delegate.onWindowAttributesChanged(attrs)
    override fun onContentChanged() = delegate.onContentChanged()
    override fun onWindowFocusChanged(hasFocus: Boolean) = delegate.onWindowFocusChanged(hasFocus)
    override fun onAttachedToWindow() = delegate.onAttachedToWindow()
    override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()
    override fun onPanelClosed(featureId: Int, menu: Menu) = delegate.onPanelClosed(featureId, menu)
    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = delegate.onSearchRequested(searchEvent)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? =
        delegate.onWindowStartingActionMode(callback)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        delegate.onWindowStartingActionMode(callback, type)
    override fun onActionModeStarted(mode: ActionMode?) = delegate.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode?) = delegate.onActionModeFinished(mode)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)
}

/**
 * Installs the tap on every Activity the `:runtime` process shows for a
 * game. Registered by [EnginehostApplication] in that process only; the
 * host's own screens are not games and have nothing to intercept.
 */
object RuntimeInputInstaller : Application.ActivityLifecycleCallbacks {
    private fun install(activity: Activity) {
        if (activity.intent?.hasExtra(RuntimeActivity.EXTRA_PLUGIN_BUNDLE) != true) return
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is HostWindowCallback) return
        window.callback = HostWindowCallback(current, RuntimeInputTap(activity))
    }

    // Created covers every Activity; started covers the ones that replace
    // their own callback during onCreate (AppCompat does), and is a no-op
    // once the wrapper is in place.
    override fun onActivityCreated(activity: Activity, state: Bundle?) = install(activity)
    override fun onActivityStarted(activity: Activity) = install(activity)
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
