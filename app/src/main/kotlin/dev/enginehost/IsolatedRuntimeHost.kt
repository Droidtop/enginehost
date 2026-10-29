package dev.enginehost

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.system.Os
import android.util.Log
import android.view.InputDevice
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.enginehost.runtime.IEngineFileBroker
import dev.enginehost.runtime.IEngineRuntimeCallback
import dev.enginehost.runtime.IEngineRuntimeService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-side half of sandbox layer 2's first milestone (docs/engine-sandbox.md):
 * binds [IsolatedRuntimeService], brokers the game and save folders over
 * [IEngineFileBroker], drives the frame loop from this ":runtime" process
 * instead of depending on the isolated process reaching vsync itself, and
 * draws what comes back into a plain [IsolatedFrameView]. One instance per
 * launch, owned by [RuntimeActivity].
 */
internal class IsolatedRuntimeHost(private val activity: RuntimeActivity) {
    private var connection: ServiceConnection? = null
    private var service: IEngineRuntimeService? = null
    private var loopThread: HandlerThread? = null
    private var loopHandler: Handler? = null
    private var view: IsolatedFrameView? = null
    private var pixels: IntArray = IntArray(0)
    private var frameWidth: Int = 0
    /**
     * The host's own copy of the shared frame buffer handed to
     * [IEngineRuntimeService.setFrameBuffer] (docs/engine-sandbox.md
     * "Audio" precedent, dq-sandbox-05 "the frame-transfer path"): a
     * plain host-owned file, not memfd, specifically so this works down
     * to this app's real minSdk (26) rather than only API 30+ -- BlueStacks,
     * where the crash this replaces a mechanism for was actually found,
     * is API 28.
     */
    private var frameBufferPfd: ParcelFileDescriptor? = null
    private var frameBufferFile: File? = null
    private var frameBytes: ByteArray = ByteArray(0)
    private var stepping = false

    /** A frame has reached the screen: an engine's [EngineHost.fail] from here on is the game stopping, not a failed start. */
    @Volatile private var showedGame = false
    private var audioBridge: IsolatedAudioBridge? = null
    /**
     * Set once by [start]; also used from [frameStep] when the isolated
     * peer dies mid-run (dq-sandbox-04, BlueStacks): that case used to
     * fall into the exact same silent activity.finish() a real
     * EngineStepDriven end-of-game uses, and RuntimeActivity.onDestroy()
     * deliberately kills this ":runtime" process on an ordinary finish
     * -- so a crash and a clean exit must not reach finish() by the same
     * unlabelled path, or the crash is indistinguishable from the game
     * simply having ended, and the person is bounced out with no reason
     * shown at all.
     */
    private var onLaunchFailure: ((String) -> Unit)? = null

