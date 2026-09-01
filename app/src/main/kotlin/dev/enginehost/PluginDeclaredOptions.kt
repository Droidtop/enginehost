package dev.enginehost

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One option a plugin declares that its engine actually reads.
 *
 * The declaration is advisory and explicitly NON-EXHAUSTIVE: it exists so
 * the config editor can offer a labeled, typed control instead of a blind
 * JSON field. It is never a validation schema -- a user can always add an
 * option no plugin declared, an undeclared option is never invalid, and a
 * config never fails to save because of one. That keeps version skew
 * harmless in both directions: newer bundles may declare more, and older
 * bundles that declare nothing simply take every option as a custom entry.
 */
data class DeclaredOption(
    val key: String,
    val label: String,
    /** One of "path", "boolean", "number", "string", "choice". */
    val type: String,
    /** True when the value is a JSON array of the type (e.g. rtpPaths). */
    val repeats: Boolean,
    val description: String,
    /** For type "choice": value to human label. */
    val choices: List<Pair<String, String>>,
)

/**
 * Reads `declaredOptions` from installed bundles' signed manifests, the
 * on-device copy of each repository's bundle-metadata.json. Contract, per
 * entry: `key` (required), `label`, `type` ("path" | "boolean" | "number" |
 * "string" | "choice", default "string"), `repeats` (default false),
 * `description`, and for "choice" a `choices` array of {value, label}.
 *
 * Read fresh on every call: a declared list can grow between plugin
 * versions, so nothing here is cached as permanent truth.
 */
object DeclaredOptionsReader {
    fun forEngine(context: Context, engine: String): List<DeclaredOption> =
        PluginRegistry.discover(context)
            .filter { it.info.engine == engine }
            .flatMap { plugin ->
                runCatching {
                    parse(JSONObject(File(plugin.directory, PluginRegistry.SIGNED_MANIFEST).readText()))
                }.getOrDefault(emptyList())
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }

    fun parse(manifest: JSONObject): List<DeclaredOption> {
        val declarations = manifest.optJSONArray("declaredOptions") ?: return emptyList()
        return buildList {
            for (index in 0 until declarations.length()) {
                val entry = declarations.optJSONObject(index) ?: continue
                val key = entry.optString("key").takeIf { it.isNotBlank() } ?: continue
                val choices = buildList {
                    val array = entry.optJSONArray("choices")
                    if (array != null) {
                        for (choiceIndex in 0 until array.length()) {
                            val choice = array.optJSONObject(choiceIndex) ?: continue
                            val value = choice.optString("value").takeIf { it.isNotBlank() } ?: continue
                            add(value to choice.optString("label").ifBlank { value })
                        }
                    }
                }
                add(
                    DeclaredOption(
                        key = key,
                        label = entry.optString("label").ifBlank { key },
                        type = entry.optString("type").ifBlank { "string" },
                        repeats = entry.optBoolean("repeats", false),
                        description = entry.optString("description"),
                        choices = choices,
                    ),
                )
            }
        }
    }
}
