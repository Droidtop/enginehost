package dev.enginehost

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.util.Log
import android.view.Surface
import dev.enginehost.debug.IDebugSurfaceTest

/**
 * docs/engine-sandbox.md "Surface handoff": the feasibility exerciser
 * itself. android:isolatedProcess="true", a fresh UID with no permissions
 * of its own, exactly like IsolatedRuntimeService -- the only question this
 * class exists to answer is whether that UID can composite into a Surface
 * it received over Binder rather than created itself. Debug/CI only, never
 * bound by anything but DebugSurfaceTestActivity (adb/DUMP-gated, see its
 * own manifest entry) and absent from release builds entirely.
 */
class DebugSurfaceTestService : Service() {
    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IDebugSurfaceTest.Stub() {
        override fun fillSurface(surface: Surface): String? = runCatching {
            Log.i(TAG, "fillSurface: isolated uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}, surface.isValid=${surface.isValid}")
            val canvas = surface.lockCanvas(null)
            try {
                // Cycles so a screenshot taken any time after the first frame
                // still shows a fresh colour, not a stale one from a previous run.
                val colour = COLOURS[(System.currentTimeMillis() / 500 % COLOURS.size).toInt()]
                canvas.drawColor(colour)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            Log.i(TAG, "fillSurface: unlockCanvasAndPost succeeded")
            null
        }.getOrElse { error ->
            Log.e(TAG, "fillSurface failed", error)
            error.message ?: error.javaClass.name
        }
    }

    companion object {
        private const val TAG = "EnginehostSandbox"
        private val COLOURS = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)
    }
}
