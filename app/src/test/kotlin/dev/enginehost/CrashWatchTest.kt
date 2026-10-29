package dev.enginehost

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashWatchTest {
    @Test
    fun androidEndingTheProcessIsSaidToBeAndroidsDoing() {
        assertEquals("it needed the memory", CrashWatch.stoppedByAndroid(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(
            "a system service it was using stopped",
            CrashWatch.stoppedByAndroid(ApplicationExitInfo.REASON_DEPENDENCY_DIED),
        )
        assertEquals(
            "it was using too much of the device",
            CrashWatch.stoppedByAndroid(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE),
        )
    }

    @Test
    fun theGameOrThePersonEndingItIsNot() {
        assertNull(CrashWatch.stoppedByAndroid(ApplicationExitInfo.REASON_EXIT_SELF))
        assertNull(CrashWatch.stoppedByAndroid(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertNull(CrashWatch.stoppedByAndroid(ApplicationExitInfo.REASON_CRASH))
    }
}
