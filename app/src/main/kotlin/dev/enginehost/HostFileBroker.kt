package dev.enginehost

import android.os.ParcelFileDescriptor
import dev.enginehost.runtime.BrokerListing
import dev.enginehost.runtime.IEngineFileBroker
import java.io.File
import java.io.IOException

/**
 * [IEngineFileBroker] bound to one root -- the game folder (read-only) or
 * the save folder (read-write) -- for exactly one launch. Path safety is
 * the same [resolveWithinRoot] every other in-process file access in this
 * app already uses.
 */
internal class HostFileBroker(private val root: File, private val readOnly: Boolean) : IEngineFileBroker.Stub() {
    override fun list(relativePath: String): Array<String> =
        runCatching { resolveWithinRoot(root, relativePath).list() ?: emptyArray() }.getOrDefault(emptyArray())

    override fun openRead(relativePath: String): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(resolveWithinRoot(root, relativePath), ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()

    override fun openWrite(relativePath: String): ParcelFileDescriptor? {
        check(!readOnly) { "the game folder is read-only" }
        return runCatching {
            val target = resolveWithinRoot(root, relativePath)
            target.parentFile?.mkdirs()
            ParcelFileDescriptor.open(pendingWritePath(target), WRITE_MODE)
        }.getOrNull()
    }

    override fun commitWrite(relativePath: String) {
        check(!readOnly) { "the game folder is read-only" }
        val target = resolveWithinRoot(root, relativePath)
        val pending = pendingWritePath(target)
        if (!pending.renameTo(target)) throw IOException("could not put $relativePath in place")
    }

    override fun delete(relativePath: String): Boolean {
        check(!readOnly) { "the game folder is read-only" }
        return resolveWithinRoot(root, relativePath).delete()
    }

    override fun listEntries(relativePath: String): BrokerListing? {
        val directory = resolveWithinRoot(root, relativePath)
        val children = directory.listFiles() ?: return null
        // A write still waiting for commitWrite is not an entry yet.
        val visible = children.filterNot { it.name.endsWith(PENDING_SUFFIX) }
        val info = LongArray(visible.size * 3)
        visible.forEachIndexed { i, child ->
            val isDirectory = child.isDirectory
            info[3 * i] = if (isDirectory) BrokerListing.KIND_DIRECTORY else BrokerListing.KIND_FILE
            info[3 * i + 1] = if (isDirectory) 0 else child.length()
            info[3 * i + 2] = child.lastModified()
        }
        return BrokerListing(visible.map { it.name }.toTypedArray(), info)
    }

    override fun makeDirectory(relativePath: String): Boolean {
        check(!readOnly) { "the game folder is read-only" }
        return resolveWithinRoot(root, relativePath).mkdir()
    }

    override fun rename(fromPath: String, toPath: String): Boolean {
        check(!readOnly) { "the game folder is read-only" }
        val from = resolveWithinRoot(root, fromPath)
        val to = resolveWithinRoot(root, toPath)
        if (!from.exists()) return false
        // File.renameTo does not replace an existing file on every
        // filesystem shared storage sits on; a save written as "write a
        // temporary, rename it over the old one" must still land.
        if (to.isFile && from.isFile) to.delete()
        return from.renameTo(to)
    }

    /**
     * A fresh file beside [target]'s eventual name: nothing appears under
     * that name until [commitWrite] renames it there, matching
     * EngineFileBroker's own contract (crash or no commit, no visible
     * partial write). Not a per-write random name: at most one write per
     * relativePath is ever open at a time, by contract.
     */
    private fun pendingWritePath(target: File): File = File(target.parentFile, "${target.name}$PENDING_SUFFIX")

    companion object {
        private const val PENDING_SUFFIX = ".isolated-write"
        private const val WRITE_MODE =
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
    }
}
