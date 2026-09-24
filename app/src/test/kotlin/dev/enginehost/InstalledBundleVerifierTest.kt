package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class InstalledBundleVerifierTest {
    private val signedBytes = byteArrayOf(1, 2, 3, 4)
    private val record = BundleFileRecord("lib/arm64-v8a/libengine.so", signedBytes.size.toLong(), sha256(signedBytes), 0b100_100_100)

    private fun bundle(contents: ByteArray = signedBytes): File =
        createTempDir(prefix = "bundle-test").also { root ->
            root.deleteOnExit()
            File(root, record.path).apply { parentFile?.mkdirs(); writeBytes(contents) }
            File(root, PluginRegistry.SIGNED_MANIFEST).writeText("{}")
        }

    private fun stampOf(value: Long) = FileStamp(record.size, value, value, value)

    @Test
    fun `a file whose stamp is unchanged since it was proven is not read again`() {
        // Same size, different bytes: only a hash could tell, and a matching
        // stamp is what says no write has happened since the last one.
        val root = bundle(byteArrayOf(9, 9, 9, 9))
        val known = mapOf(record.path to stampOf(7))

        val result = InstalledBundleVerifier.checkPayload(root, listOf(record), known) { stampOf(7) }

        assertEquals(known, result)
    }

    @Test
    fun `a file whose stamp changed is hashed and refused when its bytes are not the signed ones`() {
        val root = bundle(byteArrayOf(9, 9, 9, 9))

        assertThrows(IllegalArgumentException::class.java) {
            InstalledBundleVerifier.checkPayload(root, listOf(record), mapOf(record.path to stampOf(7))) { stampOf(8) }
        }
    }

    @Test
    fun `a file whose stamp changed but whose bytes are still signed gets its new stamp`() {
        val root = bundle()

        val result = InstalledBundleVerifier.checkPayload(root, listOf(record), emptyMap()) { stampOf(8) }

        assertEquals(mapOf(record.path to stampOf(8)), result)
    }

    @Test
    fun `a file the manifest does not sign refuses the bundle`() {
        val root = bundle()
        File(root, "lib/arm64-v8a/libextra.so").writeBytes(signedBytes)

        assertThrows(IllegalArgumentException::class.java) {
            InstalledBundleVerifier.checkPayload(root, listOf(record), mapOf(record.path to stampOf(7))) { stampOf(7) }
        }
    }

    @Test
    fun `a signed file of the wrong size refuses the bundle whatever its stamp`() {
        val root = bundle(byteArrayOf(1, 2, 3))

        assertThrows(IllegalArgumentException::class.java) {
            InstalledBundleVerifier.checkPayload(root, listOf(record), mapOf(record.path to stampOf(7))) { stampOf(7) }
        }
    }
}
