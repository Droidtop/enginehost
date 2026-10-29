package dev.enginehost

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * The sheets for the voices that read games aloud (docs/text-services.md, "Voices on the device"):
 * which voices read, downloading or supplying a voice model, removing one, and each character's voice.
 * Every list is read off the main thread, where the models' files are.
 */
class VoiceSheets(private val activity: Activity, private val changed: () -> Unit) {
    private val text = TextSettings(activity)

    /** What the Voices row says. Background thread. */
    fun describe(): String {
        val id = text.voiceModel
        if (id.isEmpty()) return activity.getString(R.string.voices_device)
        return VoiceModels.find(activity, id)?.let { describe(it) } ?: activity.getString(R.string.voices_device)
    }

    private fun describe(model: VoiceModels.Installed): String {
        val speakers = model.layout.speakers
        val female = speakers.count { it.gender == VoiceModelLayout.Gender.FEMALE }
        val male = speakers.count { it.gender == VoiceModelLayout.Gender.MALE }
        return if (female + male > 0) {
            activity.getString(R.string.voices_model_genders, model.label, speakers.size, female, male)
        } else {
            activity.getString(R.string.voices_model_count, model.label, speakers.size)
        }
    }

    /** Which voices read: the device's own, or an installed model; and getting or removing models. */
    fun choose() = Async.load(activity, { VoiceModels.installed(activity) }) { installed ->
        Sheet(activity).title(R.string.voices_row).message(R.string.voices_message).apply {
            choice(activity.getString(R.string.voices_device), detail = activity.getString(R.string.voices_device_detail), current = text.voiceModel.isEmpty()) {
                text.voiceModel = ""
                changed()
            }
            installed.forEach { model ->
                choice(model.label, detail = describe(model), current = model.id == text.voiceModel) {
                    text.voiceModel = model.id
                    changed()
                }
            }
            val offered = VoiceModels.OFFERED.filter { o -> installed.none { it.id == o.id } }
            if (offered.isNotEmpty()) choice(R.string.voices_download) { download(offered) }
            choice(R.string.voices_supply) { supply() }
            if (installed.isNotEmpty()) choice(R.string.voices_remove) { remove(installed) }
        }.show()
    }

    private fun download(offered: List<VoiceModels.Offered>) {
        Sheet(activity).title(R.string.voices_download).message(R.string.voices_download_message).apply {
            offered.forEach { model ->
                choice(activity.getString(model.label), detail = Formatter.formatShortFileSize(activity, model.bytes)) { fetch(model) }
            }
        }.show()
    }

    private fun fetch(model: VoiceModels.Offered) {
        val status = activity.layoutInflater.inflate(R.layout.item_hint, null) as TextView
        status.text = activity.getString(R.string.voices_downloading, Formatter.formatShortFileSize(activity, 0), Formatter.formatShortFileSize(activity, model.bytes))
        val sheet = Sheet(activity).title(model.label).content(status).show()
        var shown = 0L
        Async.load(activity, {
            runCatching {
                VoiceModels.download(activity.applicationContext, model) { bytes ->
                    // A line a megabyte, not one a buffer.
                    if (bytes - shown >= 1_000_000) {
                        shown = bytes
                        Async.onMain(activity) {
                            status.text = activity.getString(
                                R.string.voices_downloading,
                                Formatter.formatShortFileSize(activity, bytes), Formatter.formatShortFileSize(activity, model.bytes),
                            )
                        }
                    }
                }
            }
        }) { result -> finish(sheet, result) }
    }

