package dev.enginehost

/**
 * Which of a voice model's voices reads a character (docs/text-services.md, "Voices on the device").
 * The owner, 2026-10-29: "Remember we also want per-character voices (at least male and female)".
 *
 * Every character keeps one voice: by default a hash of their name over the model's voices for the
 * language being read, so it needs no stored state and does not change when another character
 * appears first. The person can set a character to Female or Male, which picks stably among that
 * gender's voices, or to one voice in particular. Narration is the character with no name. Pure, so
 * it is unit-tested.
 */
object CharacterVoicePick {
    /** What the person chose for one character. */
    sealed class Choice {
        object Automatic : Choice()
        data class Gender(val gender: VoiceModelLayout.Gender) : Choice()
        data class Voice(val id: Int) : Choice()

        /** As stored: "auto", "female", "male" or "voice:<id>". */
        fun wire(): String = when (this) {
            Automatic -> "auto"
            is Gender -> if (gender == VoiceModelLayout.Gender.FEMALE) "female" else "male"
            is Voice -> "voice:$id"
        }

        companion object {
            fun of(wire: String?): Choice = when {
                wire == "female" -> Gender(VoiceModelLayout.Gender.FEMALE)
                wire == "male" -> Gender(VoiceModelLayout.Gender.MALE)
                wire != null && wire.startsWith("voice:") -> wire.removePrefix("voice:").toIntOrNull()?.let(::Voice) ?: Automatic
                else -> Automatic
            }
        }
    }

    /**
     * The speaker id that reads [character] (null or blank for narration) as [choice], among
     * [speakers], preferring voices for [language] (a BCP 47 tag; null for any); null when the model
     * has no voices.
     */
    fun pick(character: String?, choice: Choice, speakers: List<VoiceModelLayout.Speaker>, language: String?): Int? {
        if (speakers.isEmpty()) return null
        if (choice is Choice.Voice && speakers.any { it.id == choice.id }) return choice.id
        val primary = language?.substringBefore('-')?.lowercase()?.takeIf { it.isNotEmpty() }
        val spoken = speakers.filter { s -> primary != null && s.language?.substringBefore('-')?.lowercase() == primary }
            .ifEmpty { speakers.filter { it.language == null } }
            .ifEmpty { speakers }
        val pool = if (choice is Choice.Gender) {
            spoken.filter { it.gender == choice.gender }.ifEmpty { speakers.filter { it.gender == choice.gender } }.ifEmpty { spoken }
        } else {
            spoken
        }
        val name = character?.trim()?.lowercase().orEmpty()
        // Narration on Automatic is the language's first voice: steady, and the same in every game.
        if (name.isEmpty() && choice is Choice.Automatic) return pool.first().id
        return pool[Math.floorMod(VoicePick.stableHash(name), pool.size)].id
    }
}
