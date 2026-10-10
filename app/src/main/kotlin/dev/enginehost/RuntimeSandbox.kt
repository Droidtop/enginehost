package dev.enginehost

import android.content.ContentProvider
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File

/**
 * The engine sandbox, applied to `:runtime` AND `:runtime_isolated` before
 * any engine code runs in either. Today it is the network half: after
 * [apply] no thread of the process, and no process it starts, can open an
 * IP socket. docs/engine-sandbox.md is the design, including what Android
 * does not let a process take away from itself (arbitrary file access,
 * Binder).
 *
 * Layer 2 (android:isolatedProcess, EnginehostApplication's own
 * `":runtime_isolated"` check) is additional to this, not a replacement
 * for it: a fresh UID with no permissions of its own still starts as an
 * ordinary Linux process that can open a socket unless something stops
 * it. This filter is a plain syscall deny-list (socket(AF_INET/AF_INET6),
 * io_uring_setup, cross-arch calls) with everything else -- including the
 * pread/pwrite/memfd_create/fcntl this milestone's own fd-passing and
 * shared-memory mechanisms use on both sides of the isolation boundary --
 * falling through to ALLOW, so it needed no new rule to cover the
 * isolated process's own I/O; see `runtime_sandbox.c`'s own filter
 * program for the exact instruction-by-instruction proof of that. It
 * returns EACCES for what it denies, never SIGSYS/SECCOMP_RET_KILL, so
 * this filter itself cannot be the source of a process death; a SIGSYS
 * seen on either process would have to come from the platform's own
 * filter underneath this one (installed by zygote before either of these
 * ever run), which this code does not touch and does not suppress the
 * kernel's own audit trail for.
 *
 * Applying it is best effort by decision: a device where the filter cannot
 * be installed (a 32-bit x86 build, a kernel that refuses it) still runs
 * games, as every device did before the sandbox existed, and says so in
 * the log under [TAG].
 */
object RuntimeSandbox {
    private const val TAG = "EnginehostSandbox"

    /** Once per process; the filter cannot be removed, and a second copy would add nothing. */
    fun apply() {
        val result = runCatching {
            System.loadLibrary("enginehost_sandbox")
            denyInternet()
        }
        when (val code = result.getOrNull()) {
            0 -> Log.i(TAG, "Network denied to every thread of the runtime process")
            1 -> Log.w(TAG, "Network denied only to the main thread and the threads it starts")
            null -> Log.w(TAG, "Network NOT denied: the sandbox library did not load", result.exceptionOrNull())
            else -> Log.w(TAG, "Network NOT denied: errno ${-code}")
        }
    }

    /**
     * Process lockdown, the prototype (docs/engine-sandbox.md "Process
     * lockdown"): off unless `debug.enginehost.lockdown` is 1 (`adb shell
     * setprop`), so nobody meets it before the shared sandbox design is
     * agreed.
     */
    fun lockdownRequested(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.enginehost.lockdown") == "1"
    }.getOrDefault(false)

    // Held for the life of the process: a stable provider reference keeps
    // the broker's process alive with this one, and takes this one down if
    // the broker's dies, so a locked-down process is never left brokerless.
    @Volatile private var broker: ContentProviderClient? = null

    /**
     * Confines this process (sbx: app/src/main/cpp/sbx/sbx.h): from here on
     * no thread can name a file except through the broker in the main
     * process ([SandboxBrokerProvider]), whose policy is the game folder
     * (read-only unless [gameWritable]), the save folder, the bundle, the
     * system's own code and data, and the devices a GPU driver opens; and
     * none can make a socket, run a program or ptrace. Call before any
     * plugin code is loaded. False when the process could not be confined.
     */
    fun lockdown(context: Context, game: File, gameWritable: Boolean, save: File, bundle: File): Boolean {
        val pair = ParcelFileDescriptor.createReliableSocketPair()
        val served = try {
            val client = broker ?: context.contentResolver.acquireContentProviderClient(SandboxBrokerProvider.AUTHORITY)
            broker = client
            client?.call(
                SandboxBrokerProvider.METHOD_SERVE, null,
                Bundle().apply {
                    putParcelable(SandboxBrokerProvider.SOCKET, pair[0])
                    putString(SandboxBrokerProvider.GAME, game.absolutePath)
                    putBoolean(SandboxBrokerProvider.GAME_WRITABLE, gameWritable)
                    putString(SandboxBrokerProvider.SAVE, save.absolutePath)
                    putString(SandboxBrokerProvider.BUNDLE, bundle.absolutePath)
                },
            )?.getBoolean(SandboxBrokerProvider.SERVED) == true
        } catch (e: Exception) {
            Log.w(TAG, "The sandbox broker did not answer", e)
            false
        } finally {
            pair[0].close()
        }
        if (!served) {
            pair[1].close()
            return false
        }
        val result = lockdown0(pair[1].detachFd(), OWN_OPENS)
        if (result == 0) Log.i(TAG, "Process locked down: files only through the broker, no sockets, no exec")
        else Log.w(TAG, "Process NOT locked down: errno ${-result}")
        return result == 0
    }

