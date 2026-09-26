package dev.enginehost

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.enginehost.runtime.IEngineFileBroker
import dev.enginehost.runtime.IEngineRuntimeCallback
import dev.enginehost.runtime.IEngineRuntimeService
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    private var stepping = false
    private var audioBridge: IsolatedAudioBridge? = null

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
        restartArguments: Array<String>,
        gameFolder: File,
        saveFolder: File,
        display: FrameLayout,
        onFailure: (String) -> Unit,
    ) {
        // A launch can no longer hang the screen silently forever: nothing
        // reachable from this process can tell whether the isolated side
        // is making progress or stuck (dq-sandbox-03 found it stuck inside
        // AAudio waiting on a system service an isolated process can never
        // see -- see IsolatedAudioBridge below for the actual fix to that;
        // this timeout is the backstop for the *next* thing that blocks
        // the same way, whatever it turns out to be).
        val settled = AtomicBoolean(false)
        val watchdogHandler = Handler(Looper.getMainLooper())
        val watchdog = Runnable {
            if (settled.compareAndSet(false, true)) {
                Log.e(TAG, "isolated runtime init did not finish within ${INIT_TIMEOUT_MS}ms; giving up on it")
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
                                restartArguments,
                                HostFileBroker(gameFolder, readOnly = true),
                                HostFileBroker(saveFolder, readOnly = false),
                                bridge?.pluginSideBuffer(), bridge?.sampleRate ?: 0,
                                HostRuntimeCallback(activity),
                            )
                        } finally {
                            // AIDL duplicates each descriptor across the binder call; this
                            // process's own copies are spent once init() returns (or throws).
                            (dexFds.asList() + nativeLibraryFds.asList()).forEach { runCatching { it.close() } }
                        }
                        val width = svc.pixelWidth()
                        val height = svc.pixelHeight()
                        settle {
                            activity.runOnUiThread {
                                if (width <= 0 || height <= 0) {
                                    bridge?.close()
                                    onFailure("The isolated runtime reported no picture size")
                                    return@runOnUiThread
                                }
                                audioBridge = bridge
                                bridge?.startPlayback()
                                pixels = IntArray(width * height)
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
                        settle { activity.runOnUiThread { onFailure(e.message ?: e.javaClass.simpleName) } }
                    }
                }
                Thread(initRunnable, "enginehost-isolated-init").start()
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
            val band = runCatching { svc.step(pixels) }.onFailure { Log.e(TAG, "step failed", it) }.getOrDefault(-1)
            if (band < 0) {
                // The engine ended on its own without calling EngineHost.finish/fail
                // (CatSystem2's own in-process ScreenView just stops looping the
                // same way); the launch screen beneath this one is what the
                // person sees next.
                stepping = false
                activity.runOnUiThread { activity.finish() }
                return
            }
            if (band > 0) {
                val snapshot = pixels.copyOf()
                activity.runOnUiThread { view?.applyFrame(snapshot, band) }
            }
            loopHandler?.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "enginehost-isolated-host"
        // A fixed pace, not vsync: driving the loop off this process's own
        // Choreographer would still only pace the *call*, and the isolated
        // process's own vsync access is exactly what this milestone avoids
        // depending on (docs/engine-sandbox.md "surfaces and input").
        private const val FRAME_INTERVAL_MS = 16L
        // Generous for a cold start on a slow device, bounded so a stuck
        // isolated launch (dq-sandbox-03: AudioFlinger lookup spinning
        // forever) shows a failure instead of a permanent black screen.
        private const val INIT_TIMEOUT_MS = 15_000L
    }
}

/**
 * [IEngineFileBroker] bound to one root -- the game folder (read-only) or
 * the save folder (read-write) -- for exactly one launch. Path safety is
 * the same [resolveWithinRoot] every other in-process file access in this
 * app already uses.
 */
/**
 * The isolated launch's audio, end to end (docs/engine-sandbox.md
 * "Audio"). An isolated process cannot reach AudioFlinger to open its
 * own output -- dq-sandbox-03 found CatSystem2 hanging there forever,
 * not merely failing, which is why [IsolatedRuntimeHost]'s own launch
 * now has a timeout regardless of this fix. The host opens the real
 * output instead and reads what the isolated side renders into a shared
 * ring buffer it never gets to see as a real audio device at all.
 *
 * Layout, matching the plugin-api contract on [EngineHost.isolatedAudioBuffer]:
 * a 16-byte header (write position, read position, capacity, reserved,
 * each a little-endian uint32) followed by [RING_CAPACITY] bytes of ring
 * data. The plugin only ever advances the write position; this class
 * only ever advances the read position. Both are byte offsets that only
 * increase, addressed into the ring by `% capacity`.
 */
