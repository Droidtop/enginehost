package dev.enginehost

/**
 * What a voice model folder holds, read from its file names and its ONNX metadata ([OnnxMetadata]):
 * which kind of model it is, the files sherpa-onnx needs, and its voices (docs/text-services.md,
 * "Voices on the device"). The same for a model Enginehost downloaded and one the person supplied.
 * Pure, so it is unit-tested.
 */
data class VoiceModelLayout(
    val kind: Kind,
    /** Paths relative to the model's folder. */
    val model: String,
    val tokens: String,
    /** Kokoro's voice table. */
    val voices: String?,
    /** espeak-ng's data, a folder. */
    val dataDir: String?,
    /** Lexicons, comma-separated as sherpa-onnx takes them. */
    val lexicon: String?,
    /** jieba's dictionary, a folder, for Chinese. */
    val dictDir: String?,
    /** Text normalisation rules, comma-separated. */
    val ruleFsts: String?,
    val sampleRate: Int?,
    val speakers: List<Speaker>,
) {
    enum class Kind { KOKORO, VITS }

    enum class Gender { FEMALE, MALE }

    /** One voice: its speaker id in the model, its name, and its gender and language when the name says. */
    data class Speaker(val id: Int, val name: String, val gender: Gender?, val language: String?)

    companion object {
        /** Kokoro's voice names are a language letter and a gender letter, then the voice: "af_bella", "jm_kumo", or "af" alone. */
        private val KOKORO_NAME = Regex("^([a-z])([fm])(_|$)")

        /** Kokoro's language letters (hexgrad/Kokoro-82M VOICES.md), as BCP 47 tags. */
        private val KOKORO_LANGUAGES = mapOf(
            'a' to "en-US", 'b' to "en-GB", 'e' to "es", 'f' to "fr", 'h' to "hi",
            'i' to "it", 'j' to "ja", 'p' to "pt-BR", 'z' to "zh",
        )

        /**
         * The layout of a model folder holding [files] (its files' relative paths, "/" between
         * folders), whose ONNX model has [metadata]; null when it is no model this build runs.
         */
        fun of(files: Collection<String>, metadata: Map<String, String>): VoiceModelLayout? {
            val top = files.filter { '/' !in it }
            val onnx = top.filter { it.endsWith(".onnx", ignoreCase = true) }
            // One model per folder; a quantised one is the one shipped beside a full one.
            val model = onnx.singleOrNull()
                ?: onnx.firstOrNull { it.contains("int8", ignoreCase = true) }
                ?: onnx.firstOrNull { it.equals("model.onnx", ignoreCase = true) }
                ?: return null
            val tokens = top.firstOrNull { it.equals("tokens.txt", ignoreCase = true) } ?: return null
            val kind = when (metadata["model_type"]?.lowercase()) {
                "kokoro" -> Kind.KOKORO
                "vits", "piper" -> Kind.VITS
                null -> if (top.any { it.equals("voices.bin", ignoreCase = true) }) Kind.KOKORO else Kind.VITS
                else -> return null
            }
            val voices = top.firstOrNull { it.equals("voices.bin", ignoreCase = true) }
            if (kind == Kind.KOKORO && voices == null) return null
            fun folder(name: String) = name.takeIf { files.any { it.startsWith("$name/") } }
            val lexicons = top.filter { it.startsWith("lexicon", ignoreCase = true) && it.endsWith(".txt", ignoreCase = true) }
                // Where a model ships US and UK English lexicons, sherpa-onnx's examples take the US one.
                .let { all -> if (all.any { it.equals("lexicon-us-en.txt", true) }) all.filterNot { it.equals("lexicon-gb-en.txt", true) } else all }
                .sorted()
            val fsts = top.filter { it.endsWith(".fst", ignoreCase = true) }.sorted()
            val count = metadata["n_speakers"]?.toIntOrNull()?.coerceIn(1, 10_000)
            val names = metadata["speaker_names"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val speakers = if (names.isNotEmpty() && (count == null || names.size == count)) {
                names.mapIndexed { id, name ->
                    val match = KOKORO_NAME.find(name)
                    Speaker(
                        id, name,
                        match?.groupValues?.get(2)?.let { if (it == "f") Gender.FEMALE else Gender.MALE },
                        match?.groupValues?.get(1)?.single()?.let(KOKORO_LANGUAGES::get),
                    )
                }
            } else {
                (0 until (count ?: 1)).map { Speaker(it, "", null, null) }
            }
            return VoiceModelLayout(
                kind, model, tokens, voices, folder("espeak-ng-data"),
                lexicons.joinToString(",").ifEmpty { null }, folder("dict"),
                fsts.joinToString(",").ifEmpty { null },
                metadata["sample_rate"]?.toIntOrNull(), speakers,
            )
        }
    }
}