    /** The broker's rules for one launch; see [lockdown]. */
    internal fun policy(context: Context, game: String, gameWritable: Boolean, save: String, bundle: String): Map<String, Int> {
        val rules = LinkedHashMap<String, Int>()
        listOf(
            "/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/linkerconfig",
            "/data/dalvik-cache", "/data/fonts", "/data/resource-cache", "/proc", "/sys", "/dev/__properties__",
            File(context.applicationInfo.sourceDir).parent ?: context.applicationInfo.sourceDir,
            context.applicationInfo.nativeLibraryDir,
        ).forEach { rules[it] = SBX_READ }
        // Devices a GPU driver, ART and the audio stack open after the fact.
        val ashmem = runCatching { "/dev/ashmem" + File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
        listOfNotNull(
            "/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/ashmem", ashmem,
            "/dev/binder", "/dev/hwbinder", "/dev/vndbinder", "/dev/kgsl-3d0", "/dev/ion", "/dev/dma_heap",
            "/dev/dri", "/dev/mali0", "/dev/goldfish_pipe", "/dev/goldfish_pipe_dprctd", "/dev/goldfish_sync",
            "/dev/goldfish_address_space", "/dev/qemu_pipe",
        ).forEach { rules[it] = SBX_READ or SBX_WRITE }
        // Shader and plugin caches; the rest of the app's private data stays out.
        rules[context.cacheDir.absolutePath] = SBX_READ or SBX_WRITE
        rules[context.codeCacheDir.absolutePath] = SBX_READ or SBX_WRITE
        rules[bundle] = SBX_READ
        rules[game] = if (gameWritable) SBX_READ or SBX_WRITE else SBX_READ
        rules[save] = SBX_READ or SBX_WRITE
        return rules
    }

    /** The broker side, in the main process: serves [fd] on its own thread under [rules]. */
    internal fun serve(fd: Int, rules: Map<String, Int>): Boolean {
        System.loadLibrary("enginehost_sandbox")
        return serve0(fd, rules.keys.toTypedArray(), rules.values.toIntArray()) == 0
    }

    /**
     * Devices this process opens for itself before the lockdown, because the
     * kernel ties the open file to the opener: binder (libhidl maps
     * /dev/hwbinder; a broker-opened one fails with EINVAL) and the GPU
     * drivers that keep per-process state (kgsl, Mali, DRM render nodes).
     */
    private val OWN_OPENS = arrayOf("/dev/hwbinder", "/dev/vndbinder", "/dev/kgsl-3d0", "/dev/mali0", "/dev/dri/renderD128")

    private const val SBX_READ = 1
    private const val SBX_WRITE = 2

    @JvmStatic private external fun denyInternet(): Int
    @JvmStatic private external fun lockdown0(fd: Int, ownOpens: Array<String>): Int
    @JvmStatic private external fun serve0(fd: Int, paths: Array<String>, modes: IntArray): Int
}

/**
 * The process lockdown's broker, in the main process (see
 * [RuntimeSandbox.lockdown]). A provider rather than a service because
 * the call is synchronous, and a stable reference ties the two processes'
 * lives together. One session per calling process, and only from this
 * app's own processes: a confined process that asks again for a wider
 * policy is refused.
 */
class SandboxBrokerProvider : ContentProvider() {
    private val served = HashSet<Int>()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SERVE || extras == null) return null
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Not this app's process")
        val pid = Binder.getCallingPid()
        synchronized(served) { if (!served.add(pid)) throw SecurityException("Process $pid already has a broker") }
        @Suppress("DEPRECATION")
        val socket = extras.getParcelable<ParcelFileDescriptor>(SOCKET) ?: return null
        val game = extras.getString(GAME) ?: return null.also { socket.close() }
        val save = extras.getString(SAVE) ?: return null.also { socket.close() }
        val bundle = extras.getString(BUNDLE) ?: return null.also { socket.close() }
        val rules = RuntimeSandbox.policy(context!!, game, extras.getBoolean(GAME_WRITABLE), save, bundle)
        val ok = RuntimeSandbox.serve(socket.detachFd(), rules)
        return Bundle().apply { putBoolean(SERVED, ok) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "dev.enginehost.sandbox"
        const val METHOD_SERVE = "serve"
        const val SOCKET = "socket"
        const val GAME = "game"
        const val GAME_WRITABLE = "gameWritable"
        const val SAVE = "save"
        const val BUNDLE = "bundle"
        const val SERVED = "served"
    }
}
