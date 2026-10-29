package dev.enginehost

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SearchEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.WeakHashMap

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
    private val profiles = ControllerProfileStore(activity)
    private val cache = mutableMapOf<Int, ControllerProfile>()

    private val bindings = ControllerBindingStore(activity, HostMenu.scopeOf(activity), HostMenu.gameOf(activity))

    /**
     * Whether this session corrects the pad at all.
     *
     * Bypass means the engine reads the raw pad, and a profile is not raw,
     * so a bypassed scope gets none unless the person has said they want
     * one there. Everything else gets it, ahead of the action map: the
     * profile answers "which control is this", the map answers "what does
     * it do here", and asking the second before the first is how a pad
     * that lies ends up mapped twice.
     *
     * Asked on every event rather than settled at construction, because
     * bypass can be turned over from the in-game menu while the game is
     * running and the store now answers both processes alike.
     */
    private val corrects: Boolean
        get() = !bindings.isBypassed() || profiles.appliesInBypass()

    private fun profile(deviceId: Int): ControllerProfile {
        if (!corrects) return ControllerProfile.NONE
        return cache.getOrPut(deviceId) {
            InputDevice.getDevice(deviceId)?.let(profiles::forDevice) ?: ControllerProfile.NONE
        }
    }

    /**
     * @param forward how to deliver an event the host is synthesising --
     *   the release of a button whose press had already reached the game
     *   when the combination completed.
     * @return the event to hand on, or null when the host has taken it.
     */
    fun key(event: KeyEvent, forward: (KeyEvent) -> Unit): KeyEvent? {
        // Back is the host's in every engine (Droidtop/tracker#289): SDL and
        // other engines swallow it, which left Home as the only way out. It
        // opens the in-game menu (Resume, Controller settings, Quit), and
        // the engine never sees half of a press.
        if (HostBack.owns(event.keyCode)) {
            if (HostBack.opensMenu(event.keyCode, event.action, event.isCanceled)) HostMenu.show(activity)
            return null
        }
        if (!event.isControllerInput()) return event
        val corrected = profile(event.deviceId).apply(event)
        val verdict = when (corrected.action) {
            KeyEvent.ACTION_DOWN -> combo.down(corrected.keyCode)
            KeyEvent.ACTION_UP -> combo.up(corrected.keyCode)
            else -> HostMenuCombo.Verdict.Pass
        }
        return when (verdict) {
            is HostMenuCombo.Verdict.Open -> {
                verdict.stuck.forEach { forward(release(corrected, it)) }
                HostMenu.show(activity)
                null
            }
            HostMenuCombo.Verdict.Consume -> null
            HostMenuCombo.Verdict.Pass -> corrected
        }
    }

    fun motion(event: MotionEvent): MotionEvent? = profile(event.deviceId).apply(event)

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

    private val handler = Handler(Looper.getMainLooper())

    /** Every controller press lasts at least [MinimumPress.MINIMUM_MS] for the engine (#618 C10). */
    private val presses = MinimumPress<KeyEvent>(
        now = SystemClock::uptimeMillis,
        later = { delay, run -> handler.postDelayed(run, delay) },
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val onward = tap.key(event) { delegate.dispatchKeyEvent(it) } ?: return true
        if (!onward.isControllerInput()) return delegate.dispatchKeyEvent(onward)
        return when {
            onward.action == KeyEvent.ACTION_DOWN && onward.repeatCount == 0 -> {
                presses.down(onward.keyCode)
                delegate.dispatchKeyEvent(onward)
            }
            onward.action == KeyEvent.ACTION_UP -> {
                // A held release is delivered later as a copy: the framework may reuse this one.
                var answer = true
                val now = presses.up(onward.keyCode, KeyEvent(onward)) { answer = delegate.dispatchKeyEvent(it) }
                if (now) answer else true
            }
            else -> delegate.dispatchKeyEvent(onward)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val onward = tap.motion(event) ?: return true
        // A corrected event is a fresh one, and this is the only place that
        // knows when everything downstream has finished with it.
        return try {
            delegate.dispatchGenericMotionEvent(onward)
        } finally {
            if (onward !== event) onward.recycle()
        }
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
 * Whether a copied corner of a surface is still black: every pixel's
 * channels below a threshold dither could not cross. Pure, so the rule that
 * ends [RuntimeLoadingNotice] is testable without a device.
 */
internal fun isBlankFrame(pixels: IntArray): Boolean = pixels.all { p ->
    ((p shr 16) and 0xFF) < BLANK_CHANNEL && ((p shr 8) and 0xFF) < BLANK_CHANNEL && (p and 0xFF) < BLANK_CHANNEL
}

private const val BLANK_CHANNEL = 12

/**
 * "Loading <game>" over a game's window until its engine draws something.
 *
 * The launch screen stops showing the moment the runtime's window is up,
 * and a heavy RPG Maker game then sat on a black window for about a minute
 * with nothing to say it was working (Droidtop/tracker#289). The notice is
 * a small bar at the bottom, not a cover, so a game that starts on a black
 * intro is never hidden by it; it goes when the first non-black frame
 * reaches the surface, when no surface can be watched for a while, or after
 * [GIVE_UP_MS] whatever happens. Never takes touches.
 *
 * It is also what takes the game's own loading screen away ([LoadingCover]):
 * the moment a surface has had any frame posted, black or not, which is the
 * core's first frame. A window with no surface to watch (a web view, an
 * isolated runtime) loses the cover after [NO_SURFACE_COVER_MS], since
 * nothing could say when to lift it and it must not hide a game for good.
 * While the game's own picture is up the bar is not drawn over it.
 *
 * It is also how Enginehost knows a game drew at all. Its first readable
 * frame and its first drawn one are marked in the session note
 * ([CrashWatch.WATCHED], [CrashWatch.DRAWN]), so a game that closes without
 * ever drawing is reported as a failure; and a surface still black after
 * [BLACK_SCREEN_MS] asks the person, once, whether to keep waiting
 * ([GameTroubleActivity]). An engine with no SurfaceView is not watched.
 */
internal class RuntimeLoadingNotice(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.uptimeMillis()
    private val sample = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(SAMPLE * SAMPLE)
    private var done = false
    private var watched = false
    private var asked = false
    private val bar: View = buildBar()

    fun start() {
        handler.post(::poll)
    }

    private fun buildBar(): View {
        val density = activity.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val size = (22 * density).toInt()
        val name = activity.intent?.getStringExtra(RuntimeActivity.EXTRA_GAME)?.let { File(it).name }.orEmpty()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(0xB0000000.toInt())
            addView(ProgressBar(activity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(size, size))
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.runtime_loading, name)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(pad / 2, 0, 0, 0)
                },
            )
        }
    }

    private fun poll() {
        if (done) return
        if (activity.isFinishing || activity.isDestroyed) return finish()
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (elapsed > GIVE_UP_MS) return finish()
        // An Activity that set its content after this started cleared the bar.
        if (bar.parent == null && !LoadingCover.coversBar) {
            activity.addContentView(
                bar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = (24 * activity.resources.displayMetrics.density).toInt() },
            )
        }
        // Every surface in the window, not only the first: an engine may keep
        // another SurfaceView beside the one it draws on, and a drawn game was
        // read as black when the first one found was not it (#618 C11: the sheet
        // appeared over a fully drawn mkxp-z title).
        val surfaces = mutableListOf<SurfaceView>().also { collectSurfaces(activity.window.decorView, it) }
        val valid = surfaces.filter { it.holder.surface.isValid }
        when {
            surfaces.isEmpty() -> {
                if (elapsed > NO_SURFACE_COVER_MS) LoadingCover.lift()
                if (elapsed > NO_SURFACE_MS) finish() else again()
            }
            valid.isEmpty() -> {
                nothingShown()
                again()
            }
            else -> look(valid, 0, readAny = false)
        }
    }

    /** Copies [surfaces] one after another; the game has drawn when any of them shows something. */
    private fun look(surfaces: List<SurfaceView>, index: Int, readAny: Boolean) {
        if (done) return
        if (index >= surfaces.size) {
            if (readAny) nothingShown()
            again()
            return
        }
        try {
            PixelCopy.request(
                surfaces[index], sample,
                PixelCopy.OnPixelCopyFinishedListener { result ->
                    if (done) return@OnPixelCopyFinishedListener
                    // NO_DATA: the surface has never had a frame posted, which is a
                    // blank screen too, and the likeliest one for a game that stalls
                    // before it draws (rig, 2026-10-23: Godot 4.5 for 2.5 minutes).
                    val read = result == PixelCopy.SUCCESS || result == PixelCopy.ERROR_SOURCE_NO_DATA
                    val drawn = result == PixelCopy.SUCCESS && run {
                        sample.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
                        !isBlankFrame(pixels)
                    }
                    // Any posted frame is the core's first: the loading screen has done its job.
                    if (result == PixelCopy.SUCCESS) LoadingCover.lift()
                    // Both are host events too: the core harness waits on the second, not
                    // on what a screenshot shows, which the loading screen also passes.
                    if (read && !watched) {
                        watched = true
                        CrashWatch.mark(activity, CrashWatch.WATCHED)
                        HostEvents.record(activity, SCREEN_WATCHED)
                    }
                    if (drawn) {
                        CrashWatch.mark(activity, CrashWatch.DRAWN)
                        HostEvents.record(activity, FIRST_FRAME)
                        finish()
                    } else {
                        look(surfaces, index + 1, readAny || read)
                    }
                },
                handler,
            )
        } catch (e: IllegalArgumentException) {
            // The surface went away between the check and the copy.
            look(surfaces, index + 1, readAny = true)
        }
    }

    /**
     * The game's surface shows nothing: black, never posted a frame, or
     * never became valid. Long past a normal start, with the game in front,
     * the person is asked, once, and the game keeps running.
     */
    private fun nothingShown() {
        if (asked || !RuntimeWatchdog.inFront || SystemClock.uptimeMillis() - startedAt < BLACK_SCREEN_MS) return
        asked = true
        GameTroubleActivity.ask(
            activity, GameTroubleActivity.BLACK_SCREEN, activity.intent?.getStringExtra(RuntimeActivity.EXTRA_GAME),
        )
    }

    private fun again() {
        if (!done) handler.postDelayed(::poll, if (LoadingCover.showing) COVER_POLL_MS else POLL_MS)
    }

    private fun finish() {
        done = true
        LoadingCover.lift()
        handler.removeCallbacksAndMessages(null)
        (bar.parent as? ViewGroup)?.removeView(bar)
        sample.recycle()
    }

    private fun collectSurfaces(view: View, into: MutableList<SurfaceView>) {
        if (view is SurfaceView) into += view
        if (view is ViewGroup) for (i in 0 until view.childCount) collectSurfaces(view.getChildAt(i), into)
    }

    private companion object {
        /**
         * The copy is scaled down to SAMPLE x SAMPLE, so it is SAMPLE squared
         * points of the frame. 16 (256 points) could miss every lit pixel of a
         * dark, letterboxed title screen; 64 (4096) does not cost a frame.
         */
        const val SAMPLE = 64
        const val POLL_MS = 750L

        /** Faster while a loading screen is up, so it does not outlast the first frame by long. */
        const val COVER_POLL_MS = 200L

        /**
         * Host events (`enginehost-events`): the game's screen can be read, and it showed
         * something other than black. tools/core-harness matches these sentences.
         */
        const val SCREEN_WATCHED = "watching the game's screen"
        const val FIRST_FRAME = "the game drew its first frame"

        /** No surface this long after the window is up: the loading screen cannot tell when to go, and goes. */
        const val NO_SURFACE_COVER_MS = 5_000L
        const val NO_SURFACE_MS = 20_000L
        const val GIVE_UP_MS = 180_000L

        /** Black this long and the person is asked what to do (owner, 2026-10-22). */
        const val BLACK_SCREEN_MS = 45_000L
    }
}

