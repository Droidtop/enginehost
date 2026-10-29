package dev.enginehost

/** What reads the game's lines aloud: the device's own voices ([GameSpeech]) or a voice model ([RemoteSpeech]). */
interface LineReader {
    /** Takes one event of the game's text, with its JSON as the core sent it. Any thread. */
    fun take(event: TextEvent, json: String)
}

/**
 * Reads the game's lines aloud with the voice model the person chose, by handing them to Enginehost's
 * main process, where [ModelSpeech] runs it (docs/text-services.md, "Voices on the device"). The same
 * rules as the device's own voices ([GameSpeech]): a line the game voices itself, and UI and system
 * text, are not read; moving on stops the line; skip mode stops reading until the game is back to
 * normal.
 */
class RemoteSpeech(private val services: TextServicesClient, private val game: String) : LineReader {
    @Volatile private var muted = false

    init {
        services.call { it.prepareVoices() }
    }

    override fun take(event: TextEvent, json: String) {
        when (event) {
            is TextEvent.Line -> if (!muted && !event.voiced && event.kind != "ui" && event.kind != "system") {
                services.call { it.speak(game, json) }
            }
            is TextEvent.Advance, is TextEvent.Clear, is TextEvent.Chosen -> services.call { it.stopSpeaking() }
            is TextEvent.State -> {
                muted = event.mode == "skip"
                if (muted) services.call { it.stopSpeaking() }
            }
            else -> Unit
        }
    }
}
