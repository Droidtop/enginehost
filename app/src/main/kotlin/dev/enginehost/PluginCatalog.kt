package dev.enginehost

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** One signed engine bundle advertised by a repository's GitHub release. */
data class AvailablePlugin(
    val manifest: EngineBundleManifest,
    val signatureBytes: ByteArray,
    val releaseTag: String,
    val archiveUrl: String,
    val archiveSha256: String?,
    val prerelease: Boolean,
) {
    val info: PluginInfo get() = manifest.info
    val origin: String get() = manifest.origin
    val bundleId: String get() = manifest.bundleId
    val apiVersion: Int get() = manifest.apiVersion
    val signerFingerprints: Set<String> get() = setOf(manifest.signingKeySha256)
}

object PluginReleaseReader {
    /**
     * A release catalog asset is a single JSON envelope containing copies of
     * each bundle's internally signed header. The bundle remains independently
     * verifiable; this envelope only avoids downloading every archive to browse.
     */
    fun parse(
        raw: String,
        expectedOrigin: String,
        releaseTag: String,
        prerelease: Boolean,
        releaseAssets: Map<String, Pair<String, String?>>,
        keys: PluginOriginKeyStore,
    ): List<AvailablePlugin> {
        val root = JSONObject(raw)
        require(root.getInt("formatVersion") == 1) { "Unsupported release catalog envelope" }
        val bundles = root.getJSONArray("bundles")
        return (0 until bundles.length()).map { index ->
            val envelope = bundles.getJSONObject(index)
            val manifestBytes = Base64.getDecoder().decode(envelope.requiredString("manifestBase64"))
            val signature = Base64.getDecoder().decode(envelope.requiredString("signatureBase64"))
            val manifest = EngineBundleManifestReader.parse(manifestBytes)
            EngineBundleManifestReader.verifySignature(manifest, signature)
            require(manifest.origin == normalizeGithubOrigin(expectedOrigin)) { "Release origin mismatch" }
            require(keys.matches(manifest.origin, manifest.signingKeySha256)) {
                "Release signer does not match the repository's pinned key"
            }
            val asset = requireNotNull(releaseAssets[manifest.assetName]) {
                "Release is missing ${manifest.assetName}"
            }
            AvailablePlugin(
                manifest,
                signature,
                releaseTag,
                asset.first,
                asset.second?.let(::normalizeGithubDigest),
                prerelease,
            )
        }
    }
}

class GithubPluginCatalogClient(private val context: Context) {
    fun fetch(origin: String, includePrereleases: Boolean = false): List<AvailablePlugin> {
        val normalized = normalizeGithubOrigin(origin)
        val match = GITHUB_ORIGIN.matchEntire(normalized) ?: error("Not a GitHub repository origin")
        var next: String? = "https://api.github.com/repos/${match.groupValues[1]}/${match.groupValues[2]}/releases?per_page=100"
        val result = mutableListOf<AvailablePlugin>()
        while (next != null) {
            val response = get(next)
            val releases = JSONArray(response.body)
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                if (release.optBoolean("draft")) continue
                val prerelease = release.optBoolean("prerelease")
                if (prerelease && !includePrereleases) continue
                val assets = release.getJSONArray("assets")
                val byName = linkedMapOf<String, Pair<String, String?>>()
                var catalogUrl: String? = null
                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.getJSONObject(assetIndex)
                    val name = asset.getString("name")
                    val url = asset.getString("browser_download_url")
                    byName[name] = url to asset.optString("digest").takeIf(String::isNotBlank)
                    if (name == RELEASE_CATALOG) catalogUrl = url
                }
                if (catalogUrl == null) continue
                result += PluginReleaseReader.parse(
                    get(catalogUrl).body,
                    normalized,
                    release.getString("tag_name"),
                    prerelease,
                    byName,
                    PluginOriginKeyStore(context),
                )
            }
            next = nextLink(response.link)
        }
        return result
    }

    private data class Response(val body: String, val link: String?)

    private fun get(url: String): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "enginehost/0.1")
        try {
            require(connection.responseCode in 200..299) { "GitHub returned HTTP ${connection.responseCode}" }
            val output = ByteArrayOutputStream()
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (output.size() <= MAX_RESPONSE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
            require(output.size() <= MAX_RESPONSE_BYTES) { "Catalog response is too large" }
            return Response(output.toString(Charsets.UTF_8.name()), connection.getHeaderField("Link"))
        } finally {
            connection.disconnect()
        }
    }

    private fun nextLink(link: String?): String? = link?.split(',')?.firstNotNullOfOrNull { part ->
        val segments = part.trim().split(';')
        if (segments.drop(1).any { it.trim() == "rel=\"next\"" }) {
            segments.first().trim().removePrefix("<").removeSuffix(">")
        } else null
    }

    companion object {
        private const val RELEASE_CATALOG = "enginehost-release.json"
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}

object AvailablePluginResolver {
    fun compatible(
        plugins: List<AvailablePlugin>,
        engine: String,
        engineContext: String?,
        engineVersion: Version,
        runtimeRequirements: Map<String, Version>,
        pluginVersionAllowlist: VersionConstraint?,
    ): List<Pair<AvailablePlugin, EngineCapability>> {
        val requestedContext = engineContext ?: DEFAULT_ENGINE_CONTEXT
        return plugins.asSequence()
            .filter { it.apiVersion == dev.enginehost.api.EnginePluginContract.API_VERSION }
            .filter { it.info.engine == engine }
            .filter { pluginVersionAllowlist == null || pluginVersionAllowlist.matches(it.info.pluginVersion) }
            .flatMap { plugin -> plugin.info.capabilities.asSequence().map { plugin to it } }
            .filter { (_, capability) ->
                capability.engineContext == requestedContext && capability.supports(engineVersion) &&
                    capability.satisfies(runtimeRequirements)
            }
            .sortedWith(
                compareByDescending<Pair<AvailablePlugin, EngineCapability>> { it.second.runtimeVersion == engineVersion }
                    .thenBy { it.second.specificityFor(engineVersion) }
                    .thenByDescending { it.first.info.pluginVersion }
                    .thenBy { it.first.bundleId },
            )
            .toList()
    }
}

