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
    /** One of "path", "file", "boolean", "number", "string", "choice". */
    val type: String,
    /** True when the value is a JSON array of the type (e.g. rtpPaths). */
    val repeats: Boolean,
    val description: String,
    /** For type "choice": value to human label. */
    val choices: List<Pair<String, String>>,
    /**
     * For type "file": an advisory MIME hint so the picker offers the kind
     * of file the option actually takes. Empty means offer everything, and
     * a declaration that omits it is not thereby wrong -- like every other
     * part of this declaration, it suggests and never gates.
     */
    val mimeTypes: List<String> = emptyList(),
)

/**
 * Reads `declaredOptions` from installed bundles' signed manifests, the
 * on-device copy of each repository's bundle-metadata.json. Contract, per
 * entry: `key` (required), `label`, `type` ("path" | "file" | "boolean" |
 * "number" | "string" | "choice", default "string"), `repeats` (default
 * false), `description`, for "choice" a `choices` array of {value, label},
 * and for "file" an optional `mimeTypes` array of hints. "path" is a
 * folder and "file" is a single file; a declaration that predates "file"
 * keeps meaning exactly what it meant, and an unrecognised type falls back
 * to the free-text editor rather than being rejected.
 *
 * Read fresh on every call: a declared list can grow between plugin
 * versions, so nothing here is cached as permanent truth.
 */
object DeclaredOptionsReader {
    /**
     * The declarations that apply to a config, given the bundle its launch
     * resolves to.
     *
     * Matching on the engine name alone was wrong: mkxp-z, EasyRPG and RPG
     * Maker MV/MZ all declare engine "rpgmaker", so their lists collapsed
     * into one and an MV game was offered mkxp-z's rgssVersion, which means
     * nothing to it. A bundle is the real unit here. By the time the editor
     * offers options the launch has resolved to exactly one bundle, and
     * asking that bundle what it honours is both correct and unambiguous --
     * engine plus context would still be ambiguous, since two bundles can
     * serve one context, as mkxp-z's own ruby19 and ruby31 capabilities do.
     */
    fun forResolvedBundle(context: Context, resolved: InstalledPlugin?, engine: String?): List<DeclaredOption> =
        merge(
            applicableBundles(installedBundles(context), resolved, engine).flatMap { plugin ->
                runCatching {
                    parse(JSONObject(File(plugin.directory, PluginRegistry.SIGNED_MANIFEST).readText()))
                }.getOrDefault(emptyList())
            },
        )

    private fun installedBundles(context: Context): List<InstalledPlugin> =
        runCatching { PluginRegistry.discover(context) }.getOrDefault(emptyList())

    /**
     * Split from the manifest reading so the rule itself is testable with no
     * Context and no files.
     *
     * With a resolved bundle the answer is that bundle and nothing else.
     * Without one -- a config still being written before any plugin that
     * could run it is installed -- there is no honest single answer, so
     * every bundle for the engine contributes, in bundle-ID order. The order
     * matters: [merge] keeps the first declaration of a duplicate key, and
     * the previous code took whatever order the filesystem happened to list
     * directories in, so which description a user saw for a key like
     * `fullscreen` was not defined. Alphabetical by bundle ID is arbitrary
     * but it is stable, and the same everywhere.
     */
    internal fun applicableBundles(
        installed: List<InstalledPlugin>,
        resolved: InstalledPlugin?,
        engine: String?,
    ): List<InstalledPlugin> {
        if (resolved != null) return listOf(resolved)
        if (engine.isNullOrBlank()) return emptyList()
        return installed
            .filter { it.info.runs(engine) }
            .sortedWith(compareBy<InstalledPlugin> { it.bundleId }.thenByDescending { it.info.pluginVersion })
    }

    /** First declaration of a key wins; the result is ordered for display. */
    internal fun merge(declarations: List<DeclaredOption>): List<DeclaredOption> =
        declarations
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.key }))

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
                val mimeTypes = buildList {
                    val array = entry.optJSONArray("mimeTypes")
                    if (array != null) {
                        for (mimeIndex in 0 until array.length()) {
                            array.optString(mimeIndex).takeIf { it.isNotBlank() }?.let(::add)
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
                        mimeTypes = mimeTypes,
                    ),
                )
            }
        }
    }
}
