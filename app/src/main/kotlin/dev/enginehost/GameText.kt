package dev.enginehost

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The running game's text, as its core reports it with `EngineHost.emitText`
 * (docs/text-services.md). One game runs in a runtime process, so there is one of these per
 * process, fed by the in-process host and by the isolated runtime's callback alike.
 *
 * Every event passes [TextRate] and [TextEvents] first, off the main thread where it arrives.
 * [GameSpeech] reads lines aloud and [GameTranslation] translates them, each when the person
 * turned it on (Droidtop/tracker#490). For the rig, with `debug.enginehost.text` set to 1, the last
 * few events are also drawn over the top of the game. Game text is never logged: it can be anything
 * the game shows, and host events never carry a game's content.
 */
object GameText {
    private val rate = TextRate()

    private val showEvents by lazy { DebugProperty.get("text") == "1" }

    /** Takes one event from the core; any thread, returns at once. */
    fun emit(activity: Activity, json: String) {
        if (!rate.take(SystemClock.elapsedRealtime())) return
        val event = TextEvents.parse(json) ?: return
        services(activity)
        speech?.take(event, json)
        translation?.take(event, json)
        if (showEvents) activity.runOnUiThread { EventsOverlay.show(activity, event) }
    }

    /**
     * The line the engine is about to print, in the person's language, or null to print the original
     * ([GameTranslation.replace]). Any thread; never waits.
     */
    fun replace(json: String): String? = translation?.replace(json)

    private var servicesChecked = false
    @Volatile private var speech: LineReader? = null
    @Volatile private var translation: GameTranslation? = null

    /** This launch's reader and translator, as the person chose them (the launch says which). */
    @Synchronized
    private fun services(activity: Activity) {
        if (servicesChecked) return
        servicesChecked = true
        val intent = activity.intent ?: return
        val game = intent.getStringExtra(RuntimeActivity.EXTRA_GAME)
        val services by lazy { TextServicesClient(activity) }
        if (intent.getBooleanExtra(RuntimeActivity.EXTRA_READ_ALOUD, false)) {
            // A voice model is read in the main process; the device's own voices, here.
            speech = if (intent.getBooleanExtra(RuntimeActivity.EXTRA_MODEL_VOICES, false) && game != null) {
                RemoteSpeech(services, game)
            } else {
                GameSpeech(activity)
            }
        }
        if (intent.getBooleanExtra(RuntimeActivity.EXTRA_TRANSLATE, false) && game != null) {
            translation = GameTranslation(activity, game, services)
        }
    }

    /** The rig check's view of the stream: the newest events, one per line, over the game; never takes a touch. */
    private object EventsOverlay {
        private const val KEPT = 6
        private val lines = ArrayDeque<String>()
        private var view: TextView? = null

        fun show(activity: Activity, event: TextEvent) {
            if (activity.isFinishing || activity.isDestroyed) return
            lines.addLast(describe(event))
            while (lines.size > KEPT) lines.removeFirst()
            val shown = view?.takeIf { it.context === activity } ?: TextView(activity).apply {
                setBackgroundColor(Color.argb(170, 0, 0, 0))
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                activity.addContentView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                view = this
            }
            shown.text = lines.joinToString("\n")
        }

        private fun describe(event: TextEvent): String = when (event) {
            is TextEvent.Hello -> "hello ${event.engine} ${event.engineVersion.orEmpty()} ${event.gameTitle.orEmpty()}".trim()
            is TextEvent.Scene -> "scene ${event.id}"
            is TextEvent.Line -> "${event.kind} ${event.speaker?.name?.let { "$it: " }.orEmpty()}${event.text}" +
                (if (event.voiced) " [voiced]" else "") + (if (event.append) " [+]" else "")
            is TextEvent.Choices -> "choices " + event.items.joinToString(" | ") { it.text }
            is TextEvent.Chosen -> "chosen ${event.item}"
            is TextEvent.Advance -> "advance"
            is TextEvent.Clear -> "clear ${event.window.orEmpty()}".trim()
            is TextEvent.State -> "state ${event.mode}"
        }
    }
}
