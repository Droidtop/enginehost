package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterVoicePickTest {
    private fun speaker(id: Int, name: String) = VoiceModelLayout.of(
        listOf("m.onnx", "tokens.txt", "voices.bin"), mapOf("model_type" to "kokoro", "speaker_names" to name),
    )!!.speakers[0].copy(id = id)

    private val voices = listOf(
        speaker(0, "af_bella"), speaker(1, "af_sarah"), speaker(2, "am_adam"), speaker(3, "am_michael"),
        speaker(4, "bf_emma"), speaker(5, "jf_alpha"), speaker(6, "jm_kumo"),
    )
    private val auto = CharacterVoicePick.Choice.Automatic

    @Test
    fun `a character keeps one voice, in the language being read`() {
        val first = CharacterVoicePick.pick("Eileen", auto, voices, "en-US")
        assertEquals(first, CharacterVoicePick.pick(" eileen ", auto, voices, "en-US"))
        assertTrue(first in listOf(0, 1, 2, 3, 4))
        assertTrue(CharacterVoicePick.pick("Eileen", auto, voices, "ja") in listOf(5, 6))
        // Different characters are spread over the voices, not all on one.
        val picks = listOf("Eileen", "Lucy", "Sylvie", "Michael", "Ken", "Yuki", "Mark", "Anna").map { CharacterVoicePick.pick(it, auto, voices, "en") }
        assertTrue(picks.toSet().size >= 3)
    }

    @Test
    fun `female and male choose among that gender's voices`() {
        val female = CharacterVoicePick.Choice.Gender(VoiceModelLayout.Gender.FEMALE)
        val male = CharacterVoicePick.Choice.Gender(VoiceModelLayout.Gender.MALE)
        listOf("Eileen", "Lucy", "Ken", "Mark").forEach {
            assertTrue(CharacterVoicePick.pick(it, female, voices, "en") in listOf(0, 1, 4))
            assertTrue(CharacterVoicePick.pick(it, male, voices, "en") in listOf(2, 3))
            assertEquals(6, CharacterVoicePick.pick(it, male, voices, "ja"))
        }
    }

    @Test
    fun `one voice chosen is that voice, and narration has its own`() {
        assertEquals(6, CharacterVoicePick.pick("Eileen", CharacterVoicePick.Choice.Voice(6), voices, "en"))
        // A voice the model does not have falls back to the automatic pick.
        assertEquals(CharacterVoicePick.pick("Eileen", auto, voices, "en"), CharacterVoicePick.pick("Eileen", CharacterVoicePick.Choice.Voice(99), voices, "en"))
        assertEquals(0, CharacterVoicePick.pick(null, auto, voices, "en"))
        assertEquals(5, CharacterVoicePick.pick("", auto, voices, "ja"))
        assertNull(CharacterVoicePick.pick("Eileen", auto, emptyList(), "en"))
    }

    @Test
    fun `choices are stored as words`() {
        listOf(auto, CharacterVoicePick.Choice.Gender(VoiceModelLayout.Gender.MALE), CharacterVoicePick.Choice.Voice(3)).forEach {
            assertEquals(it, CharacterVoicePick.Choice.of(it.wire()))
        }
        assertEquals(auto, CharacterVoicePick.Choice.of("nonsense"))
    }
}