/**
 * Installs the tap on every Activity the `:runtime` process shows for a
 * game. Registered by [EnginehostApplication] in that process only; the
 * host's own screens are not games and have nothing to intercept.
 */
object RuntimeInputInstaller : Application.ActivityLifecycleCallbacks {
    private val noticed = WeakHashMap<Activity, Boolean>()

    private fun isGame(activity: Activity) = activity.intent?.hasExtra(RuntimeActivity.EXTRA_PLUGIN_BUNDLE) == true

    private fun install(activity: Activity) {
        if (!isGame(activity)) return
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is HostWindowCallback) return
        window.callback = HostWindowCallback(current, RuntimeInputTap(activity))
    }

    // Created covers every Activity; started covers the ones that replace
    // their own callback during onCreate (AppCompat does), and is a no-op
    // once the wrapper is in place.
    override fun onActivityCreated(activity: Activity, state: Bundle?) = install(activity)
    // Started rather than created for the notice: an Activity's own
    // setContentView clears the content, and runs after Created.
    override fun onActivityStarted(activity: Activity) {
        install(activity)
        if (isGame(activity) && noticed.put(activity, true) == null) {
            // RuntimeActivity puts up its own cover; an engine's own Activity gets the one the
            // factory read for it, before the notice, so the bar knows it is covered.
            if (activity !is RuntimeActivity) LoadingCover.showOnEngineActivity(activity)
            RuntimeLoadingNotice(activity).start()
        }
    }
    override fun onActivityResumed(activity: Activity) {
        if (!isGame(activity)) return
        RuntimeWatchdog.inFront = true
        RuntimeWatchdog.start(activity, activity.intent?.getStringExtra(RuntimeActivity.EXTRA_GAME))
    }
    override fun onActivityPaused(activity: Activity) {
        if (isGame(activity)) RuntimeWatchdog.inFront = false
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
