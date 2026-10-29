package dev.enginehost

/**
 * Which voice reads a speaker's lines, and at what pitch (docs/text-services.md, "Reading aloud").
 * The owner, 2026-10-14: "We need to provide per-character voices." Each speaker keeps the same
 * voice every launch: the pick is a hash of the speaker over the voices there are, so it needs no
 * stored state and does not change when another character appears first. A device with fewer
 * voices than characters gives each character a pitch of its own on top, so two characters that
 * share a voice still sound apart. Narration is read by the first voice at its natural pitch.
 * Pure, so it is unit-tested on the JVM.
 */
object VoicePick {
    /** One choice: [voice] is an index into the voices given; [pitch] multiplies the voice's own. */
    data class Choice(val voice: Int, val pitch: Float)

    /** Pitches a shared voice is spread over, low to high; 1.0 is the voice as it is. */
    private val PITCHES = floatArrayOf(1.0f, 0.82f, 1.18f, 0.9f, 1.1f, 0.74f, 1.27f)

    /**
     * The voice and pitch for [speaker] (its id, else its name; null for narration) among
     * [voices] voices, or null when there are none.
     */
    fun choose(speaker: String?, voices: Int): Choice? {
        if (voices <= 0) return null
        if (speaker.isNullOrBlank()) return Choice(0, 1.0f)
        val hash = stableHash(speaker.trim().lowercase())
        val voice = Math.floorMod(hash, voices)
        // Characters on one voice differ by pitch; with a voice each, every pitch is natural.
        val pitch = if (voices >= 8) 1.0f else PITCHES[Math.floorMod(hash / voices, PITCHES.size)]
        return Choice(voice, pitch)
    }

    /** FNV-1a: the same on every device and every run, unlike String.hashCode's contract. */
    internal fun stableHash(text: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (c in text) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return hash ushr 1
    }
}
