package dev.enginehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Droidtop/tracker#885: a game-ended save report is kept until droidtop takes it. */
class SavesOutboxTest {
    @get:Rule val temp = TemporaryFolder()

    private val now = 1_000_000_000_000L
    private fun file(): File = File(temp.root, "outbox.json")
    private fun report(path: String = "/sdcard/Games/A", folder: String = "/sdcard/Saves/A", at: Long = now) =
        SavesOutbox.Report(path, folder, "exited", at)

    private fun reply(accepted: Boolean, message: String = "") =
        JSONObject().put("ok", true).put("data", JSONObject().put("accepted", accepted).put("message", message))

    @Test
    fun `a report kept while droidtop is away is sent when it connects`() {
        val f = file()
        SavesOutbox.add(f, report(), now)
        // droidtop not connected: nothing answers, the report stays.
        assertEquals(0, SavesOutbox.deliver(f, now) { null })
        assertEquals(1, SavesOutbox.pending(f).size)
        // droidtop connects and takes it.
        val sent = mutableListOf<JSONObject>()
        assertEquals(1, SavesOutbox.deliver(f, now) { sent += it.args(); reply(true) })
        assertTrue(SavesOutbox.pending(f).isEmpty())
        assertFalse(f.exists())
        val args = sent.single()
        assertEquals("/sdcard/Games/A", args.getString("path"))
        assertEquals("/sdcard/Saves/A", args.getString("saveFolder"))
        assertEquals("exited", args.getString("reason"))
        assertEquals(now, args.getLong("endedAtMs"))
        assertFalse("bookkeeping is not sent", args.has("attempts"))
    }

    @Test
    fun `it survives a restart, since it lives in the file`() {
        val f = file()
        SavesOutbox.add(f, report(), now)
        val reread = SavesOutbox.pending(File(f.path))
        assertEquals(listOf(report()), reread)
    }

    @Test
    fun `not ready yet is retried, a final refusal is dropped`() {
        val f = file()
        SavesOutbox.add(f, report(path = "/g/1", folder = "/s/1"), now)
        SavesOutbox.add(f, report(path = "/g/2", folder = "/s/2"), now)
        SavesOutbox.deliver(f, now) {
            if (it.path == "/g/1") reply(false, "droidtop's library is not ready yet; tell it again in a moment")
            else reply(false, "droidtop has no game at /g/2")
        }
        assertEquals(listOf("/g/1"), SavesOutbox.pending(f).map { it.path })
        assertEquals(1, SavesOutbox.pending(f).single().attempts)
    }

    @Test
    fun `a failed call is retried and stops the round`() {
        val f = file()
        SavesOutbox.add(f, report(path = "/g/1", folder = "/s/1"), now)
        SavesOutbox.add(f, report(path = "/g/2", folder = "/s/2"), now)
        var asked = 0
        SavesOutbox.deliver(f, now) { asked++; null }
        assertEquals("no broker: the rest are not tried", 1, asked)
        assertEquals(2, SavesOutbox.pending(f).size)
        // An answer that is not ok (the broker's own failure) is a retry too.
        SavesOutbox.deliver(f, now) { JSONObject().put("ok", false) }
        assertEquals(2, SavesOutbox.pending(f).size)
    }

    @Test
    fun `a newer end of the same game and folder replaces the older report`() {
        val f = file()
        SavesOutbox.add(f, report(at = now - 1000), now)
        SavesOutbox.add(f, report(at = now), now)
        SavesOutbox.add(f, report(folder = "/sdcard/Other"), now)
        val pending = SavesOutbox.pending(f)
        assertEquals(2, pending.size)
        assertEquals(now, pending.first { it.saveFolder == "/sdcard/Saves/A" }.endedAtMs)
    }

    @Test
    fun `old reports and too many reports are dropped, and nothing is retried for ever`() {
        val f = file()
        SavesOutbox.add(f, report(path = "/old", at = now - SavesOutbox.MAX_AGE_MS - 1), now)
        assertTrue(SavesOutbox.pending(f).isEmpty())
        repeat(SavesOutbox.MAX_PENDING + 5) { SavesOutbox.add(f, report(path = "/g/$it", folder = "/s/$it"), now) }
        val pending = SavesOutbox.pending(f)
        assertEquals(SavesOutbox.MAX_PENDING, pending.size)
        assertEquals("the oldest go first", "/g/5", pending.first().path)

        val g = File(temp.root, "retries.json")
        SavesOutbox.add(g, report(), now)
        repeat(SavesOutbox.MAX_ATTEMPTS) { SavesOutbox.deliver(g, now) { reply(false, "not ready yet") } }
        assertTrue(SavesOutbox.pending(g).isEmpty())
    }

    @Test
    fun `outcome reads droidtop's reply`() {
        assertEquals(SavesOutbox.Outcome.RETRY, SavesOutbox.outcome(null))
        assertEquals(SavesOutbox.Outcome.RETRY, SavesOutbox.outcome(JSONObject().put("ok", false)))
        assertEquals(SavesOutbox.Outcome.DELIVERED, SavesOutbox.outcome(reply(true, "steam: uploading")))
        assertEquals(SavesOutbox.Outcome.REFUSED, SavesOutbox.outcome(reply(false, "droidtop takes saves only from a folder in shared storage")))
        assertEquals(SavesOutbox.Outcome.RETRY, SavesOutbox.outcome(reply(false, "droidtop's library is not ready yet; tell it again in a moment")))
    }

    @Test
    fun `a broken file is read as empty and replaced`() {
        val f = file()
        f.writeText("not json")
        assertTrue(SavesOutbox.pending(f).isEmpty())
        SavesOutbox.add(f, report(), now)
        assertEquals(1, SavesOutbox.pending(f).size)
    }
}
