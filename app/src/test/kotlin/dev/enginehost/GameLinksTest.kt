package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GameLinksTest {
    private val kinds = GameLinks.parseKinds(File("src/main/assets/link-kinds.json").readText())

    private fun parse(url: String) = GameLinks.parse(url, kinds)

    @Test
    fun linksAreClassifiedByTheData() {
        assertEquals("discord", parse("https://discord.gg/abc123")?.kind?.id)
        assertEquals("discord", parse("https://discord.com/invite/abc123")?.kind?.id)
        assertEquals("discord", parse("discord://-/invite/abc123")?.kind?.id)
        assertEquals("youtube", parse("https://www.youtube.com/watch?v=x")?.kind?.id)
        assertEquals("steam", parse("https://store.steampowered.com/app/1/")?.kind?.id)
        assertEquals("itch", parse("https://somedev.itch.io/game")?.kind?.id)
        // A Discord page that is not an invite or a channel is a web page.
        assertNull(parse("https://discord.com/terms")?.kind)
        // A lookalike domain is not the service.
        assertNull(parse("https://discord.gg.evil.example/x")?.kind)
        assertNull(parse("https://notdiscord.gg/x")?.kind)
        assertEquals("https://example.com/a?b=c#d", parse("https://Example.COM/a?b=c#d")?.text)
    }

    @Test
    fun onlyWebEmailAndListedAppLinksPass() {
        listOf(
            "intent://x#Intent;scheme=https;end", "file:///sdcard/a", "content://x/y", "javascript:alert(1)",
            "data:text/html,x", "steam://run/570", "market://details?id=x", "ftp://example.com",
            "https://good.example@evil.example/", "https://", "https:///path", "https://[::1]/",
            "https://example.com/a b", "https://example.com/\u0000", "https://example.com\\@evil.example",
            "https://example.com:99999999/", "", "x".repeat(GameLinks.MAX_LENGTH + 1),
        ).forEach { assertNull(it, parse(it)) }
        assertEquals("mailto:dev@example.com", parse("mailto:dev@example.com")?.text)
    }

    @Test
    fun anInternationalHostIsShownBothWays() {
        val link = parse("https://bücher.example/x")!!
        assertEquals("xn--bcher-kva.example", link.host)
        assertEquals("bücher.example", link.displayHost)
        assertEquals("https://xn--bcher-kva.example/x", link.text)
    }
}
