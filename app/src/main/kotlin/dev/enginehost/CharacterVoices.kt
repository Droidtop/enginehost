package dev.enginehost

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The characters heard in each game and the voice the person chose for each ([CharacterVoicePick];
 * docs/text-services.md, "Voices on the device"). Kept per game folder, in Enginehost's main process:
 * the reader adds the characters it hears, Settings shows them and keeps the choices.
 *
 * A choice of one voice names its model, so it applies only while that model reads; Female, Male and
 * Automatic apply to any model. Character names are game text: kept on this device, never logged.
 */
class CharacterVoices(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("character_voices", Context.MODE_PRIVATE)

    /** Narration's entry: the character with no name. */
    companion object {
        const val NARRATION = ""
        private const val MAX_CHARACTERS = 300
        private const val PREFIX = "game:"
    }

    private fun load(game: String): JSONObject =
        prefs.getString(PREFIX + game, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun save(game: String, state: JSONObject) = prefs.edit().putString(PREFIX + game, state.toString()).apply()

    /** Notes that [character] speaks in [game]; cheap when already known. */
    @Synchronized
    fun heard(game: String, character: String?) {
        val name = character?.trim().orEmpty()
        val state = load(game)
        val seen = state.optJSONArray("seen") ?: JSONArray()
        for (i in 0 until seen.length()) if (seen.optString(i) == name) return
        if (seen.length() >= MAX_CHARACTERS) return
        save(game, state.put("seen", seen.put(name)))
    }

    /** The games with characters heard, as their folders. */
    fun games(): List<String> = prefs.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.sorted()

    /** The characters heard in [game], narration first. */
    fun characters(game: String): List<String> {
        val seen = load(game).optJSONArray("seen") ?: return emptyList()
        val names = (0 until seen.length()).map { seen.optString(it) }.distinct()
        return names.filter { it == NARRATION } + names.filter { it != NARRATION }.sortedBy { it.lowercase() }
    }

    /** [character]'s choice in [game], for [model] (a choice of one voice of another model is Automatic here). */
    fun choice(game: String, character: String?, model: String): CharacterVoicePick.Choice {
        val wire = load(game).optJSONObject("choices")?.optString(character?.trim().orEmpty(), "")?.takeIf { it.isNotEmpty() }
            ?: return CharacterVoicePick.Choice.Automatic
        if (wire.startsWith("voice:")) {
            val (owner, id) = wire.removePrefix("voice:").split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else return CharacterVoicePick.Choice.Automatic }
            return if (owner == model) CharacterVoicePick.Choice.of("voice:$id") else CharacterVoicePick.Choice.Automatic
        }
        return CharacterVoicePick.Choice.of(wire)
    }

    @Synchronized
    fun choose(game: String, character: String?, model: String, choice: CharacterVoicePick.Choice) {
        val state = load(game)
        val choices = state.optJSONObject("choices") ?: JSONObject()
        val name = character?.trim().orEmpty()
        when (choice) {
            CharacterVoicePick.Choice.Automatic -> choices.remove(name)
            is CharacterVoicePick.Choice.Voice -> choices.put(name, "voice:$model:${choice.id}")
            else -> choices.put(name, choice.wire())
        }
        save(game, state.put("choices", choices))
    }

    /** Drops every choice of one voice of [model], which was removed. */
    @Synchronized
    fun forgetModel(model: String) {
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { key ->
            val game = key.removePrefix(PREFIX)
            val state = load(game)
            val choices = state.optJSONObject("choices") ?: return@forEach
            val gone = choices.keys().asSequence().filter { choices.optString(it).startsWith("voice:$model:") }.toList()
            if (gone.isNotEmpty()) {
                gone.forEach(choices::remove)
                save(game, state.put("choices", choices))
            }
        }
    }
}
