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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
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
import java.io.IOException
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
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val svc = IEngineRuntimeService.Stub.asInterface(binder)
                service = svc
                Thread({
                    try {
                        svc.init(
                            installed.directory.absolutePath, installed.entrypointClass,
                            installed.dexFiles.toTypedArray(),
                            engine, engineContext, engineVersion, runtimeVersion, capabilityId,
                            execFile, optionsJson,
                            runtimeRequirements.keys.toTypedArray(), runtimeRequirements.values.toTypedArray(),
                            restartArguments,
                            HostFileBroker(gameFolder, readOnly = true),
                            HostFileBroker(saveFolder, readOnly = false),
                            HostRuntimeCallback(activity),
                        )
                        val width = svc.pixelWidth()
                        val height = svc.pixelHeight()
                        activity.runOnUiThread {
                            if (width <= 0 || height <= 0) {
                                onFailure("The isolated runtime reported no picture size")
                                return@runOnUiThread
                            }
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
                    } catch (e: Throwable) {
                        Log.e(TAG, "isolated runtime init failed", e)
                        activity.runOnUiThread { onFailure(e.message ?: e.javaClass.simpleName) }
                    }
                }, "enginehost-isolated-init").start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        connection = conn
        val intent = Intent(activity, IsolatedRuntimeService::class.java)
        if (!activity.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            connection = null
            onFailure("Could not start the isolated runtime")
        }
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
    }
}

/**
 * [IEngineFileBroker] bound to one root -- the game folder (read-only) or
 * the save folder (read-write) -- for exactly one launch. Path safety is
 * the same [resolveWithinRoot] every other in-process file access in this
 * app already uses.
 */
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
