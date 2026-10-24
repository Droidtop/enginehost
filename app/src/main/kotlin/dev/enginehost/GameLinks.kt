package dev.enginehost

import org.json.JSONObject
import java.net.IDN
import java.net.URI

/** One kind of link a game opens, and the apps it is offered to (assets/link-kinds.json). */
data class LinkKind(
    val id: String,
    /** The service's own name, shown as is ("Discord"). */
    val name: String,
    val schemes: Set<String>,
    /** Matched as the domain itself or any subdomain of it. */
    val domains: List<String>,
    /** Per domain, the path prefixes that make a link this kind; a domain not listed matches on any path. */
    val paths: Map<String, List<String>>,
    /** The apps that handle it, preferred first. */
    val packages: List<String>,
)

/**
 * A link a game asked to open, checked: what [GameLinkOffer] shows and opens. [text] is the
 * normalized ASCII form that is opened and copied; [host] is the ASCII host, [displayHost] the
 * same host as a person reads it (an internationalized domain decoded), so a lookalike shows as
 * one. [kind] is null for a plain web page or an email.
 */
data class GameLink(
    val text: String,
    val scheme: String,
    val host: String?,
    val displayHost: String?,
    val kind: LinkKind?,
) {
    val isWeb: Boolean get() = scheme == "https" || scheme == "http"
    val isEmail: Boolean get() = scheme == "mailto"
}

/**
 * Checks a link a game asks to open (EngineHost.openLink; docs/SPEC.md, "Links a game opens";
 * owner, 2026-10-23: "Enginehost should parse the link type (safely), and then OFFER to open
 * it"). Strict: a length cap, no control characters or backslashes, no user name in front of a
 * host (`https://good.example@evil.example`), a host that converts cleanly to ASCII, and only
 * https, http, mailto and the app schemes [kinds] list. Everything else (intent:, file:,
 * content:, javascript:, data:, steam://run/...) is refused. Never Intent.parseUri.
 */
object GameLinks {
    const val MAX_LENGTH = 2048
    private val BASE_SCHEMES = setOf("https", "http", "mailto")
    private val SCHEME = Regex("[a-z][a-z0-9+.-]*")
    private val PORT = Regex("[0-9]{1,5}")

    /** The checked link, or null when it is refused. */
    fun parse(raw: String, kinds: List<LinkKind>): GameLink? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        if (trimmed.any { it.code < 0x21 || it.code == 0x7f || it == '\\' }) return null
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null
        val scheme = trimmed.substring(0, colon).lowercase()
        if (!scheme.matches(SCHEME)) return null
        if (scheme !in BASE_SCHEMES && kinds.none { scheme in it.schemes }) return null
        val uri = runCatching { URI(scheme + encodeNonAscii(trimmed.substring(colon))) }.getOrNull() ?: return null
        if (scheme == "mailto") {
            val address = uri.rawSchemeSpecificPart
            if (!uri.isOpaque || address.isNullOrEmpty()) return null
            return GameLink(uri.toASCIIString(), scheme, null, null, null)
        }
        if (uri.isOpaque) return null
        // An app's own scheme goes only to that app, which reads the rest itself (Discord's
        // links name "-" as their host); it is classified by the scheme alone.
        if (scheme != "https" && scheme != "http") {
            return GameLink(uri.toASCIIString(), scheme, null, null, classify(scheme, null, uri.rawPath.orEmpty(), kinds))
        }
        val authority = uri.rawAuthority
        if (authority == null || authority.isEmpty()) return null
        // No user name in front of the host, and no bracketed IP literal.
        if ('@' in authority || authority.startsWith('[')) return null
        val hostPart = authority.substringBeforeLast(':').takeIf { ':' in authority } ?: authority
        val port = authority.substringAfterLast(':', "").takeIf { ':' in authority }
        if (port != null && !port.matches(PORT)) return null
        val decoded = runCatching { java.net.URLDecoder.decode(hostPart, "UTF-8") }.getOrNull() ?: return null
        val ascii = runCatching { IDN.toASCII(decoded, IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return null
        if (ascii.isEmpty() || ascii.startsWith('.') || ascii.endsWith('.')) return null
        val display = runCatching { IDN.toUnicode(ascii) }.getOrDefault(ascii)
        val path = uri.rawPath.orEmpty()
        val text = buildString {
            append(scheme).append("://").append(ascii)
            if (port != null) append(':').append(port)
            append(path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
        return GameLink(text, scheme, ascii, display, classify(scheme, ascii, path, kinds))
    }

    /** The kind of a link: by its scheme, else by its domain and path; null for a plain web page. */
    internal fun classify(scheme: String, host: String?, path: String, kinds: List<LinkKind>): LinkKind? {
        kinds.firstOrNull { scheme in it.schemes }?.let { return it }
        if (host == null) return null
        return kinds.firstOrNull { kind ->
            kind.domains.any { domain ->
                (host == domain || host.endsWith(".$domain")) &&
                    kind.paths[domain]?.let { prefixes -> prefixes.any { path == it || path.startsWith("$it/") } } != false
            }
        }
    }

    /** Percent-encodes every non-ASCII character as UTF-8; ASCII is left exactly as written. */
    private fun encodeNonAscii(text: String): String = buildString {
        text.forEach { char ->
            if (char.code < 0x80) append(char)
            else char.toString().toByteArray(Charsets.UTF_8).forEach { append('%').append("%02X".format(it.toInt() and 0xff)) }
        }
    }

    /** Reads assets/link-kinds.json. */
    fun parseKinds(json: String): List<LinkKind> {
        val array = JSONObject(json).getJSONArray("kinds")
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            fun strings(name: String): List<String> = entry.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val paths = entry.optJSONObject("paths")?.let { object_ ->
                object_.keys().asSequence().associateWith { key ->
                    object_.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
                }
            }.orEmpty()
            LinkKind(
                id = entry.getString("id"),
                name = entry.getString("name"),
                schemes = strings("schemes").map { it.lowercase() }.toSet(),
                domains = strings("domains").map { it.lowercase() },
                paths = paths,
                packages = strings("packages"),
            )
        }
    }
}
