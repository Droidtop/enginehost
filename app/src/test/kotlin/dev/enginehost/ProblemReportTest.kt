package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

class ProblemReportTest {
    private val secretGame = "Sentinel Quest 9"
    private val folder = File("/storage/emulated/0/Games/$secretGame")

    @Test
    fun aStorageOrAppPathGoesWholeNotJustItsFirstComponent() {
        val out = ProblemReport.scrub(
            "open /storage/1234-ABCD/Private/Stuff/save.dat failed; cache /data/user/0/dev.enginehost/cache/x and /sdcard/Download/a.txt",
            null,
        )
        assertFalse(out, out.contains("Private"))
        assertFalse(out, out.contains("Stuff"))
        assertFalse(out, out.contains("Download"))
        assertFalse(out, out.contains("cache/x"))
        assertTrue(out, out.contains("<storage>") && out.contains("<app>"))
    }

    @Test
    fun theGameFolderAndAHiddenNameBecomeGame() {
        val out = ProblemReport.scrub("loading ${folder.absolutePath}/data.pak then $secretGame crashed", folder, hide = listOf(secretGame))
        assertFalse(out, out.contains(secretGame))
        assertTrue(out, out.contains("<game>"))
    }

    @Test
    fun emailsAddressesAndTokensAreRedacted() {
        val out = ProblemReport.scrub(
            "user jo@example.org from 192.168.1.20 sent Bearer abc.def.ghi and ghp_${"a".repeat(30)} with password=hunter2 token: zzz9",
            null,
        )
        for (secret in listOf("jo@example.org", "192.168.1.20", "abc.def.ghi", "ghp_", "hunter2", "zzz9")) {
            assertFalse("$secret in $out", out.contains(secret))
        }
    }

    @Test
    fun versionNumbersSurviveWhenAddressesAreNotScrubbed() {
        assertEquals("Engine 4.2.1.0", ProblemReport.scrub("Engine 4.2.1.0", null, addresses = false))
    }

    @Test
    fun composeRedactsWhateverWasEditedIn() {
        val text = ProblemReport.compose(
            game = "A Game", engine = "Godot 4.1", symptomHeading = "What happens", symptom = "It does not start",
            details = "mail me at me@example.org",
            environment = "Plugin: godot 1.0\nDevice: X\nsaved at /storage/emulated/0/Secret/x",
            logHeading = "Log", log = "err from 10.0.0.7 in $secretGame", hide = listOf(secretGame),
        )
        for (secret in listOf("me@example.org", "Secret", "10.0.0.7", secretGame)) {
            assertFalse("$secret in $text", text.contains(secret))
        }
        assertTrue(text, text.startsWith("Game: A Game\nEngine: Godot 4.1\nWhat happens: It does not start"))
        assertTrue(text, text.contains("\nLog\n"))
    }

    @Test
    fun theSystemLogKeepsWarningsAndOurOwnTagsOnly() {
        val raw = listOf(
            "09-28 10:00:00.000  100  100 I chatty : noise",
            "09-28 10:00:01.000  100  100 W SomeTag : a warning",
            "09-28 10:00:02.000  100  100 I enginehost : launch",
            "09-28 10:00:03.000  100  100 E AndroidRuntime : crash",
            "not a log line",
        ).joinToString("\n")
        val kept = ProblemReport.trimSystemLog(raw).lines()
        assertEquals(3, kept.size)
        assertFalse(kept.any { it.contains("noise") })
    }

    @Test
    fun theEventRingKeepsTheNewestLinesAndOneLineEach() {
        val ring = HostEvents.trimmed(listOf("a", "b", "c"), "d", 3)
        assertEquals(listOf("b", "c", "d"), ring)
        val line = HostEvents.format(Date(0), "plan:\nfailure   here")
        assertTrue(line, line.endsWith(" plan: failure here"))
        assertFalse(line.contains("\n"))
    }
}
