package dev.enginehost

import org.junit.Assert.assertThrows
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

    @Test
    fun `an arm64-only bundle is refused on an x86_64 device, naming both sides`() {
        // The sentence is the whole point: a person installing a file by
        // hand needs to hear what the bundle ships and what this device
        // runs, not an UnsatisfiedLinkError at first launch. Both halves
        // have to be in the message or the refusal explains nothing.
        val error = assertThrows(IllegalArgumentException::class.java) {
            EngineBundleInstaller.requireRunnableAbis(
                "dev.enginehost.kirikiri.v1",
                listOf("arm64-v8a"),
                listOf("x86_64", "x86"),
            )
        }
        assertTrue(error.message!!.contains("arm64-v8a"))
        assertTrue(error.message!!.contains("x86_64"))
    }

    @Test
    fun `a bundle is accepted when the device runs any ABI it ships, wherever listed`() {
        // Build.SUPPORTED_ABIS is the device's order of preference, and an
        // emulator that runs arm64 through binary translation lists it
        // after x86_64: position in the list must not matter, only
        // membership, or the check would disagree with the load-time
        // choice it mirrors.
        EngineBundleInstaller.requireRunnableAbis(
            "dev.enginehost.example.v1",
            listOf("arm64-v8a"),
            listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a"),
        )
        EngineBundleInstaller.requireRunnableAbis(
            "dev.enginehost.example.v1",
            listOf("arm64-v8a", "x86_64"),
            listOf("x86_64", "x86"),
        )
    }

    @Test
    fun `a bundle with no native code is architecture-agnostic and installs anywhere`() {
        EngineBundleInstaller.requireRunnableAbis("dev.enginehost.html.v1", emptyList(), listOf("x86_64"))
    }
}
