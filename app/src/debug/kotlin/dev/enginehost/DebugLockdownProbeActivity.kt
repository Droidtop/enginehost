package dev.enginehost

import android.app.Activity
import android.app.ActivityManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Debug build only: the process lockdown's evidence run (docs/engine-sandbox.md
 * "Process lockdown"). In `:runtime`, it tries what an engine might (files
 * inside and outside the policy, DNS, TCP, exec, another process's /proc),
 * locks the process down, tries the same again, and only then starts GL, so
 * the driver's own late opens go through the broker. Every line is logged
 * under [TAG].
 *
 * adb shell am start -n dev.enginehost/.DebugLockdownProbeActivity --es game /sdcard/TestGames/<folder>
 */
class DebugLockdownProbeActivity : Activity() {
    private var frames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val game = File(intent.getStringExtra("game") ?: "/sdcard/TestGames")
        val save = File(cacheDir, "lockdown-probe-save").apply { mkdirs() }
        probes(game, save, "before")
        File(game, "probe-write.txt").delete()
        val locked = RuntimeSandbox.lockdown(this, game, false, save, File(filesDir, "engine-bundles-v1"))
        Log.i(TAG, "lockdown: $locked")
        probes(game, save, "after")
        val fromThread = Thread { probe("after", "game file from a new thread") { "${File(game, "main.lua").readBytes().size} bytes" } }
        fromThread.start()
        fromThread.join()
        val view = GLSurfaceView(this)
        view.setEGLContextClientVersion(2)
        view.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                Log.i(TAG, "GL up after lockdown: ${GLES20.glGetString(GLES20.GL_RENDERER)} / ${GLES20.glGetString(GLES20.GL_VERSION)}")
            }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = GLES20.glViewport(0, 0, width, height)
            override fun onDrawFrame(gl: GL10?) {
                frames++
                val phase = (frames % 120) / 120f
                GLES20.glClearColor(phase, 0.4f, 1f - phase, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                if (frames == 60 || frames == 600) {
                    val pixel = ByteBuffer.allocateDirect(4)
                    GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
                    val rgba = (0 until 4).joinToString(",") { (pixel.get(it).toInt() and 0xff).toString() }
                    Log.i(TAG, "GL frame $frames drawn after lockdown, pixel $rgba, glGetError ${GLES20.glGetError()}")
                }
            }
        })
        setContentView(view)
    }

    private fun probes(game: File, save: File, phase: String) {
        probe(phase, "game file") { "${File(game, "main.lua").readBytes().size} bytes" }
        probe(phase, "write in the game folder") { File(game, "probe-write.txt").writeText("x"); "WRITTEN" }
        probe(phase, "another game's folder") { "${File("/sdcard/TestGames/nscripter-smoke/0.txt").readBytes().size} bytes" }
        probe(phase, "Enginehost's private files") { "${File(filesDir, "plugins-index.json").readBytes().size} bytes" }
        probe(phase, "list shared storage") { "${File("/sdcard/Download").list()?.size ?: "null"} entries" }
        probe(phase, "save write and read") { File(save, "probe.txt").writeText(phase); File(save, "probe.txt").readText() }
        probe(phase, "cache write") { File(cacheDir, "probe-cache.txt").writeText(phase); "WRITTEN" }
        val mainPid = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
            ?.firstOrNull { it.processName == packageName }?.pid
        probe(phase, "the main process's /proc ($mainPid)") { File("/proc/$mainPid/cmdline").readText().trim('\u0000') }
        probe(phase, "own /proc/self/status") { File("/proc/self/status").readLines().first() }
        probe(phase, "DNS example.com") { InetAddress.getByName("example.com").hostAddress ?: "?" }
        probe(phase, "TCP 1.1.1.1:80") { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 80), 3000) }; "CONNECTED" }
        probe(phase, "exec /system/bin/id") { "exit ${Runtime.getRuntime().exec(arrayOf("/system/bin/id")).waitFor()}" }
    }

    private fun probe(phase: String, name: String, block: () -> String) {
        val result = runCatching(block).fold({ "OK $it" }, { "FAILED ${it.javaClass.simpleName}: ${it.message}" })
        Log.i(TAG, "$phase | $name | $result")
    }

    private companion object {
        const val TAG = "LockdownProbe"
    }
}
