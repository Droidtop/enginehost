package dev.enginehost

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineReportFactsTest {
    @Test
    fun aTombstoneKeepsTheSignalAndTheLibraryFramesOnly() {
        val text = "Build fingerprint: 'x'\npid: 4, tid: 4, name: game  >>> dev.enginehost:runtime <<<\n" +
            "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0\nregisters x0 0000\n" +
            "    #00 pc 000000000012a4 /data/app/x/lib/arm64/libgame.so (Render+52)\nunrelated line here\n"
        val out = CrashWatch.summarizeNative("signal 11 (SIGSEGV)", text.toByteArray())
        assertTrue(out, out.startsWith("signal 11 (SIGSEGV)\n"))
        assertTrue(out, out.contains("libgame.so") && out.contains("SEGV_MAPERR"))
        assertTrue(out, !out.contains("unrelated") && !out.contains("Build fingerprint"))
    }

    @Test
    fun aBinaryTombstoneStillYieldsItsPrintableFrames() {
        val bytes = byteArrayOf(0x0a, 0x03, 0x01, 0x02) + "/system/lib64/libc.so".toByteArray() + byteArrayOf(0x00, 0x12, 0x7f) +
            "junk".toByteArray()
        val out = CrashWatch.summarizeNative(null, bytes)
        assertEquals("/system/lib64/libc.so", out)
    }

    @Test
    fun onlyWarningsAndWorseReachTheEngineRingAndRepeatsAreDropped() {
        assertNull(HostEvents.engineLine(Log.INFO, "t", "hello", null))
        val line = HostEvents.engineLine(Log.ERROR, "gl", "context lost\nmore", null)
        assertEquals("error gl: context lost", line)
        assertNull(HostEvents.engineLine(Log.ERROR, "gl", "context lost", line))
    }
}
