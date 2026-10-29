package dev.enginehost

import org.json.JSONObject
import java.net.InetAddress
import java.net.URI

/**
 * One rating of a voice or translation model, as it would be shared (docs/stats-schema.md;
 * Droidtop/tracker#491). Pure, so it is unit-tested on the JVM.
 *
 * Built only from what the person chose and coarse classes of the device: never a game's name or
 * text, an account, a key, a device or install id, or the address of the person's own service.
 */
data class StatsReport(
    val kind: Kind,
    /** The model the person named ("gpt-4o-mini"), or the voice engine's package. */
    val model: String,
    val modelVersion: String?,
    /** The service class ([backendOf]), never its address. */
    val backend: String,
    /** The language translated into, or the voices' language. */
    val language: String?,
    val purpose: Purpose?,
    val rating: Int,
    val comment: String?,
    val ramTier: String,
    val socFamily: String,
    /** How long an answer took ([speedBucket]); null when not measured. */
    val speed: String?,
) {
    enum class Kind(val wire: String) { TRANSLATION("translation"), VOICE("voice") }

    /** What the person uses the model for, from a fixed list, so no free text names a game. */
    enum class Purpose(val wire: String) {
        VISUAL_NOVELS("visual_novels"), RPG_TEXT("rpg_text"), ACCESSIBILITY("accessibility"),
        LANGUAGE_LEARNING("language_learning"), OTHER("other"),
    }

    fun toJson(): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("kind", kind.wire)
        put("model", JSONObject().put("id", model).apply { modelVersion?.let { put("version", it) } })
        put("backend", backend)
        language?.let { put("language", it) }
        purpose?.let { put("purpose", it.wire) }
        put("rating", rating)
        comment?.let { put("comment", it) }
        put("device", JSONObject().put("ramTier", ramTier).put("socFamily", socFamily))
        speed?.let { put("speed", it) }
    }.toString()

    companion object {
        const val SCHEMA = 1
        const val MAX_COMMENT = 200
        const val MAX_NAME = 80
        const val MAX_LANGUAGE = 40

        /** A report from the person's choices, cleaned; null when the rating is not 1 to 5 or there is no model. */
        fun of(
            kind: Kind, model: String, modelVersion: String?, backend: String, language: String?,
            purpose: Purpose?, rating: Int, comment: String?, ramTier: String, socFamily: String, speed: String?,
        ): StatsReport? {
            if (rating !in 1..5) return null
            val name = clean(model, MAX_NAME) ?: return null
            return StatsReport(
                kind, name, modelVersion?.let { clean(it, MAX_NAME) }, backend,
                language?.let { clean(it, MAX_LANGUAGE) }, purpose, rating,
                comment?.let { clean(it, MAX_COMMENT) }, ramTier, socFamily, speed,
            )
        }

        /** One line of plain text: control characters and runs of space taken out, cut to [max]; null when empty. */
        fun clean(text: String, max: Int): String? = text
            .map { if (Character.isISOControl(it)) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)
            .trim()
            .ifEmpty { null }

        /** Services known by name; any other address is only told apart as the person's own network or not. */
        private val KNOWN = listOf(
            "api.openai.com" to "openai",
            "openrouter.ai" to "openrouter",
            "api.deepseek.com" to "deepseek",
            "api.mistral.ai" to "mistral",
            "api.groq.com" to "groq",
            "api.together.xyz" to "together",
            "generativelanguage.googleapis.com" to "google",
        )

        /**
         * The class of the service at [endpoint], which is all a report says about it: a known service by
         * its name, "local-network" for an address on the person's own network or device, "other-service"
         * for anything else. Never the host itself: a server's name or address can identify its owner.
         * Reads no DNS: a name is classed by its spelling alone.
         */
        fun backendOf(endpoint: String): String {
            val host = runCatching { URI(endpoint.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return "other-service"
            KNOWN.firstOrNull { (known, _) -> host == known || host.endsWith(".$known") }?.let { return it.second }
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return "local-network"
            val literal = host.removePrefix("[").removeSuffix("]")
            val isLiteral = literal.all { it.isDigit() || it == '.' } || ':' in literal
            if (isLiteral) {
                val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return "other-service"
                if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return "local-network"
                // IPv6 unique local addresses, fc00::/7.
                if (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC) return "local-network"
            }
            return "other-service"
        }

        /** The device's memory as a tier ("<=4", "6", "8", "12+" GB), never its exact size. */
        fun ramTier(totalBytes: Long): String {
            val gb = totalBytes / (1024.0 * 1024 * 1024)
            return when {
                gb <= 4.5 -> "<=4"
                gb <= 6.5 -> "6"
                gb <= 9.0 -> "8"
                else -> "12+"
            }
        }

        /** The SoC's maker in lower case ("qualcomm"), or "unknown"; never the model or the board. */
        fun socFamily(manufacturer: String?): String =
            manufacturer?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "unknown" }
                ?.let { clean(it, 32) } ?: "unknown"

        /** A typical answer time as a bucket, or null when none was measured. */
        fun speedBucket(millis: Long?): String? = when {
            millis == null || millis < 0 -> null
            millis < 1_000 -> "<1s"
            millis < 3_000 -> "1-3s"
            millis < 10_000 -> "3-10s"
            else -> ">10s"
        }
    }
}