    /** Asks the system picker for a voice archive; the answer comes to [supplied]. */
    private fun supply() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { activity.startActivityForResult(pick, REQUEST_VOICE_FILE) }
            .onFailure { Toast.makeText(activity, R.string.voices_no_picker, Toast.LENGTH_LONG).show() }
    }

    /** The archive the person picked: installed like a download. */
    fun supplied(uri: Uri) {
        val status = activity.layoutInflater.inflate(R.layout.item_hint, null) as TextView
        status.setText(R.string.voices_unpacking)
        val sheet = Sheet(activity).title(R.string.voices_supply).content(status).show()
        Async.load(activity, {
            runCatching {
                val name = runCatching {
                    activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.voices_own)
                VoiceModels.supply(activity.applicationContext, uri, name)
            }
        }) { result -> finish(sheet, result) }
    }

    private fun finish(sheet: android.app.Dialog, result: Result<VoiceModels.Installed>) {
        runCatching { sheet.dismiss() }
        result.onSuccess { model ->
            text.voiceModel = model.id
            changed()
            Toast.makeText(activity, activity.getString(R.string.voices_ready, describe(model)), Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Sheet(activity).warning().title(R.string.voices_failed).message(error.message ?: error.javaClass.simpleName).choice(R.string.ok) {}.show()
        }
    }

    private fun remove(installed: List<VoiceModels.Installed>) {
        Sheet(activity).title(R.string.voices_remove).apply {
            installed.forEach { model ->
                choice(model.label, detail = describe(model)) {
                    Async.load(activity, { VoiceModels.remove(activity.applicationContext, model.id) }) {
                        if (text.voiceModel == model.id) text.voiceModel = ""
                        changed()
                    }
                }
            }
        }.show()
    }

    /** The games with characters heard, then a game's characters, then one character's voice. */
    fun characters() = Async.load(activity, { VoiceModels.find(activity, text.voiceModel) to CharacterVoices(activity).games() }) { (model, games) ->
        if (model == null) {
            Toast.makeText(activity, R.string.character_voices_need_model, Toast.LENGTH_LONG).show()
            return@load
        }
        Sheet(activity).title(R.string.character_voices_row).apply {
            if (games.isEmpty()) message(R.string.character_voices_none)
            games.forEach { game -> choice(File(game).name.ifEmpty { game }) { gameCharacters(model, game) } }
        }.show()
    }

    private fun gameCharacters(model: VoiceModels.Installed, game: String) {
        val store = CharacterVoices(activity)
        Async.load(activity, { store.characters(game) }) { characters ->
            Sheet(activity).title(File(game).name).apply {
                characters.forEach { character ->
                    val label = character.ifEmpty { activity.getString(R.string.character_narration) }
                    choice(label, detail = choiceName(model, store.choice(game, character, model.id))) {
                        characterVoice(model, game, character, label)
                    }
                }
            }.show()
        }
    }

    private fun characterVoice(model: VoiceModels.Installed, game: String, character: String, label: String) {
        val store = CharacterVoices(activity)
        val current = store.choice(game, character, model.id)
        fun set(choice: CharacterVoicePick.Choice) = Async.load(activity, { store.choose(game, character, model.id, choice) }) {}
        Sheet(activity).title(label).apply {
            choice(activity.getString(R.string.character_voice_auto), current = current == CharacterVoicePick.Choice.Automatic) { set(CharacterVoicePick.Choice.Automatic) }
            if (model.layout.speakers.any { it.gender == VoiceModelLayout.Gender.FEMALE }) {
                choice(activity.getString(R.string.character_voice_female), current = current == CharacterVoicePick.Choice.Gender(VoiceModelLayout.Gender.FEMALE)) {
                    set(CharacterVoicePick.Choice.Gender(VoiceModelLayout.Gender.FEMALE))
                }
            }
            if (model.layout.speakers.any { it.gender == VoiceModelLayout.Gender.MALE }) {
                choice(activity.getString(R.string.character_voice_male), current = current == CharacterVoicePick.Choice.Gender(VoiceModelLayout.Gender.MALE)) {
                    set(CharacterVoicePick.Choice.Gender(VoiceModelLayout.Gender.MALE))
                }
            }
            model.layout.speakers.forEach { speaker ->
                choice(voiceName(speaker), current = current == CharacterVoicePick.Choice.Voice(speaker.id)) {
                    set(CharacterVoicePick.Choice.Voice(speaker.id))
                }
            }
        }.show()
    }

    private fun choiceName(model: VoiceModels.Installed, choice: CharacterVoicePick.Choice): String = when (choice) {
        CharacterVoicePick.Choice.Automatic -> activity.getString(R.string.character_voice_auto)
        is CharacterVoicePick.Choice.Gender -> activity.getString(
            if (choice.gender == VoiceModelLayout.Gender.FEMALE) R.string.character_voice_female else R.string.character_voice_male,
        )
        is CharacterVoicePick.Choice.Voice -> model.layout.speakers.firstOrNull { it.id == choice.id }?.let(::voiceName)
            ?: activity.getString(R.string.character_voice_auto)
    }

    /** "Bella · female · en-US", from the voice's name; "Voice 3" when the model names none. */
    private fun voiceName(speaker: VoiceModelLayout.Speaker): String {
        val name = speaker.name.substringAfter('_').replaceFirstChar { it.titlecase() }
            .ifEmpty { activity.getString(R.string.voice_numbered, speaker.id + 1) }
        val gender = when (speaker.gender) {
            VoiceModelLayout.Gender.FEMALE -> activity.getString(R.string.voice_female)
            VoiceModelLayout.Gender.MALE -> activity.getString(R.string.voice_male)
            null -> null
        }
        return listOfNotNull(name, gender, speaker.language).joinToString(" · ")
    }

    companion object {
        const val REQUEST_VOICE_FILE = 40
    }
}
