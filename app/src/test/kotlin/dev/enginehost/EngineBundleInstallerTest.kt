package dev.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineBundleInstallerTest {
    private fun tempRoot(): File = createTempDir(prefix = "installer-test").also { it.deleteOnExit() }

    @Test
    fun `sweepOrphanedStagingIn deletes a leaked staging directory, including its read-only payload files`() {
        // Regression test: EngineBundleInstaller.install writes each payload
        // file mode 0444 as it extracts. A staging directory left behind by a
        // killed process (rather than one that failed by throwing, which the
        // install() catch block already handles) can contain such read-only
        // files, and a plain deleteRecursively() silently leaves them in place.
        val root = tempRoot()
        val staging = File(root, STAGING_PREFIX + "deadbeef").apply { mkdirs() }
        val payload = File(staging, "classes.dex").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        check(payload.setWritable(false, false)) { "test setup needs to be able to lock down a file" }

        EngineBundleInstaller.sweepOrphanedStagingIn(root)

        assertFalse(staging.exists())
    }

    @Test
    fun `sweepOrphanedStagingIn leaves a real installed bundle directory alone`() {
        val root = tempRoot()
        val staging = File(root, STAGING_PREFIX + "deadbeef").apply { mkdirs() }
        File(staging, "classes.dex").writeBytes(byteArrayOf(1, 2, 3))
        val installed = File(root, "godot--abc123").apply { mkdirs() }
        val installedFile = File(installed, "classes.dex").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        EngineBundleInstaller.sweepOrphanedStagingIn(root)

        assertFalse(staging.exists())
        assertTrue(installed.isDirectory)
        assertTrue(installedFile.isFile)
    }
}
