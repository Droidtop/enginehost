package dev.enginehost

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import dev.enginehost.runtime.ITextServices
import dev.enginehost.runtime.ITextTranslated
import java.util.concurrent.Executors

/**
 * The text services a running game gets from Enginehost's main process, which has what the
 * runtime may not: the network and the person's keys (docs/text-services.md). Bound by the
 * runtime ([GameTranslation]); not exported, so no other app reaches it.
 *
 * A translation is answered from [TranslationCache] when the line was seen before, else asked of
 * the person's chosen [Translator] on one worker thread, in the order lines came, and kept. Lines
 * to read aloud with a voice model on the device go to [ModelSpeech], which has its own thread.
 */
class TextServicesService : Service() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "enginehost-translate").apply { isDaemon = true } }
    private val cache by lazy { TranslationCache(this) }
    private val speechStarted = lazy { ModelSpeech(this) }
    private val speech by speechStarted

    private val binder = object : ITextServices.Stub() {
        override fun translate(game: String, line: String, answer: ITextTranslated) {
            val event = TextEvents.parse(line) as? TextEvent.Line ?: return
            worker.execute { translate(game, event, answer) }
        }

        override fun prepareVoices() = speech.prepare()

        override fun speak(game: String, line: String) {
            val event = TextEvents.parse(line) as? TextEvent.Line ?: return
            speech.speak(game, event)
        }

        override fun stopSpeaking() {
            speech.stop()
        }
    }

    private fun translate(game: String, line: TextEvent.Line, answer: ITextTranslated) {
        val settings = TextSettings(this)
        val target = settings.translateInto
        val service = settings.translatorEndpoint.trim()
        if (!settings.translate || target.isBlank() || service.isEmpty()) return
        val text = cache.get(game, line.id, line.text, target, service) ?: run {
            val started = SystemClock.elapsedRealtime()
            val asked = Translator(service, settings.translatorModel, settings.translatorKey.get())
                .translate(TranslationRequest.Line(line.speaker?.name, line.text), target)
                ?.let { TranslationRequest.withoutSpeaker(it, line.speaker?.name) }
                ?: return
            // Kept on the device as a typical answer time; a rating report carries only its bucket.
            SharedStats.recordTranslationAnswer(this, SystemClock.elapsedRealtime() - started)
            cache.put(game, line.id, line.text, target, service, asked)
            asked
        }
        runCatching { answer.translated(line.id, text) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        worker.shutdownNow()
        if (speechStarted.isInitialized()) speech.release()
        super.onDestroy()
    }
}
