package dev.enginehost

import android.util.Log
import dev.enginehost.runtime.BrokerListing
import dev.enginehost.runtime.IEngineFileBroker
import java.io.FileNotFoundException

/**
 * The isolated runtime's file layer for native engines (docs/engine-sandbox.md
 * "Files by path: one host VFS for every native engine"). [install] mounts
 * the game and save folders, under the same absolute paths an in-process
 * launch uses, onto their brokers; plugin libraries linked with
 * plugin-native/enginehost_vfs_forward.c then reach them through ordinary
 * libc calls. The static methods below are what isolated_vfs_jni.c calls
 * from whichever engine thread made the request; each answers a
 * descriptor or 0 on success and a negative errno on failure.
 */
internal object IsolatedVfs {
    private const val TAG = "enginehost-isolated-vfs"
    private const val ROOT_GAME = 0
    private const val ROOT_SAVE = 1

    private const val ENOENT = 2
    private const val EIO = 5
    private const val EACCES = 13
    private const val EROFS = 30

    @Volatile private var game: IEngineFileBroker? = null
    @Volatile private var save: IEngineFileBroker? = null

    /** Mounts [gamePath] (writable only when the bundle says its engine saves there) and [savePath]. Before any plugin library loads. */
    fun install(gamePath: String, gameBroker: IEngineFileBroker?, gameWritable: Boolean, savePath: String, saveBroker: IEngineFileBroker?): Boolean {
        game = gameBroker
        save = saveBroker
        val result = runCatching {
            System.loadLibrary("enginehost_sandbox")
            install0(
                gamePath.takeIf { gameBroker != null && it.startsWith("/") }, gameWritable,
                savePath.takeIf { saveBroker != null && it.startsWith("/") },
            )
        }.onFailure { Log.e(TAG, "could not mount the game and save folders", it) }.getOrDefault(-EIO)
        if (result < 0) Log.e(TAG, "mounting the game and save folders failed with errno ${-result}")
        return result >= 0
    }

    private fun broker(root: Int): IEngineFileBroker? = if (root == ROOT_GAME) game else save

    private inline fun answer(block: () -> Int): Int = try {
        block()
    } catch (e: FileNotFoundException) {
        -ENOENT
    } catch (e: SecurityException) {
        -EACCES
    } catch (e: IllegalStateException) {
        // HostFileBroker's read-only check.
        -EROFS
    } catch (e: Exception) {
        Log.w(TAG, "broker call failed", e)
        -EIO
    }

    @JvmStatic
    fun openRead(root: Int, relative: String): Int = answer {
        broker(root)?.openRead(relative)?.detachFd() ?: -ENOENT
    }

    @JvmStatic
    fun openWrite(root: Int, relative: String): Int = answer {
        broker(root)?.openWrite(relative)?.detachFd() ?: -EIO
    }

    @JvmStatic
    fun commitWrite(root: Int, relative: String): Int = answer {
        broker(root)?.commitWrite(relative) ?: return@answer -EIO
        0
    }

    @JvmStatic
    fun remove(root: Int, relative: String): Int = answer {
        if (broker(root)?.delete(relative) == true) 0 else -ENOENT
    }

    @JvmStatic
    fun makeDirectory(root: Int, relative: String): Int = answer {
        if (broker(root)?.makeDirectory(relative) == true) 0 else -EIO
    }

    @JvmStatic
    fun rename(root: Int, from: String, to: String): Int = answer {
        if (broker(root)?.rename(from, to) == true) 0 else -EIO
    }

    /**
     * Directory [relative]'s entries: their names in `names`, and three
     * longs per entry in `info` (kind: 1 file, 2 directory; size; modified
     * time in ms). Null when it is not a directory the broker can list.
     */
    @JvmStatic
    fun list(root: Int, relative: String): BrokerListing? = runCatching {
        broker(root)?.listEntries(relative)
    }.onFailure { Log.w(TAG, "listing $relative failed", it) }.getOrNull()

    @JvmStatic private external fun install0(gamePath: String?, gameWritable: Boolean, savePath: String?): Int
}
