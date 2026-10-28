package dev.enginehost

import org.json.JSONArray
import org.json.JSONObject

/** One event of a game's text, as a core reports it (docs/text-services.md, "ehtext/1"). */
sealed class TextEvent {
    data class Hello(val engine: String, val engineVersion: String?, val gameTitle: String?, val lang: String?, val markup: String?) : TextEvent()
    data class Scene(val id: String, val label: String?) : TextEvent()
    data class Speaker(val id: String?, val name: String)
    data class Line(
        val id: String,
        val kind: String,
        val speaker: Speaker?,
        val text: String,
        val lang: String?,
        val voiced: Boolean,
        val window: String?,
        val append: Boolean,
    ) : TextEvent()
    data class Choice(val id: String, val text: String, val enabled: Boolean)
    data class Choices(val id: String, val prompt: String?, val items: List<Choice>) : TextEvent()
    data class Chosen(val id: String, val item: String) : TextEvent()
    data class Advance(val id: String?) : TextEvent()
    data class Clear(val window: String?) : TextEvent()
    data class State(val mode: String) : TextEvent()
}

/**
 * Reads the events a core sends with `EngineHost.emitText` (docs/text-services.md). Every
 * event is untrusted game data: one that is too large, malformed, of another major version
 * or of an unknown type is dropped, never half-read; strings are cut to their caps and lose
 * their control characters (a line break stays). Unknown fields are ignored, so 1.x events
 * can add optional ones. Pure, so it is unit-tested on the JVM.
 */
object TextEvents {
    const val VERSION = 1

    /** The largest event read, in characters of its JSON. */
    const val MAX_EVENT = 16 * 1024

    /** The longest text kept of one field. */
    const val MAX_TEXT = 4000

    /** The longest id, name, language or mode kept. */
    const val MAX_NAME = 256

    /** The most choices one menu keeps. */
    const val MAX_CHOICES = 64

    val KINDS = setOf("dialogue", "narration", "thought", "choice_prompt", "ui", "system")
    val MODES = setOf("normal", "skip", "auto", "menu", "paused", "backlog")

    /** [json] as an event, or null when it is not one this host reads. */
    fun parse(json: String): TextEvent? {
        if (json.length > MAX_EVENT) return null
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (event.optInt("v", -1) != VERSION) return null
        return runCatching { read(event) }.getOrNull()
    }

    private fun read(e: JSONObject): TextEvent? {
        return when (e.optString("t")) {
            "hello" -> TextEvent.Hello(
                name(e, "engine") ?: return null,
                name(e, "engineVersion"),
                e.optJSONObject("game")?.let { text(it, "title") },
                e.optJSONObject("game")?.let { name(it, "lang") },
                name(e, "markup"),
            )
            "scene" -> TextEvent.Scene(name(e, "id") ?: return null, name(e, "label"))
            "line" -> TextEvent.Line(
                id = name(e, "id") ?: return null,
                kind = name(e, "kind")?.takeIf { it in KINDS } ?: "dialogue",
                speaker = e.optJSONObject("speaker")?.let { s -> name(s, "name")?.let { TextEvent.Speaker(name(s, "id"), it) } },
                text = text(e, "text") ?: return null,
                lang = name(e, "lang"),
                voiced = e.optBoolean("voiced", false),
                window = name(e, "window"),
                append = e.optBoolean("append", false),
            )
            "choices" -> TextEvent.Choices(
                name(e, "id") ?: return null,
                name(e, "prompt"),
                items(e.optJSONArray("items") ?: return null).ifEmpty { return null },
            )
            "chosen" -> TextEvent.Chosen(name(e, "id") ?: return null, name(e, "item") ?: return null)
            "advance" -> TextEvent.Advance(name(e, "id"))
            "clear" -> TextEvent.Clear(name(e, "window"))
            "state" -> TextEvent.State(name(e, "mode")?.takeIf { it in MODES } ?: return null)
            else -> null
        }
    }

    private fun items(array: JSONArray): List<TextEvent.Choice> =
        (0 until minOf(array.length(), MAX_CHOICES)).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            TextEvent.Choice(
                name(item, "id") ?: return@mapNotNull null,
                text(item, "text") ?: return@mapNotNull null,
                item.optBoolean("enabled", true),
            )
        }

    private fun text(o: JSONObject, key: String): String? = clean(o.opt(key) as? String, MAX_TEXT, keepBreaks = true)

    private fun name(o: JSONObject, key: String): String? = clean(o.opt(key) as? String, MAX_NAME, keepBreaks = false)

    private fun clean(value: String?, max: Int, keepBreaks: Boolean): String? {
        value ?: return null
        val kept = StringBuilder(minOf(value.length, max))
        for (c in value) {
            if (kept.length == max) break
            when {
                c == '\n' && keepBreaks -> kept.append(c)
                c == '\t' -> kept.append(' ')
                Character.isISOControl(c) || c == ' ' || c == ' ' -> Unit
                else -> kept.append(c)
            }
        }
        return kept.toString().trim().ifEmpty { null }
    }
}

/**
 * How many events a core may send: [perSecond] on average, in bursts of up to [burst]. A
 * core that sends more (a runaway loop, a game drawing text every frame) loses the excess,
 * so the host never spends more than this on a game's text.
 */
class TextRate(private val perSecond: Int = 120, private val burst: Int = 240) {
    private var tokens = burst.toDouble()
    private var last = -1L

    /** Whether one more event may pass at [nowMillis]. */
    @Synchronized
    fun take(nowMillis: Long): Boolean {
        if (last >= 0) tokens = minOf(burst.toDouble(), tokens + (nowMillis - last).coerceAtLeast(0) * perSecond / 1000.0)
        last = nowMillis
        if (tokens < 1) return false
        tokens -= 1
        return true
    }
}
