package dev.enginehost

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * The one way Enginehost talks to a server that is not GitHub (Droidtop/tracker#768): voice model
 * downloads, the translator the person chose, and the stats endpoint. GitHub traffic stays on the
 * shared client ([GithubClients]).
 *
 * Before this, each of the four built its own connection, with its own timeouts and User-Agent,
 * and a catch that turned every failure into null. Here:
 * - Redirects are followed by hand, at most [MAX_REDIRECTS], and only to https. A download that
 *   pinned its file by digest still must not hand its request to a plain-http host.
 * - A plain-http address is refused unless the caller allows it, and a request carrying an
 *   Authorization header never goes over plain http: a translator key typed beside an http
 *   address is not sent.
 * - What is read is bounded ([TooLarge]).
 * - A failure is a [Failure] that says what went wrong; the caller decides what the person sees.
 */
object Http {
    /** Why a request did not give an answer: [status] is the server's code when it gave one. */
    open class Failure(message: String, val status: Int? = null) : IOException(message)

    /** The answer was longer than the caller's bound. */
    class TooLarge(val limit: Long) : Failure("The answer is larger than $limit bytes")

    const val MAX_REDIRECTS = 5

    class Request(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
        /** Plain http is allowed for this address (a server on the person's own network). */
        val allowPlainHttp: Boolean = false,
        val userAgent: String = "enginehost",
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 60_000,
    )

    /** [request]'s answer body, at most [maxBytes]. Network: not the main thread. */
    fun read(request: Request, maxBytes: Long): ByteArray = open(request) { input -> readAtMost(input, maxBytes) }

    /** Runs [use] on [request]'s answer stream once it answered 2xx, and closes the connection after. */
    fun <T> open(request: Request, use: (InputStream) -> T): T {
        var url = request.url
        var hops = 0
        while (true) {
            checkScheme(url, request)
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = request.connectTimeoutMs
                connection.readTimeout = request.readTimeoutMs
                connection.requestMethod = request.method
                connection.setRequestProperty("User-Agent", request.userAgent)
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw Failure("A redirect named no address", status)
                    if (++hops > MAX_REDIRECTS) throw Failure("Too many redirects", status)
                    url = URI(url).resolve(location).toString()
                    // Only a redirect to https is followed, whatever the first address was.
                    if (URI(url).scheme != "https") throw Failure("A redirect led away from https", status)
                    continue
                }
                if (status !in 200..299) throw Failure("The server answered $status", status)
                return connection.inputStream.use(use)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Whether [url] may be asked as [request] says: https always, http only when allowed and with no Authorization. */
    internal fun checkScheme(url: String, request: Request) {
        when (URI(url).scheme) {
            "https" -> Unit
            "http" -> {
                if (!request.allowPlainHttp) throw Failure("Only https addresses are used")
                if (request.headers.keys.any { it.equals("Authorization", ignoreCase = true) }) {
                    throw Failure("A key is never sent over plain http")
                }
            }
            else -> throw Failure("Only https addresses are used")
        }
    }

    private fun readAtMost(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw TooLarge(limit)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