private class IsolatedAudioBridge private constructor(
    private val ownedFd: FileDescriptor,
    private val hostBuffer: MappedByteBuffer,
    val sampleRate: Int,
) {
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile private var playing = false

    /** A descriptor the isolated service can pass on to its plugin; a fresh dup each call, closed by whoever receives it. */
    fun pluginSideBuffer(): ParcelFileDescriptor = ParcelFileDescriptor.dup(ownedFd)

    fun startPlayback() {
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBufferBytes, RING_CAPACITY),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.play()
        audioTrack = track
        playing = true
        val thread = Thread({
            val chunk = ByteArray(4096)
            while (playing) {
                val writePos = hostBuffer.getInt(OFFSET_WRITE_POS)
                val readPos = hostBuffer.getInt(OFFSET_READ_POS)
                val available = writePos - readPos
                if (available <= 0) {
                    Thread.sleep(5)
                    continue
                }
                val toRead = minOf(available, chunk.size)
                val start = readPos.toRingOffset()
                if (start + toRead <= RING_CAPACITY) {
                    hostBuffer.position(HEADER_SIZE + start)
                    hostBuffer.get(chunk, 0, toRead)
                } else {
                    val first = RING_CAPACITY - start
                    hostBuffer.position(HEADER_SIZE + start)
                    hostBuffer.get(chunk, 0, first)
                    hostBuffer.position(HEADER_SIZE)
                    hostBuffer.get(chunk, first, toRead - first)
                }
                track.write(chunk, 0, toRead)
                hostBuffer.putInt(OFFSET_READ_POS, readPos + toRead)
            }
        }, "enginehost-isolated-audio")
        playbackThread = thread
        thread.start()
    }

    private fun Int.toRingOffset(): Int {
        val offset = this % RING_CAPACITY
        return if (offset < 0) offset + RING_CAPACITY else offset
    }

    fun close() {
        playing = false
        playbackThread?.let { runCatching { it.join(500) } }
        playbackThread = null
        audioTrack?.let { runCatching { it.stop() }; runCatching { it.release() } }
        audioTrack = null
        runCatching { Os.close(ownedFd) }
    }

    companion object {
        private const val HEADER_SIZE = 16
        private const val OFFSET_WRITE_POS = 0
        private const val OFFSET_READ_POS = 4
        private const val OFFSET_CAPACITY = 8
        private const val RING_CAPACITY = 32 * 1024
        private const val TOTAL_SIZE = HEADER_SIZE + RING_CAPACITY
        private const val TAG = "enginehost-isolated-audio"

        /**
         * Null when this device cannot give the host a usable output
         * rate, is older than API 30 ([Os.memfd_create] has no Java
         * binding below it -- same gate [ownedCopy] in
         * IsolatedRuntimeService.kt uses for the same reason), or the
         * shared region itself cannot be made -- a game still plays,
         * silently, exactly as when no audio device is available today.
         *
         * The mapping is made through a throwaway dup of the memfd
         * (closed right after [FileChannel.map], which is safe: mmap
         * stays valid once made, independent of the descriptor used to
         * request it), never through [ownedFd] itself, which stays open
         * and unmapped-from for [pluginSideBuffer] and [close] alone.
         */
        fun create(): IsolatedAudioBridge? {
            if (Build.VERSION.SDK_INT < 30) return null
            return try {
                val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                    .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                val fd = Os.memfd_create("enginehost-isolated-audio", 0)
                Os.ftruncate(fd, TOTAL_SIZE.toLong())
                val mapDup = ParcelFileDescriptor.dup(fd)
                val buffer = try {
                    FileInputStream(mapDup.fileDescriptor).channel
                        .map(FileChannel.MapMode.READ_WRITE, 0, TOTAL_SIZE.toLong())
                } finally {
                    mapDup.close()
                }
                buffer.order(ByteOrder.nativeOrder())
                buffer.putInt(OFFSET_WRITE_POS, 0)
                buffer.putInt(OFFSET_READ_POS, 0)
                buffer.putInt(OFFSET_CAPACITY, RING_CAPACITY)
                IsolatedAudioBridge(fd, buffer, rate)
            } catch (e: Exception) {
                Log.w(TAG, "could not set up isolated audio; the game will play silently", e)
                null
            }
        }

        private const val DEFAULT_SAMPLE_RATE = 48_000
    }
}

