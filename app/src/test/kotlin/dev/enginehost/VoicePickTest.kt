package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePickTest {
    @Test
    fun `a speaker keeps the same voice, whatever its case or spacing`() {
        val first = VoicePick.choose("Eileen", 4)
        assertEquals(first, VoicePick.choose("Eileen", 4))
        assertEquals(first, VoicePick.choose(" eileen ", 4))
    }

    @Test
    fun `narration has the first voice at its natural pitch`() {
        assertEquals(VoicePick.Choice(0, 1.0f), VoicePick.choose(null, 5))
        assertEquals(VoicePick.Choice(0, 1.0f), VoicePick.choose("  ", 5))
    }

    @Test
    fun `no voices means no choice`() {
        assertNull(VoicePick.choose("Eileen", 0))
    }

    @Test
    fun `characters on a single voice are told apart by pitch`() {
        val names = listOf("Eileen", "Lucy", "Ren", "Mio", "Akane", "Haru")
        val pitches = names.map { VoicePick.choose(it, 1)!!.pitch }.toSet()
        assertTrue("pitches: $pitches", pitches.size >= 3)
        names.forEach { assertEquals(0, VoicePick.choose(it, 1)!!.voice) }
    }

    @Test
    fun `with plenty of voices every pitch is natural and voices spread`() {
        val names = (1..40).map { "Character $it" }
        val picks = names.map { VoicePick.choose(it, 10)!! }
        assertTrue(picks.all { it.pitch == 1.0f })
        assertTrue(picks.map { it.voice }.toSet().size >= 6)
        assertTrue(picks.all { it.voice in 0 until 10 })
    }

    @Test
    fun `a cast of ten on six voices mostly sounds different`() {
        val cast = listOf("Eileen", "Lucy", "Ren", "Mio", "Akane", "Haru", "Sora", "Yuki", "Kaito", "Nanami")
        val sounds = cast.map { VoicePick.choose(it, 6) }.toSet()
        assertTrue("distinct: ${sounds.size}", sounds.size >= 8)
    }
}