class PluginCatalogCache(context: Context) {
    private val directory = File(context.filesDir, "plugin-catalogs-v2").apply { mkdirs() }
    private val keys = PluginOriginKeyStore(context)

    fun save(origin: String, plugins: List<AvailablePlugin>) {
        val root = JSONObject().put("origin", normalizeGithubOrigin(origin)).put(
            "plugins",
            JSONArray().apply { plugins.forEach { put(it.toJson()) } },
        )
        file(origin).writeText(root.toString())
    }

    fun load(origin: String): List<AvailablePlugin> = runCatching {
        val array = JSONObject(file(origin).readText()).getJSONArray("plugins")
        (0 until array.length()).map { availablePluginFromJson(array.getJSONObject(it), keys) }
    }.getOrDefault(emptyList())

    fun loadAll(origins: Collection<String>): List<AvailablePlugin> = origins.flatMap(::load)

    private fun file(origin: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(normalizeGithubOrigin(origin).toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        return File(directory, "$name.json")
    }
}

private fun AvailablePlugin.toJson() = JSONObject()
    .put("manifestBase64", Base64.getEncoder().encodeToString(manifest.rawBytes))
    .put("signatureBase64", Base64.getEncoder().encodeToString(signatureBytes))
    .put("releaseTag", releaseTag)
    .put("archiveUrl", archiveUrl)
    .put("archiveSha256", archiveSha256)
    .put("prerelease", prerelease)

private fun availablePluginFromJson(json: JSONObject, keys: PluginOriginKeyStore): AvailablePlugin {
    val manifestBytes = Base64.getDecoder().decode(json.requiredString("manifestBase64"))
    val signature = Base64.getDecoder().decode(json.requiredString("signatureBase64"))
    val manifest = EngineBundleManifestReader.parse(manifestBytes)
    EngineBundleManifestReader.verifySignature(manifest, signature)
    require(keys.matches(manifest.origin, manifest.signingKeySha256)) { "Cached catalog key no longer matches" }
    return AvailablePlugin(
        manifest,
        signature,
        json.requiredString("releaseTag"),
        json.requiredString("archiveUrl"),
        json.optString("archiveSha256").takeIf(String::isNotBlank)?.let(::normalizeGithubDigest),
        json.optBoolean("prerelease"),
    )
}

class PluginOriginStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plugin-origins-v2", Context.MODE_PRIVATE)

    fun all(): List<String> = (DEFAULT_ORIGINS + custom()).distinct().sorted()

    fun add(origin: String, rawKeyDocument: String) {
        val normalized = normalizeGithubOrigin(origin)
        require(GITHUB_ORIGIN.matches(normalized)) { "Use a GitHub repository URL" }
        PluginOriginKeyStore(context).importCustom(rawKeyDocument, normalized)
        preferences.edit().putStringSet(CUSTOM, custom() + normalized).apply()
    }

    fun remove(origin: String) {
        val normalized = normalizeGithubOrigin(origin)
        preferences.edit().putStringSet(CUSTOM, custom() - normalized).apply()
        PluginOriginKeyStore(context).removeCustom(normalized)
    }

    fun isDefault(origin: String): Boolean = normalizeGithubOrigin(origin) in DEFAULT_ORIGINS
    private fun custom(): Set<String> = preferences.getStringSet(CUSTOM, emptySet())?.toSet().orEmpty()

    companion object {
        private const val CUSTOM = "custom"
        val DEFAULT_ORIGINS = setOf(
            "https://github.com/droidtop/enginehost-renpy-plugin",
            "https://github.com/droidtop/enginehost-rpgmaker-mkxp-z-plugin",
            "https://github.com/droidtop/enginehost-rpgmaker-easyrpg-plugin",
            "https://github.com/droidtop/enginehost-rpgmaker-mv-mz-plugin",
            "https://github.com/droidtop/enginehost-kirikiri-plugin",
            "https://github.com/droidtop/enginehost-buriko-plugin",
            "https://github.com/droidtop/enginehost-catsystem2-plugin",
            "https://github.com/droidtop/enginehost-cmvs-plugin",
            "https://github.com/droidtop/enginehost-flash-air-plugin",
            "https://github.com/droidtop/enginehost-twine-plugin",
            "https://github.com/droidtop/enginehost-godot-plugin",
        )
    }
}

internal val GITHUB_ORIGIN = Regex("https://github\\.com/([^/]+)/([^/]+)", RegexOption.IGNORE_CASE)
internal fun normalizeGithubOrigin(value: String): String {
    val candidate = value.trim().trimEnd('/').removeSuffix(".git")
    val match = GITHUB_ORIGIN.matchEntire(candidate) ?: return candidate
    return "https://github.com/${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
}
private fun normalizeGithubDigest(value: String): String = value.removePrefix("sha256:").uppercase().also {
    require(it.matches(Regex("[A-F0-9]{64}"))) { "Invalid GitHub asset digest" }
}