private class HostFileBroker(private val root: File, private val readOnly: Boolean) : IEngineFileBroker.Stub() {
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

    /**
     * A fresh file beside [target]'s eventual name: nothing appears under
     * that name until [commitWrite] renames it there, matching
     * EngineFileBroker's own contract (crash or no commit, no visible
     * partial write). Not a per-write random name: at most one write per
     * relativePath is ever open at a time, by contract.
     */
    private fun pendingWritePath(target: File): File = File(target.parentFile, "${target.name}.isolated-write")

    companion object {
        private const val WRITE_MODE =
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
    }
}

/** [IEngineRuntimeCallback]: EngineHost calls that only this ("runtime") process's Activity, vibrator etc. can carry out. */
private class HostRuntimeCallback(private val activity: RuntimeActivity) : IEngineRuntimeCallback.Stub() {
    override fun log(priority: Int, tag: String, message: String) {
        Log.println(priority, "enginehost/$tag", message)
    }

    override fun rumbleController(deviceId: Int, durationMs: Long, amplitude: Int): Boolean {
        val vibrator = InputDevice.getDevice(deviceId)?.vibrator ?: return false
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(1, 10_000), amplitude.coerceIn(1, 255)))
        return true
    }

    override fun finish() {
        activity.runOnUiThread { activity.finish() }
    }

    override fun restart(arguments: Array<String>) {
        activity.runOnUiThread {
            activity.setResult(
                Activity.RESULT_FIRST_USER,
                Intent()
                    .putExtra(RuntimeActivity.EXTRA_RESTART, true)
                    .putExtra(RuntimeActivity.EXTRA_RESTART_ARGUMENTS, arguments),
            )
            activity.finish()
        }
    }

    override fun fail(message: String) {
        activity.runOnUiThread { activity.failAndFinish(RuntimeActivity.STARTUP_FAILED + message) }
    }
}

/**
 * A plain software-rendered View for an [dev.enginehost.api.EngineStepDriven]
 * plugin's isolated runtime: the same letterboxed-bitmap draw and
 * pointer-to-engine-space scaling CatSystem2's own in-process ScreenView
 * does, written once here because it is generic to any pixel-buffer engine
 * this milestone's shape covers (docs/engine-sandbox.md roadmap), not
 * specific to CatSystem2.
 */
internal class IsolatedFrameView(
    context: Context,
    private val engineWidth: Int,
    private val engineHeight: Int,
    private val onPointerMove: (Int, Int) -> Unit,
    private val onPointerUp: (Int, Int) -> Unit,
) : View(context) {
    private val frame = Bitmap.createBitmap(engineWidth, engineHeight, Bitmap.Config.ARGB_8888)
    private val source = Rect(0, 0, engineWidth, engineHeight)
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
    }

    /** [pixels] is engineWidth * engineHeight ARGB_8888 ints; [band] is EngineStepDriven.step()'s return, already known > 0. */
    fun applyFrame(pixels: IntArray, band: Int) {
        val top = band ushr 16
        val rows = band and 0xffff
        frame.setPixels(pixels, top * engineWidth, engineWidth, 0, top, engineWidth, rows)
        invalidate()
    }

    private fun scale(): Float = minOf(width / engineWidth.toFloat(), height / engineHeight.toFloat())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = scale()
        if (scale <= 0f) return
        val left = (width - engineWidth * scale) / 2
        val top = (height - engineHeight * scale) / 2
        destination.set(left, top, left + engineWidth * scale, top + engineHeight * scale)
        canvas.drawBitmap(frame, source, destination, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scale = scale()
        if (scale <= 0f) return true
        val left = (width - engineWidth * scale) / 2
        val top = (height - engineHeight * scale) / 2
        val x = ((event.x - left) / scale).roundToInt()
        val y = ((event.y - top) / scale).roundToInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> onPointerMove(x, y)
            MotionEvent.ACTION_UP -> {
                onPointerMove(x, y)
                onPointerUp(x, y)
            }
        }
        return true
    }
}
