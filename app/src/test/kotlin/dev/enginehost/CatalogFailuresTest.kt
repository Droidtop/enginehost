package dev.enginehost

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reason a refresh gives for failing. A fresh device showed an empty
 * catalog with no explanation because a spent GitHub allowance and a
 * genuinely empty repository looked identical to the screen; these are the
 * distinctions that fixed that.
 */
class CatalogFailuresTest {
    @Test
    fun `a spent allowance is a rate limit with its reset time`() {
        val failure = CatalogFailures.of(
            GithubHttpException(403, rateLimitRemaining = 0, rateLimitResetEpochSeconds = 1_757_500_000, message = "x"),
        )
        assertEquals(CatalogFailure.RateLimited(1_757_500_000), failure)
    }

    @Test
    fun `429 is a rate limit too`() {
        assertEquals(
            CatalogFailure.RateLimited(null),
            CatalogFailures.of(GithubHttpException(429, null, null, "x")),
        )
    }

    @Test
    fun `403 with allowance left is not a rate limit`() {
        assertEquals(
            CatalogFailure.Http(403),
            CatalogFailures.of(GithubHttpException(403, rateLimitRemaining = 42, rateLimitResetEpochSeconds = 1, message = "x")),
        )
    }

    @Test
    fun `any other status is reported as itself`() {
        assertEquals(CatalogFailure.Http(500), CatalogFailures.of(GithubHttpException(500, null, null, "x")))
    }

    @Test
    fun `nothing reachable reads as offline`() {
        assertEquals(CatalogFailure.Offline, CatalogFailures.of(UnknownHostException("api.github.com")))
        assertEquals(CatalogFailure.Offline, CatalogFailures.of(ConnectException("ECONNREFUSED")))
        assertEquals(CatalogFailure.Offline, CatalogFailures.of(SocketTimeoutException("timeout")))
    }

    @Test
    fun `a wrapped failure keeps the reason of the one that knows`() {
        val wrapped = IllegalStateException(
            "refreshing origin",
            IOException("reading", GithubHttpException(403, 0, 99, "limit")),
        )
        assertEquals(CatalogFailure.RateLimited(99), CatalogFailures.of(wrapped))
    }

    @Test
    fun `everything else keeps its own words`() {
        assertEquals(
            CatalogFailure.Other("Release signer does not match the repository's pinned key"),
            CatalogFailures.of(IllegalArgumentException("Release signer does not match the repository's pinned key")),
        )
    }
}