    fun start(
        installed: InstalledPlugin,
        engine: String,
        engineContext: String,
        engineVersion: String,
        runtimeVersion: String,
        capabilityId: String,
        execFile: String?,
        optionsJson: String?,
        runtimeRequirements: Map<String, String>,
        runtimeComponents: Map<String, String>,
        restartArguments: Array<String>,
        gameFolder: File,
        saveFolder: File,
        display: FrameLayout,
        /** The confined side of the lockdown broker ([RuntimeSandbox.brokerForIsolated]); null when the core is not clamped. */
        sandboxBroker: ParcelFileDescriptor?,
        sandboxLearn: Boolean,
        onFailure: (String) -> Unit,
    ) {
        onLaunchFailure = onFailure
        // A launch can no longer hang the screen silently forever: nothing
        // reachable from this process can tell whether the isolated side
        // is making progress or stuck (dq-sandbox-03 found it stuck inside
        // AAudio waiting on a system service an isolated process can never
        // see -- see IsolatedAudioBridge (IsolatedAudioBridge.kt) for the actual fix to that;
        // this timeout is the backstop for the *next* thing that blocks
        // the same way, whatever it turns out to be).
        val settled = AtomicBoolean(false)
        val watchdogHandler = Handler(Looper.getMainLooper())
        val watchdog = Runnable {
            if (settled.compareAndSet(false, true)) {
                Log.e(TAG, "isolated runtime did not start or show a picture in time; giving up on it")
                connection?.let { runCatching { activity.unbindService(it) } }
                connection = null
                onFailure("The isolated runtime did not start in time")
            }
        }
        watchdogHandler.postDelayed(watchdog, INIT_TIMEOUT_MS)
        fun settle(block: () -> Unit) {
            if (settled.compareAndSet(false, true)) {
                watchdogHandler.removeCallbacks(watchdog)
                block()
            } // else: the watchdog already ended this launch; nothing further to do with a late result.
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val svc = IEngineRuntimeService.Stub.asInterface(binder)
                service = svc
                val initRunnable = Runnable {
                    try {
                        val dexFds = openDexFds(installed)
                        val (nativeLibraryNames, nativeLibraryFds) = openNativeLibraryFds(installed)
                        // The isolated process cannot reach AudioFlinger to open its own
                        // output at all (dq-sandbox-03: it hangs there forever, not merely
                        // fails). The host owns real output instead; this is what it hands
                        // over for the isolated side to fill (docs/engine-sandbox.md "Audio").
                        val bridge = IsolatedAudioBridge.create()
                        try {
                            svc.init(
                                dexFds, installed.entrypointClass,
                                nativeLibraryNames, nativeLibraryFds,
                                engine, engineContext, engineVersion, runtimeVersion, capabilityId,
                                execFile, optionsJson,
                                runtimeRequirements.keys.toTypedArray(), runtimeRequirements.values.toTypedArray(),
                                runtimeComponents.keys.toTypedArray(), runtimeComponents.values.toTypedArray(),
                                restartArguments,
                                HostFileBroker(gameFolder, readOnly = !installed.writesGameFolder),
                                HostFileBroker(saveFolder, readOnly = false),
                                gameFolder.absolutePath, installed.writesGameFolder, saveFolder.absolutePath,
                                bridge?.pluginSideBuffer(), bridge?.sampleRate ?: 0,
                                sandboxBroker, sandboxLearn,
                                HostRuntimeCallback(activity) { showedGame },
                            )
                        } finally {
                            // AIDL duplicates each descriptor across the binder call; this
                            // process's own copies are spent once init() returns (or throws).
                            (dexFds.asList() + nativeLibraryFds.asList() + listOfNotNull(sandboxBroker))
                                .forEach { runCatching { it.close() } }
                        }
                        // The runtime is up. An engine that boots into its
                        // own scripts may take a while to compose its first
                        // picture, whose size pixelWidth()/pixelHeight()
                        // answer (OpenBGI runs its boot scripts for seconds
                        // first), so that wait gets its own, longer bound.
                        if (!settled.get()) {
                            watchdogHandler.removeCallbacks(watchdog)
                            watchdogHandler.postDelayed(watchdog, PICTURE_TIMEOUT_MS)
                        }
                        val width = svc.pixelWidth()
                        val height = svc.pixelHeight()
                        // dq-sandbox-05 (BlueStacks): step() used to carry the
                        // whole frame as an `out int[] pixels` Binder array --
                        // CatSystem2's default 1024x576 is ~2.25MB, sent 60
                        // times a second -- consistent with the isolated
                        // process's own Binder transaction buffer being
                        // exhausted, which is the leading theory for why it
                        // died silently and unexplained about 11s into every
                        // run. Frame data now crosses through a plain
                        // host-owned file instead (see setFrameBuffer's AIDL
                        // doc comment), created only once width/height are
                        // known, exactly like the audio ring is sized only
                        // once a real sample rate is known.
                        val frameFile = if (width > 0 && height > 0) {
                            runCatching {
                                File.createTempFile("enginehost-frame", ".buf", activity.cacheDir).also {
                                    it.deleteOnExit()
                                }
                            }.getOrNull()
                        } else {
                            null
                        }
                        val framePfd = frameFile?.let {
                            runCatching { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_WRITE) }.getOrNull()
                        }
                        framePfd?.let { runCatching { svc.setFrameBuffer(it) } }
                        settle {
                            Async.onMain(activity) {
                                if (width <= 0 || height <= 0 || framePfd == null) {
                                    bridge?.close()
                                    framePfd?.let { runCatching { it.close() } }
                                    frameFile?.let { runCatching { it.delete() } }
                                    onFailure(
                                        if (width <= 0 || height <= 0) "The isolated runtime reported no picture size"
                                        else "Could not set up the isolated runtime's frame buffer",
                                    )
                                    return@onMain
                                }
                                audioBridge = bridge
                                bridge?.startPlayback()
                                frameBufferPfd = framePfd
                                frameBufferFile = frameFile
                                pixels = IntArray(width * height)
                                frameWidth = width
                                val frameView = IsolatedFrameView(
                                    activity, width, height,
                                    onPointerMove = { x, y -> onLoop { runCatching { service?.onPointerMove(x, y) } } },
                                    onPointerUp = { x, y -> onLoop { runCatching { service?.onPointerUp(x, y) } } },
                                )
                                view = frameView
                                display.addView(frameView, ViewGroup.LayoutParams(-1, -1))
                                beginLoop()
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "isolated runtime init failed", e)
                        settle { Async.onMain(activity) { onFailure(e.message ?: e.javaClass.simpleName) } }
                    }
                }
                Async.run(initRunnable::run)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        connection = conn
        val intent = Intent(activity, IsolatedRuntimeService::class.java)
        if (!activity.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            connection = null
            settle { onFailure("Could not start the isolated runtime") }
        }
    }

    /**
     * Opens this process's own read-only descriptor for each of the
     * bundle's dex files, against the already hash-verified installed
     * directory (InstalledBundleVerifier ran before this launch reached
     * here) -- the isolated process gets these fds instead of the
     * directory path itself, which it may not be able to reach by name
     * at all on Android 10+ (docs/engine-sandbox.md "The bundle's own
     * files"). Same path-safety check every other bundle-file read here
     * already uses.
     */
    private fun openDexFds(installed: InstalledPlugin): Array<ParcelFileDescriptor> {
        val root = installed.directory.canonicalFile
        return installed.dexFiles.map { dexFile ->
            ParcelFileDescriptor.open(safeRuntimeChild(root, dexFile), ParcelFileDescriptor.MODE_READ_ONLY)
        }.toTypedArray()
    }

    /**
     * The same, for the one ABI directory this device actually uses --
     * every ".so" in it, named as System.loadLibrary(name) would ask for
     * it (PluginDexLoader.findLibrary is the other end of this).
     */
    private fun openNativeLibraryFds(installed: InstalledPlugin): Pair<Array<String>, Array<ParcelFileDescriptor>> {
        val root = installed.directory.canonicalFile
        val abiDir = Build.SUPPORTED_ABIS.asSequence()
            .map { File(root, "lib/$it") }
            .firstOrNull(File::isDirectory)
            ?: return emptyArray<String>() to emptyArray()
        val libraries = abiDir.listFiles { file -> file.isFile && file.name.startsWith("lib") && file.name.endsWith(".so") }
            .orEmpty()
        val names = libraries.map { it.name.removePrefix("lib").removeSuffix(".so") }.toTypedArray()
        val fds = libraries.map { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }.toTypedArray()
        return names to fds
    }

    /** Forwards to [RuntimeActivity]'s controller router: the pad, not the pointer, which the view already carries. */
    fun onControllerEvent(action: String, value: Float, deviceId: Int, deviceDescriptor: String, eventTime: Long): Boolean {
        val svc = service ?: return false
        return runCatching { svc.onControllerEvent(action, value, deviceId, deviceDescriptor, eventTime) }.getOrDefault(false)
    }

    fun pause() {
        stepping = false
        onLoop { runCatching { service?.pauseRuntime() } }
    }

    fun resume() {
        onLoop { runCatching { service?.resumeRuntime() } }
        if (loopThread != null && !stepping) {
            stepping = true
            loopHandler?.post(frameStep)
        }
    }

    fun destroy() {
        stepping = false
        onLoop { runCatching { service?.destroyRuntime() } }
        loopThread?.quitSafely()
        loopThread = null
        loopHandler = null
        connection?.let { runCatching { activity.unbindService(it) } }
        connection = null
        service = null
        audioBridge?.close()
        audioBridge = null
        frameBufferPfd?.let { runCatching { it.close() } }
        frameBufferPfd = null
        frameBufferFile?.let { runCatching { it.delete() } }
        frameBufferFile = null
    }

    private fun onLoop(block: () -> Unit) {
        loopHandler?.post(block) ?: block()
    }

    private fun beginLoop() {
        val thread = HandlerThread("enginehost-isolated-frame").apply { start() }
        loopThread = thread
        loopHandler = Handler(thread.looper)
        stepping = true
        loopHandler?.post(frameStep)
    }

    private val frameStep = object : Runnable {
        override fun run() {
            if (!stepping) return
            val svc = service
            if (svc == null) {
                stepping = false
                return
            }
            val result = runCatching { svc.step() }
            val error = result.exceptionOrNull()
            if (error != null) {
                // dq-sandbox-04 (BlueStacks): the isolated peer died mid-run
                // (silently, no tombstone) and this step() call is how the
                // host finds out, via DeadObjectException. This is NOT the
                // same as EngineStepDriven's own step() returning a plain
                // -1 below, which is the engine's normal "I have ended"
                // signal -- routing both to the same silent
                // activity.finish() meant a crash and a clean exit looked
                // identical, and since RuntimeActivity.onDestroy()
                // deliberately kills this ":runtime" process on an
                // ordinary finish, the whole app appeared to silently die
                // with no explanation at all. A crash goes through
                // onLaunchFailure instead, the same on-screen failure path
                // a launch-time error already uses.
                stepping = false
                logIsolatedDeath(error)
                Async.onMain(activity) {
                    val onFailure = onLaunchFailure
                    if (onFailure != null) {
                        onFailure("the isolated runtime stopped: " + (error.message ?: error.javaClass.simpleName))
                    } else {
                        activity.finish()
                    }
                }
                return
            }
            val band = result.getOrDefault(-1)
            if (band < 0) {
                // The engine ended on its own without calling EngineHost.finish/fail
                // (CatSystem2's own in-process ScreenView just stops looping the
                // same way); the launch screen beneath this one is what the
                // person sees next.
                stepping = false
                Async.onMain(activity) { activity.finish() }
                return
            }
            if (band > 0) {
                readChangedRows(band)
                showedGame = true
                val snapshot = pixels.copyOf()
                Async.onMain(activity) { view?.applyFrame(snapshot, band) }
            }
            loopHandler?.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    /**
     * Reads the rows [band] says changed out of [frameBufferPfd] into
     * [pixels], at the same byte offset the isolated side wrote them to
     * (IsolatedRuntimeService.writeChangedRows) -- never the whole
     * frame, matching the row-diff [band] already encodes.
     */
    private fun readChangedRows(band: Int) {
        val fd = frameBufferPfd ?: return
        val width = frameWidth
        if (width <= 0) return
        val top = band ushr 16
        val rows = band and 0xffff
        val byteLength = rows * width * 4
        if (frameBytes.size < byteLength) frameBytes = ByteArray(byteLength)
        val byteOffset = top.toLong() * width * 4
        var done = 0
        while (done < byteLength) {
            val n = Os.pread(fd.fileDescriptor, frameBytes, done, byteLength - done, byteOffset + done)
            if (n <= 0) break
            done += n
        }
        ByteBuffer.wrap(frameBytes, 0, byteLength).order(ByteOrder.nativeOrder())
            .asIntBuffer().get(pixels, top * width, rows * width)
    }

    /**
     * Whatever this process can find out about why the isolated peer is
     * gone, since [error] itself (a DeadObjectException off a failed
     * Binder call) never carries more than "remote process probably
     * died" -- dq-sandbox-04 found no tombstone and nothing else in
     * logcat marking the isolated process's own end. ApplicationExitInfo
     * (API 30+) is the one place Android records why a process it hosted
     * actually stopped (crash, native crash, ANR, signal, low memory,
     * ...); below API 30, or if nothing matches, this can only log what
     * [error] itself says.
     */
    private fun logIsolatedDeath(error: Throwable) {
        Log.e(TAG, "step failed; the isolated runtime is presumed dead", error)
        if (Build.VERSION.SDK_INT < 30) {
            Log.w(TAG, "no exit-reason record available below API 30 (this device is ${Build.VERSION.SDK_INT})")
            return
        }
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(activity.packageName, 0, 8)
            val isolated = reasons.firstOrNull { it.processName.contains(ISOLATED_PROCESS_NAME) }
            if (isolated == null) {
                Log.w(TAG, "no matching exit-reason record found for $ISOLATED_PROCESS_NAME")
                return
            }
            Log.e(
                TAG,
                "isolated runtime (${isolated.processName}, pid ${isolated.pid}) exit reason code " +
                    "${isolated.reason} (see ApplicationExitInfo.REASON_*), status ${isolated.status}, " +
                    "description: ${isolated.description}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not read the isolated process's exit reason", e)
        }
    }

    companion object {
        private const val TAG = "enginehost-isolated-host"
        // Matches AndroidManifest.xml's android:process=":runtime_isolated"
        // on IsolatedRuntimeService; ActivityManager reports the full name
        // ("dev.enginehost:runtime_isolated"), so this only needs to be a
        // substring of it.
        private const val ISOLATED_PROCESS_NAME = ":runtime_isolated"
        // A fixed pace, not vsync: driving the loop off this process's own
        // Choreographer would still only pace the *call*, and the isolated
        // process's own vsync access is exactly what this milestone avoids
        // depending on (docs/engine-sandbox.md "surfaces and input").
        private const val FRAME_INTERVAL_MS = 16L
        // Generous for a cold start on a slow device, bounded so a stuck
        // isolated launch (dq-sandbox-03: AudioFlinger lookup spinning
        // forever) shows a failure instead of a permanent black screen.
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val PICTURE_TIMEOUT_MS = 60_000L
    }
}

/** [IEngineRuntimeCallback]: EngineHost calls that only this ("runtime") process's Activity, vibrator etc. can carry out. */
private class HostRuntimeCallback(
    private val activity: RuntimeActivity,
    private val showedGame: () -> Boolean,
) : IEngineRuntimeCallback.Stub() {
    override fun log(priority: Int, tag: String, message: String) {
        Log.println(priority, "enginehost/$tag", message)
        HostEvents.recordEngine(activity, priority, tag, message)
    }

    override fun rumbleController(deviceId: Int, durationMs: Long, amplitude: Int): Boolean {
        val vibrator = InputDevice.getDevice(deviceId)?.vibrator ?: return false
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(1, 10_000), amplitude.coerceIn(1, 255)))
        return true
    }

    override fun finish() {
        Async.onMain(activity) { activity.finish() }
    }

    override fun restart(arguments: Array<String>) {
        Async.onMain(activity) {
            activity.setResult(
                Activity.RESULT_FIRST_USER,
                Intent()
                    .putExtra(RuntimeActivity.EXTRA_RESTART, true)
                    .putExtra(RuntimeActivity.EXTRA_RESTART_ARGUMENTS, arguments),
            )
            activity.finish()
        }
    }

    override fun openLink(url: String) {
        GameLinkOffer.offer(activity, url)
    }

    override fun emitText(event: String) = GameText.emit(activity, event)

    override fun replaceText(event: String): String? = GameText.replace(event)

    override fun fail(message: String) {
        val prefix = if (showedGame()) RuntimeActivity.GAME_STOPPED else RuntimeActivity.STARTUP_FAILED
        Async.onMain(activity) { activity.failAndFinish(prefix + message) }
    }
}
