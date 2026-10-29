package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two models Enginehost offers, as their archives hold them (sherpa-onnx tts-models,
 * kokoro-int8-en-v0_19 and kokoro-int8-multi-lang-v1_0) with the speaker names their ONNX metadata
 * carries, and a VITS model a person might supply.
 */
class VoiceModelLayoutTest {
    private val english = listOf("README.md", "tokens.txt", "model.int8.onnx", "LICENSE", "voices.bin", "espeak-ng-data/en_dict", "espeak-ng-data/phontab")
    private val englishNames = "af,af_bella,af_nicole,af_sarah,af_sky,am_adam,am_michael,bf_emma,bf_isabella,bm_george,bm_lewis"

    @Test
    fun `the English model reads as eleven Kokoro voices with their genders`() {
        val layout = VoiceModelLayout.of(english, mapOf("model_type" to "kokoro", "sample_rate" to "24000", "n_speakers" to "11", "speaker_names" to englishNames))!!
        assertEquals(VoiceModelLayout.Kind.KOKORO, layout.kind)
        assertEquals("model.int8.onnx", layout.model)
        assertEquals("voices.bin", layout.voices)
        assertEquals("espeak-ng-data", layout.dataDir)
        assertNull(layout.lexicon)
        assertEquals(24000, layout.sampleRate)
        assertEquals(11, layout.speakers.size)
        assertEquals(7, layout.speakers.count { it.gender == VoiceModelLayout.Gender.FEMALE })
        assertEquals(4, layout.speakers.count { it.gender == VoiceModelLayout.Gender.MALE })
        // "af", Kokoro v0.19's default voice, is American English and female like the rest of "af_".
        assertEquals(VoiceModelLayout.Gender.FEMALE, layout.speakers[0].gender)
        assertEquals(VoiceModelLayout.Speaker(5, "am_adam", VoiceModelLayout.Gender.MALE, "en-US"), layout.speakers[5])
        assertEquals("en-GB", layout.speakers[7].language)
    }

    @Test
    fun `the multilingual model takes its lexicons, rules and dictionary`() {
        val files = listOf(
            "lexicon-us-en.txt", "phone-zh.fst", "voices.bin", "LICENSE", "lexicon-gb-en.txt", "date-zh.fst", "tokens.txt",
            "lexicon-zh.txt", "model.int8.onnx", "README.md", "number-zh.fst", "dict/idf.utf8", "dict/jieba.dict.utf8",
            "espeak-ng-data/en_dict",
        )
        val names = listOf("af_alloy", "am_adam", "bf_alice", "bm_daniel", "jf_alpha", "jm_kumo", "zf_xiaobei", "zm_yunxi")
        val layout = VoiceModelLayout.of(files, mapOf("model_type" to "kokoro", "n_speakers" to "8", "speaker_names" to names.joinToString(",")))!!
        assertEquals("lexicon-us-en.txt,lexicon-zh.txt", layout.lexicon)
        assertEquals("date-zh.fst,number-zh.fst,phone-zh.fst", layout.ruleFsts)
        assertEquals("dict", layout.dictDir)
        assertEquals("ja", layout.speakers[5].language)
        assertEquals(VoiceModelLayout.Gender.MALE, layout.speakers[5].gender)
        assertEquals("zh", layout.speakers[6].language)
    }

    @Test
    fun `a supplied VITS model without names gets numbered voices`() {
        val layout = VoiceModelLayout.of(listOf("en_US-libritts_r-medium.onnx", "tokens.txt", "espeak-ng-data/en_dict"), mapOf("model_type" to "vits", "n_speakers" to "3"))!!
        assertEquals(VoiceModelLayout.Kind.VITS, layout.kind)
        assertEquals(3, layout.speakers.size)
        assertNull(layout.speakers[2].gender)
    }

    @Test
    fun `what is not a model this build runs is refused`() {
        assertNull(VoiceModelLayout.of(listOf("model.onnx"), emptyMap()))
        assertNull(VoiceModelLayout.of(listOf("model.onnx", "tokens.txt"), mapOf("model_type" to "matcha")))
        assertNull(VoiceModelLayout.of(listOf("model.onnx", "tokens.txt"), mapOf("model_type" to "kokoro")))
        // Names that do not match the count are not trusted for genders.
        val odd = VoiceModelLayout.of(listOf("model.onnx", "tokens.txt"), mapOf("model_type" to "vits", "n_speakers" to "2", "speaker_names" to "af_a"))!!
        assertEquals(2, odd.speakers.size)
        assertNull(odd.speakers[0].gender)
    }
}
