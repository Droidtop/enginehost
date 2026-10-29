package dev.enginehost

import android.content.Context
import dev.droidtop.shared.net.GitHubClient

/**
 * The one way Enginehost talks to GitHub: droidtop-shared's `:net` (Droidtop/tracker#194, #195). It decides which requests
 * may carry the person's token (the three GitHub hosts, exactly; never the pre-signed address a release asset redirects to,
 * which refuses a request that carries both) and follows redirects hop by hop, https only.
 *
 * [withToken] is for reading plugin sources (the catalog, the engine registry, a repository's files): the token the person
 * saved in Settings goes on those requests and no other. [anonymous] is for everything else (Enginehost's own update,
 * plugin bundles), which never sends the token.
 */
object GithubClients {
    private const val USER_AGENT = "enginehost/0.1"
    private const val TAG = "EnginehostFetch"

    fun withToken(context: Context): GitHubClient =
        client { GithubTokenStore(context.applicationContext).get() }

    fun anonymous(): GitHubClient = client { null }

    private fun client(token: () -> String?) = GitHubClient(
        userAgent = USER_AGENT,
        token = token,
        // One line per request (Droidtop/tracker#550), with the allowance left, so a refresh that runs dry says so in logcat
        // as well as on the screen.
        onResponse = { url, status, left -> android.util.Log.i(TAG, "GET $url -> $status, rate limit remaining $left") },
    )
}
