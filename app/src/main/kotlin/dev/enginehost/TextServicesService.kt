package dev.enginehost

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.enginehost.runtime.ITextServices
import dev.enginehost.runtime.ITextTranslated
import java.util.concurrent.Executors

/**
 * The text services a running game gets from Enginehost's main process, which has what the
 * runtime may not: the network and the person's keys (docs/text-services.md). Bound by the
 * runtime ([GameTranslation]); not exported, so no other app reaches it.
 *
 * A translation is answered from [TranslationCache] when the line was seen before, else asked of
 * the person's chosen [Translator] on one worker thread, in the order lines came, and kept.
 */
class TextServicesService : Service() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "enginehost-translate").apply { isDaemon = true } }
    private val cache by lazy { TranslationCache(this) }

    private val binder = object : ITextServices.Stub() {
        override fun translate(game: String, line: String, answer: ITextTranslated) {
            val event = TextEvents.parse(line) as? TextEvent.Line ?: return
            worker.execute { translate(game, event, answer) }
        }
    }

    private fun translate(game: String, line: TextEvent.Line, answer: ITextTranslated) {
        val settings = TextSettings(this)
        val target = settings.translateInto
        val service = settings.translatorEndpoint.trim()
        if (!settings.translate || target.isBlank() || service.isEmpty()) return
        val text = cache.get(game, line.id, line.text, target, service) ?: run {
            val asked = Translator(service, settings.translatorModel, settings.translatorKey.get())
                .translate(TranslationRequest.Line(line.speaker?.name, line.text), target)
                ?.let { TranslationRequest.withoutSpeaker(it, line.speaker?.name) }
                ?: return
            cache.put(game, line.id, line.text, target, service, asked)
            asked
        }
        runCatching { answer.translated(line.id, text) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
