package dev.enginehost

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Reads a game's lines aloud with a voice model on the device, through sherpa-onnx, in Enginehost's
 * main process (docs/text-services.md, "Voices on the device"; Droidtop/tracker#490). Each character
 * keeps a voice of their own ([CharacterVoicePick], [CharacterVoices]).
 *
 * It runs here, not in the game's process: a locked-down runtime cannot open the model's files, and a
 * model of a hundred megabytes should not share the game's memory. The runtime sends the lines over
 * [TextServicesService]; one worker thread loads the model once and speaks them in order, streaming
 * the audio as it is made. A new line, or the reader moving on, ends the one being read: the
 * generation counter turns the synthesis callback's answer to "stop".
 */
class ModelSpeech(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "enginehost-voices").apply { isDaemon = true } }
    private val characters = CharacterVoices(context)

    @Volatile private var generation = 0
    private var loaded: Pair<VoiceModels.Installed, OfflineTts>? = null
    @Volatile private var track: AudioTrack? = null

    /** Loads the chosen model ahead of the first line, so it is not read late. */
    fun prepare() {
        worker.execute { runCatching { model() } }
    }

    /** Reads [line] of [game]: after the one being read when it continues it ([TextEvent.Line.append]), else instead of it. */
    fun speak(game: String, line: TextEvent.Line) {
        val turn = if (line.append) generation else stop()
        worker.execute {
            if (turn != generation) return@execute
            runCatching { read(game, line, turn) }.onFailure { Log.w(TAG, "A line was not read: ${it.javaClass.simpleName}") }
        }
    }

    /** Ends the line being read and any waiting; returns the new turn. */
    fun stop(): Int {
        val turn = ++generation
        track?.let { runCatching { it.pause(); it.flush() } }
        return turn
    }

    fun release() {
        stop()
        worker.execute {
            loaded?.second?.release()
            loaded = null
            track?.release()
            track = null
        }
        worker.shutdown()
    }

    private fun read(game: String, line: TextEvent.Line, turn: Int) {
        val (model, tts) = model() ?: return
        val speaker = line.speaker?.name
        characters.heard(game, speaker)
        val language = line.lang ?: Locale.getDefault().toLanguageTag()
        val sid = CharacterVoicePick.pick(speaker, characters.choice(game, speaker, model.id), model.layout.speakers, language) ?: return
        val audio = track(tts.sampleRate())
        if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
        tts.generateWithCallback(line.text, sid, 1.0f) { samples ->
            if (turn != generation) {
                0
            } else {
                audio.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                1
            }
        }
    }

    /** The chosen model, loaded; null when none is chosen or it is not installed. Worker thread. */
    private fun model(): Pair<VoiceModels.Installed, OfflineTts>? {
        val id = TextSettings(context).voiceModel
        if (id.isEmpty()) return null
        loaded?.let { if (it.first.id == id) return it }
        loaded?.second?.release()
        loaded = null
        val model = VoiceModels.find(context, id) ?: return null
        val tts = OfflineTts(config = config(model))
        Log.i(TAG, "Voices loaded: ${model.id}, ${tts.numSpeakers()} voices at ${tts.sampleRate()} Hz")
        return (model to tts).also { loaded = it }
    }

    private fun track(sampleRate: Int): AudioTrack {
        track?.let { if (it.sampleRate == sampleRate) return it else it.release() }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimum * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track = it }
    }

    private companion object {
        const val TAG = "EnginehostVoices"

        fun config(model: VoiceModels.Installed): OfflineTtsConfig {
            val layout = model.layout
            fun path(relative: String?): String = relative?.split(',')?.joinToString(",") { File(model.folder, it).path }.orEmpty()
            val kokoro = if (layout.kind == VoiceModelLayout.Kind.KOKORO) {
                OfflineTtsKokoroModelConfig(
                    model = path(layout.model), voices = path(layout.voices), tokens = path(layout.tokens),
                    dataDir = path(layout.dataDir), lexicon = path(layout.lexicon), dictDir = path(layout.dictDir),
                )
            } else {
                OfflineTtsKokoroModelConfig()
            }
            val vits = if (layout.kind == VoiceModelLayout.Kind.VITS) {
                OfflineTtsVitsModelConfig(
                    model = path(layout.model), lexicon = path(layout.lexicon), tokens = path(layout.tokens),
                    dataDir = path(layout.dataDir), dictDir = path(layout.dictDir),
                )
            } else {
                OfflineTtsVitsModelConfig()
            }
            return OfflineTtsConfig(
                model = OfflineTtsModelConfig(vits = vits, kokoro = kokoro, numThreads = 2, debug = false, provider = "cpu"),
                ruleFsts = path(layout.ruleFsts),
                maxNumSentences = 1,
            )
        }
    }
}
