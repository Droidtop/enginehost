package dev.enginehost

import android.content.ContentProvider
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.widget.Toast
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
 * be installed (a kernel that refuses it) still runs
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
     * Whether a launch of [plugin] is locked down: always when the core
     * declares `lockdown` (docs/engine-sandbox.md "The lockdown is how a core
     * that cannot be isolated is sandboxed"), and for any core while
     * `debug.enginehost.lockdown` is 1 (`adb shell setprop`), which is only
     * for running a core's rig check before it declares it.
     */
    fun locksDown(plugin: InstalledPlugin): Boolean = plugin.lockdown || lockdownRequested() || learning(plugin)

    private fun lockdownRequested(): Boolean = DebugProperty.get("lockdown") == "1"

    /**
     * Whether a launch of [plugin] is a learning run: `debug.enginehost.learn`
     * names its bundle id, or is `all` (the core harness's --learn). Such a
     * launch is locked down with nothing refused and every would-be refusal
     * recorded ([SandboxLearner]), whether or not the core declares lockdown;
     * it has NO sandbox, and says so (docs/engine-sandbox.md, "Learning mode").
     */
    fun learning(plugin: InstalledPlugin): Boolean = DebugProperty.get("learn").let { it == plugin.bundleId || it == "all" }

    // Held for the life of the process: a stable provider reference keeps
    // the broker's process alive with this one, and takes this one down if
    // the broker's dies, so a locked-down process is never left brokerless.
    @Volatile private var broker: ContentProviderClient? = null

    /**
     * Confines this process (the sandbox library, vendor/sandbox, sbx.h): from here on
     * no thread can name a file except through the broker in the main
     * process ([SandboxBrokerProvider]), whose policy is the game folder
     * (read-only unless [gameWritable]), the save folder, the bundle, the
     * system's own code and data, and the devices a GPU driver opens; and
     * none can make a socket, run a program or ptrace. Call before any
     * plugin code is loaded. False when the process could not be confined.
     */
    fun lockdown(
        context: Context,
        game: File,
        gameWritable: Boolean,
        save: File,
        bundles: List<File>,
        /** The game sub-folders the core writes ([InstalledPlugin.gameWrites]); the rest of the game folder stays read-only. */
        gameWrites: List<String> = emptyList(),
        /** The core being launched; a learning run needs it (null for the debug lockdown probe, which never learns). */
        plugin: InstalledPlugin? = null,
    ): Boolean {
        // Once per process: the activity transport's resource relaunch comes
        // through here a second time, already confined.
        if (confined) return true
        val learn = plugin != null && learning(plugin)
        val confinedSide = serveBroker(context, game, gameWritable, save, bundles, gameWrites, plugin, isolated = false)
            ?: return false
        // Earlier runtimes that ended locked down (a restart loop leaves one each), then this one's ring.
        reportEndedTraces(context)
        val traced = runCatching { trace0(traceFile(context, Process.myPid()).absolutePath) }.getOrDefault(-1)
        if (traced != 0) Log.w(TAG, "No sandbox trace for this process: errno ${-traced}")
        confined = confine(confinedSide, learn)
        return confined
    }

    /**
     * The clamp for an isolated runtime (docs/engine-sandbox.md, "Both layers for every core"):
     * from `:runtime`, has the broker serve [isolatedPolicy] and answers the confined side's end
     * of the socket, which [IsolatedRuntimeHost] hands the isolated service with its init call
     * and the service locks itself down on ([confineIsolated]) before any plugin code loads.
     * Null when the broker did not answer; a launch that needs the lockdown then does not start.
     * [game], [save] and [bundles] only name paths in a learning run's profile: the isolated
     * policy grants none of them, because the isolated process reaches the game and save folders
     * only through the descriptors the host hands it ([HostFileBroker]).
     */
    fun brokerForIsolated(context: Context, plugin: InstalledPlugin, game: File, save: File): ParcelFileDescriptor? =
        serveBroker(context, game, false, save, listOf(plugin.directory), emptyList(), plugin, isolated = true)

    /** In the isolated service: locks this process down on [broker] ([brokerForIsolated]). */
    fun confineIsolated(broker: ParcelFileDescriptor, learn: Boolean): Boolean {
        System.loadLibrary("enginehost_sandbox")
        return confine(broker, learn).also { confined = it }
    }

    /** Has the broker serve one launch's rules; answers the confined side's end of the socket, or null. */
    private fun serveBroker(
        context: Context,
        game: File,
        gameWritable: Boolean,
        save: File,
        bundles: List<File>,
        gameWrites: List<String>,
        plugin: InstalledPlugin?,
        isolated: Boolean,
    ): ParcelFileDescriptor? {
        val learn = plugin != null && learning(plugin)
        if (learn && plugin != null) {
            val warning = "LEARNING MODE: ${plugin.bundleId}: nothing is refused, this run has NO sandbox"
            Log.w(TAG, warning)
            HostEvents.record(context, warning)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, warning, Toast.LENGTH_LONG).show()
            }
        }
        val pair = ParcelFileDescriptor.createReliableSocketPair()
        val served = try {
            val client = broker ?: context.contentResolver.acquireContentProviderClient(SandboxBrokerProvider.AUTHORITY)
            broker = client
            client?.call(
                SandboxBrokerProvider.METHOD_SERVE, null,
                Bundle().apply {
                    putParcelable(SandboxBrokerProvider.SOCKET, pair[0])
                    putBoolean(SandboxBrokerProvider.ISOLATED, isolated)
                    putString(SandboxBrokerProvider.GAME, game.absolutePath)
                    putBoolean(SandboxBrokerProvider.GAME_WRITABLE, gameWritable)
                    putStringArray(SandboxBrokerProvider.GAME_WRITES, gameWrites.toTypedArray())
                    putString(SandboxBrokerProvider.SAVE, save.absolutePath)
                    putStringArray(SandboxBrokerProvider.BUNDLES, bundles.map { it.absolutePath }.toTypedArray())
                    if (learn && plugin != null) {
                        putString(SandboxBrokerProvider.LEARN_CORE, plugin.bundleId)
                        putString(SandboxBrokerProvider.LEARN_BUILD, plugin.info.pluginVersion.toString())
                        putString(SandboxBrokerProvider.LEARN_ARCHIVE, plugin.archiveSha256)
                    }
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
            return null
        }
        return pair[1]
    }

    /** Locks this process down on [broker]; whether it is confined. */
    private fun confine(broker: ParcelFileDescriptor, learn: Boolean): Boolean {
        val audit = DebugProperty.get("audit") == "1"
        if (audit) Log.w(TAG, "AUDIT: the kernel logs every trapped call of this process (audit type=1326)")
        val result = lockdown0(broker.detachFd(), OWN_OPENS, learn, audit)
        if (result == 0) Log.i(TAG, "Process locked down: files only through the broker, no sockets, no exec")
        else Log.w(TAG, "Process NOT locked down: errno ${-result}")
        // EDEADLK: a thread has SIGSYS blocked and would kill the process on its first trapped call.
        if (result == -EDEADLK) Log.w(TAG, "Threads with SIGSYS blocked: ${sigsysBlockedThreads().joinToString()}")
        return result == 0
    }

    /**
     * Where a locked-down runtime's trace ring lives (the sandbox library's
     * sbx_trace): one file per process, so a restart loop does not write
     * over the ring of the process that died. docs/engine-sandbox.md,
     * "When a lockdown kills".
     */
    private fun traceFile(context: Context, pid: Int): File =
        File(context.filesDir, "sandbox-trace").apply { mkdirs() }.let { File(it, "$pid.bin") }

    /** The trace of runtime process [pid] as sentences, or null when it was not locked down. */
    fun traceOf(context: Context, pid: Int): String? {
        val file = traceFile(context, pid)
        if (!file.isFile) return null
        return runCatching {
            System.loadLibrary("enginehost_sandbox")
            traceSummary0(file.absolutePath)
        }.getOrNull()
    }

    /** Logs the trace of every earlier runtime that has ended, then deletes it; a live one is left alone. */
    private fun reportEndedTraces(context: Context) {
        val folder = File(context.filesDir, "sandbox-trace")
        folder.listFiles { file -> file.name.endsWith(".bin") }.orEmpty().forEach { file ->
            val pid = file.name.removeSuffix(".bin").toIntOrNull() ?: return@forEach
            if (pid == Process.myPid() || File("/proc/$pid").exists()) return@forEach
            // A line per frame of a fatal signal: one logcat message holds about 4 KB.
            traceOf(context, pid)?.lines()?.forEachIndexed { k, line ->
                Log.w(TAG, if (k == 0) "Sandbox trace of an ended runtime: $line" else line)
            }
            file.delete()
        }
    }

    /** The names of this process's threads that have SIGSYS blocked, from /proc. */
    private fun sigsysBlockedThreads(): List<String> =
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
            runCatching {
                val blocked = File(task, "status").readLines().firstOrNull { it.startsWith("SigBlk:") }
                    ?.substringAfter(':')?.trim()?.toULong(16) ?: return@runCatching null
                if (blocked and SIGSYS_BIT == 0UL) null else "${File(task, "comm").readText().trim()} (${task.name})"
            }.getOrNull()
        }

    private const val EDEADLK = 35
    private const val SIGSYS_BIT = 0x40000000UL // 1 << (SIGSYS 31 - 1)

    /** Whether this process is locked down; it cannot be undone. */
    @Volatile var confined = false
        private set

    /** The broker's rules for one launch; see [lockdown]. */
    internal fun policy(
        context: Context,
        game: String,
        gameWritable: Boolean,
        save: String,
        bundles: List<String>,
        gameWrites: List<String> = emptyList(),
    ): Map<String, Int> {
        val rules = LinkedHashMap<String, Int>()
        listOf(
            "/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/linkerconfig",
            "/data/dalvik-cache", "/data/fonts", "/data/resource-cache", "/proc", "/sys", "/dev/__properties__",
            // Time zone data (tzdata updates land here, not in /apex).
            "/data/misc/zoneinfo",
            // Adopted storage's mount root, which Android's storage code stats and opens at start.
            // Refused, LÖVE died with SIGSEGV about a second in, and it was LÖVE's only refusal
            // (KiriKiri and mkxp-z too; #550, 2026-10-24). It holds one folder per adopted volume,
            // whose app folders stay closed to other apps by their own permissions.
            "/mnt/expand",
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
            // BlueStacks' GL pipe, its goldfish_pipe: refused, EGL cannot reach the host and ART
            // aborts creating a context (Ren'Py 8.4 under lockdown, Droidtop/tracker#550).
            "/dev/bstpgaipc",
        ).forEach { rules[it] = SBX_READ or SBX_WRITE }
        // Shader and plugin caches; the rest of the app's private data stays out.
        rules[context.cacheDir.absolutePath] = SBX_READ or SBX_WRITE
        rules[context.codeCacheDir.absolutePath] = SBX_READ or SBX_WRITE
        // The host's own settings the game process reads and the in-game menu changes: the
        // controller bindings in their own folder, and exactly two preference files (each with
        // the backup SharedPreferences renames it to while writing). Nothing else of the app's.
        rules[ControllerBindingFile.folder(context).apply { mkdirs() }.absolutePath] = SBX_READ or SBX_WRITE
        val preferences = File(context.dataDir, "shared_prefs")
        listOf(HostMenuHotkeyStore.PREFERENCES, ControllerProfileStore.PREFERENCES).forEach { name ->
            rules[File(preferences, "$name.xml").absolutePath] = SBX_READ or SBX_WRITE
            rules[File(preferences, "$name.xml.bak").absolutePath] = SBX_READ or SBX_WRITE
        }
        // Enginehost's own notes that its code in this process keeps writing while the game runs:
        // the session note (CrashWatch: drawn, watched, quit, a crash's trace), the host and
        // engine event rings, and where the engine saves (EngineSaveFolders). Refused, every
        // locked-down core lost black-screen detection, crash reports, its log and GAME_ENDED's
        // save folder (learning profiles, #550, 2026-10-24). Exactly these; nothing else of the app's.
        (listOf(CrashWatch.noteFile(context)) + HostEvents.runtimeFiles(context))
            .forEach { rules[it.absolutePath] = SBX_READ or SBX_WRITE }
        rules[EngineSaveFolders.directory(context).apply { mkdirs() }.absolutePath] = SBX_READ or SBX_WRITE
        // HWUI keeps its shader cache in the device-protected code cache.
        rules[context.createDeviceProtectedStorageContext().codeCacheDir.absolutePath] = SBX_READ or SBX_WRITE
        // The engine's bundle and each verified component bundle the launch loads.
        bundles.forEach { rules[it] = SBX_READ }
        // A WebView core: the WebView provider's code, and WebView's own
        // profile folders in this app's data. Its renderer is a separate
        // process Android isolates itself (docs/engine-sandbox.md).
        webViewCode(context).forEach { rules[it] = SBX_READ }
        context.dataDir.listFiles { file -> file.isDirectory && file.name.startsWith("app_webview") }.orEmpty()
            .forEach { rules[it.absolutePath] = SBX_READ or SBX_WRITE }
        rules[File(context.dataDir, "app_webview").absolutePath] = SBX_READ or SBX_WRITE
        rules[game] = if (gameWritable) SBX_READ or SBX_WRITE else SBX_READ
        // The longest rule decides, so these stay writable inside a read-only game folder.
        gameWrites.filter(GameWrites::isSubFolder).forEach { rules[File(game, it).path] = SBX_READ or SBX_WRITE }
        rules[save] = SBX_READ or SBX_WRITE
        return rules
    }

    /**
     * The broker's rules for an isolated runtime: only what an isolated process can already
     * reach (the system's code and data, its own app's code, and a few devices that hold
     * nothing). The broker opens files as this app, so granting an isolated process the game,
     * save, cache or any app data here would widen it, not clamp it; those reach it only as
     * descriptors over binder, as before. No GPU device: an isolated process renders in software.
     */
    internal fun isolatedPolicy(context: Context): Map<String, Int> {
        val rules = LinkedHashMap<String, Int>()
        listOf(
            "/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/linkerconfig",
            "/data/dalvik-cache", "/proc", "/sys", "/dev/__properties__", "/data/misc/zoneinfo",
            File(context.applicationInfo.sourceDir).parent ?: context.applicationInfo.sourceDir,
            context.applicationInfo.nativeLibraryDir,
        ).forEach { rules[it] = SBX_READ }
        val ashmem = runCatching { "/dev/ashmem" + File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
        listOfNotNull("/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/ashmem", ashmem)
            .forEach { rules[it] = SBX_READ or SBX_WRITE }
        return rules
    }

    /** The current WebView provider's APK folder and native libraries, when there is one. */
    private fun webViewCode(context: Context): List<String> {
        val info = runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.applicationInfo }.getOrNull() ?: return emptyList()
        return listOfNotNull(info.sourceDir?.let { File(it).parent }, info.nativeLibraryDir)
    }

    /** The broker side, in the main process: serves [fd] on its own thread under [rules]. */
    internal fun serve(fd: Int, rules: Map<String, Int>, learner: SandboxLearner? = null): Boolean {
        System.loadLibrary("enginehost_sandbox")
        return serve0(fd, rules.keys.toTypedArray(), rules.values.toIntArray(), learner) == 0
    }

    /**
     * The folders a learning run's profile names paths by, so one profile
     * serves every game of a core: the launch's own, then the app's and
     * shared storage, each under the names Android also gives it.
     */
    internal fun learnRoots(context: Context, game: String, save: String, bundles: List<String>): List<Pair<String, String>> {
        val app = context.packageName
        return listOf("\$GAME" to game, "\$SAVE" to save) +
            bundles.map { "\$BUNDLE" to it } +
            listOf(
                "\$CACHE" to context.cacheDir.absolutePath, "\$CACHE" to context.codeCacheDir.absolutePath,
                "\$APP" to context.dataDir.absolutePath, "\$APP" to "/data/user/0/$app", "\$APP" to "/data/data/$app",
                "\$EXTERNAL" to "/storage/emulated/0", "\$EXTERNAL" to "/sdcard",
            )
    }

    /**
     * Devices this process opens for itself before the lockdown, because the
     * kernel ties the open file to the opener: binder (libhidl maps
     * /dev/hwbinder; a broker-opened one fails with EINVAL) and the GPU
     * drivers that keep per-process state (kgsl, Mali, DRM render nodes).
     */
    private val OWN_OPENS = arrayOf("/dev/hwbinder", "/dev/kgsl-3d0", "/dev/mali0", "/dev/dri/renderD128")

    private const val SBX_READ = 1
    private const val SBX_WRITE = 2

    @JvmStatic private external fun denyInternet(): Int
    @JvmStatic private external fun lockdown0(fd: Int, ownOpens: Array<String>, learn: Boolean, audit: Boolean): Int
    @JvmStatic private external fun trace0(path: String): Int
    @JvmStatic private external fun traceSummary0(path: String): String?
    @JvmStatic private external fun serve0(fd: Int, paths: Array<String>, modes: IntArray, learner: SandboxLearner?): Int
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
    private val served = HashSet<String>()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SERVE || extras == null) return null
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Not this app's process")
        val pid = Binder.getCallingPid()
        // One broker per process for its own lockdown, and one for the isolated runtime it starts.
        val isolated = extras.getBoolean(ISOLATED)
        val session = if (isolated) "$pid:isolated" else "$pid"
        synchronized(served) { if (!served.add(session)) throw SecurityException("Process $pid already has a broker") }
        @Suppress("DEPRECATION")
        val socket = extras.getParcelable<ParcelFileDescriptor>(SOCKET) ?: return null
        val game = extras.getString(GAME) ?: return null.also { socket.close() }
        val save = extras.getString(SAVE) ?: return null.also { socket.close() }
        val bundles = extras.getStringArray(BUNDLES)?.toList()?.takeIf { it.isNotEmpty() } ?: return null.also { socket.close() }
        val gameWrites = extras.getStringArray(GAME_WRITES)?.toList().orEmpty()
        val rules = if (isolated) RuntimeSandbox.isolatedPolicy(context!!)
        else RuntimeSandbox.policy(context!!, game, extras.getBoolean(GAME_WRITABLE), save, bundles, gameWrites)
        // A learning run (RuntimeSandbox.learning): its broker refuses nothing and records every would-be refusal.
        val learner = extras.getString(LEARN_CORE)?.let { core ->
            SandboxLearner.start(
                context!!, core, extras.getString(LEARN_BUILD).orEmpty(), extras.getString(LEARN_ARCHIVE).orEmpty(), game,
                RuntimeSandbox.learnRoots(context!!, game, save, bundles),
            )
        }
        val ok = RuntimeSandbox.serve(socket.detachFd(), rules, learner)
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
        const val ISOLATED = "isolated"
        const val GAME = "game"
        const val GAME_WRITABLE = "gameWritable"
        const val GAME_WRITES = "gameWrites"
        const val SAVE = "save"
        const val BUNDLES = "bundles"
        const val SERVED = "served"
        const val LEARN_CORE = "learnCore"
        const val LEARN_BUILD = "learnBuild"
        const val LEARN_ARCHIVE = "learnArchive"
    }
}
