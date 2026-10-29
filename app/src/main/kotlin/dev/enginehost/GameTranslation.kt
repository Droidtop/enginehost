package dev.enginehost

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.enginehost.runtime.ITextTranslated
import org.json.JSONObject
import java.util.Collections

/**
 * The game's lines in the person's language, in the runtime process (docs/text-services.md,
 * "Translating"; Droidtop/tracker#490). The runtime has no network of its own, so each line goes
 * to Enginehost's main process ([TextServicesService]) and the translation comes back here.
 *
 * The owner, 2026-10-14: "Translation isn't shown OVER the game. By default, it'll hand the text
 * back to the engine to print." An engine that prints it asks [replace] before it draws a line,
 * and gets the translation once it is here. For an engine that never asks, the translation is
 * drawn in a band at the bottom of the screen instead, the fallback, and goes when the reader
 * moves on.
 */
class GameTranslation(private val activity: Activity, private val game: String, private val services: TextServicesClient) {
    private val translated = Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > KEPT
    })

    /** Lines the engine asked to print itself: no band for those. */
    private val printed = Collections.synchronizedSet(HashSet<String>())

    @Volatile private var current: TextEvent.Line? = null
    private var band: TextView? = null

    private val answer = object : ITextTranslated.Stub() {
        override fun translated(lineId: String, text: String) {
            translated[lineId] = text
            if (lineId !in printed) activity.runOnUiThread { if (current?.id == lineId) showBand(text) }
        }
    }

    /** Takes one event of the game's text, with its JSON as the core sent it. Any thread. */
    fun take(event: TextEvent, json: String) {
        when (event) {
            is TextEvent.Line -> {
                current = event
                activity.runOnUiThread { hideBand() }
                if (event.kind == "ui" || event.kind == "system") return
                translated[event.id]?.let { known ->
                    if (event.id !in printed) activity.runOnUiThread { if (current?.id == event.id) showBand(known) }
                    return
                }
                services.call { it.translate(game, json, answer) }
            }
            is TextEvent.Advance, is TextEvent.Clear, is TextEvent.Chosen -> {
                current = null
                activity.runOnUiThread { hideBand() }
            }
            else -> Unit
        }
    }

    /**
     * [event] (a line the engine is about to print) with its text in the person's language, or null
     * to print the original: never waits, so a line asked before its translation is here prints as it
     * is. A line asked about is the engine's to print, so it gets no band.
     */
    fun replace(event: String): String? {
        val line = TextEvents.parse(event) as? TextEvent.Line ?: return null
        printed += line.id
        val text = translated[line.id] ?: return null
        return runCatching { JSONObject(event).put("text", text).toString() }.getOrNull()
    }

    private fun showBand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val shown = band?.takeIf { it.context === activity } ?: TextView(activity).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = false
            isFocusable = false
            activity.addContentView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            band = this
        }
        val speaker = current?.speaker?.name
        shown.text = if (speaker.isNullOrBlank()) text else "$speaker: $text"
        shown.visibility = View.VISIBLE
    }

    private fun hideBand() {
        band?.visibility = View.GONE
    }

    private companion object {
        /** Translations kept in memory for lines the engine may ask about again; the cache keeps the rest. */
        const val KEPT = 500
    }
}
