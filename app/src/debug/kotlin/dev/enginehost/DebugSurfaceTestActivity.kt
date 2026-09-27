package dev.enginehost

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import dev.enginehost.debug.IDebugSurfaceTest

/**
 * docs/engine-sandbox.md "Surface handoff": drives DebugSurfaceTestService,
 * the feasibility exerciser for whether an isolated_app UID can composite
 * into a Surface handed to it over Binder. ADB/CI only (android.permission.DUMP,
 * same pattern as DebugBundleInstallActivity), absent from release builds.
 *
 * `adb shell am start -a dev.enginehost.debug.SURFACE_TEST -n dev.enginehost/.DebugSurfaceTestActivity`
 */
class DebugSurfaceTestActivity : Activity() {
    private var service: IDebugSurfaceTest? = null
    private var connection: ServiceConnection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surfaceView = SurfaceView(this)
        setContentView(surfaceView, ViewGroup.LayoutParams(-1, -1))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "host surfaceCreated; binding DebugSurfaceTestService")
                bindAndFill(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                running = false
            }
        })
    }

    private fun bindAndFill(holder: SurfaceHolder) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Log.i(TAG, "host: DebugSurfaceTestService connected")
                service = IDebugSurfaceTest.Stub.asInterface(binder)
                running = true
                handler.post(fillLoop)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TAG, "host: DebugSurfaceTestService disconnected")
                service = null
                running = false
            }
        }
        connection = conn
        val bound = bindService(Intent(this, DebugSurfaceTestService::class.java), conn, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "host: bindService returned $bound")
        this.holder = holder
    }

    private var holder: SurfaceHolder? = null
    private var calls = 0
    private var failures = 0

    private val fillLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val svc = service
            val s = holder?.surface
            if (svc != null && s != null && s.isValid) {
                calls++
                val error = runCatching { svc.fillSurface(s) }.getOrElse { it.message ?: it.javaClass.name }
                if (error != null) {
                    failures++
                    Log.e(TAG, "call #$calls FAILED: $error")
                } else if (calls == 1 || calls % 10 == 0) {
                    Log.i(TAG, "call #$calls ok ($failures failures so far)")
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        connection?.let { runCatching { unbindService(it) } }
        Log.i(TAG, "host: final tally: $calls calls, $failures failures")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EnginehostSandbox"
    }
}
