package dev.enginehost

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Reads the game's lines aloud with the device's own text-to-speech (docs/text-services.md,
 * "Reading aloud"; Droidtop/tracker#490). It is one backend of several the owner asked for, and
 * the one that needs no download; it is on only when the person turned on "Read text aloud".
 *
 * Fed by [GameText], in the runtime process:
 * - a line is read in its speaker's voice ([VoicePick]); narration in the first;
 * - a line the game voices itself, and UI and system text, are not read;
 * - the reader moving on (advance, a cleared box, a chosen answer) stops the line being read;
 * - skip mode stops reading until the game is back to normal.
 *
 * TextToSpeech binds to the device's engine and is ready a moment later; a line before that is
 * dropped rather than read late over the next one. The runtime process ends with the game, and
 * the binding with it.
 */
class GameSpeech(context: Context) : LineReader {
    @Volatile private var ready = false
    @Volatile private var muted = false
    private var voices: List<Voice> = emptyList()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) prepare()
    }

    private fun prepare() {
        val locale = runCatching { tts.voice?.locale ?: tts.defaultVoice?.locale }.getOrNull() ?: Locale.getDefault()
        voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
            .sortedBy { it.name }
        ready = true
    }

    override fun take(event: TextEvent, json: String) {
        when (event) {
            is TextEvent.Line -> speak(event)
            is TextEvent.Advance, is TextEvent.Clear, is TextEvent.Chosen -> stop()
            is TextEvent.State -> {
                muted = event.mode == "skip"
                if (muted) stop()
            }
            else -> Unit
        }
    }

    private fun speak(line: TextEvent.Line) {
        if (!ready || muted || line.voiced || line.kind == "ui" || line.kind == "system") return
        val speaker = line.speaker?.let { it.id ?: it.name }
        val choice = VoicePick.choose(speaker, voices.size)
        runCatching {
            if (choice != null) tts.voice = voices[choice.voice]
            tts.setPitch(choice?.pitch ?: 1.0f)
            tts.speak(line.text, if (line.append) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, line.id)
        }
    }

    private fun stop() {
        if (ready) runCatching { tts.stop() }
    }
}
