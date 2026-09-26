package dev.enginehost

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.enginehost.api.EngineControllerEvent
import dev.enginehost.api.EngineFileBroker
import dev.enginehost.api.EngineFileSystem
import dev.enginehost.api.EngineHost
import dev.enginehost.api.EnginePlugin
import dev.enginehost.api.EnginePluginSession
import dev.enginehost.api.EngineStepDriven
import dev.enginehost.runtime.IEngineFileBroker
import dev.enginehost.runtime.IEngineRuntimeCallback
import dev.enginehost.runtime.IEngineRuntimeService
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Sandbox layer 2, first milestone (docs/engine-sandbox.md): the whole
 * plugin lifecycle for one isolated launch. Runs in ":runtime_isolated", a
 * fresh android:isolatedProcess UID with no permission of its own -- bound
 * by IsolatedRuntimeHost from the ordinary ":runtime" process, never
 * started or bound by anything else (android:exported="false").
 *
 * One instance is one launch: [init] starts exactly one plugin session and
 * [destroyRuntime] ends the process along with it (the service is never
 * reused for a second game).
 */
class IsolatedRuntimeService : Service() {
    private var plugin: EnginePlugin? = null
    private var stepDriven: EngineStepDriven? = null
    private var callback: IEngineRuntimeCallback? = null
    private var restartArguments: Array<String> = emptyArray()
    private val resourceHandles = mutableListOf<AutoCloseable>()
    /** The dex and native-library descriptors init() was handed, kept open for the session's life. */
    private val heldFds = mutableListOf<ParcelFileDescriptor>()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { plugin?.onDestroy() }
        resourceHandles.asReversed().forEach { runCatching { it.close() } }
        resourceHandles.clear()
        heldFds.forEach { runCatching { it.close() } }
        heldFds.clear()
        plugin = null
        stepDriven = null
        super.onDestroy()
    }

    private val binder = object : IEngineRuntimeService.Stub() {
        override fun init(
            dexFds: Array<ParcelFileDescriptor>,
            entrypointClass: String,
            nativeLibraryNames: Array<String>,
            nativeLibraryFds: Array<ParcelFileDescriptor>,
            engine: String,
            engineContext: String,
            engineVersion: String,
            runtimeVersion: String,
            capabilityId: String,
            execFile: String?,
            optionsJson: String?,
            runtimeRequirementKeys: Array<String>,
            runtimeRequirementValues: Array<String>,
            restartArguments: Array<String>,
            gameBroker: IEngineFileBroker?,
            saveBroker: IEngineFileBroker?,
            audioBuffer: ParcelFileDescriptor?,
            audioSampleRate: Int,
            callback: IEngineRuntimeCallback,
        ) {
            this@IsolatedRuntimeService.callback = callback
            this@IsolatedRuntimeService.restartArguments = restartArguments
            heldFds += dexFds
            heldFds += nativeLibraryFds
            audioBuffer?.let { heldFds += it }
            val runtimeRequirements = runtimeRequirementKeys.indices.associate {
                runtimeRequirementKeys[it] to runtimeRequirementValues[it]
            }
            val host = IsolatedEngineHost(
                this@IsolatedRuntimeService, callback, this@IsolatedRuntimeService.restartArguments,
                gameBroker?.let(::AidlFileBrokerAdapter), saveBroker?.let(::AidlFileBrokerAdapter),
                audioBuffer, audioSampleRate,
            )
            // Prefer a descriptor this process owns outright over the
            // received one: opening /proc/self/fd/<received fd> BY PATH is
            // a fresh open() of the ORIGINAL file, which SELinux re-checks
            // against that file's own security context (app_data_file) --
            // the same check that may be refusing this launch in the first
            // place, if that turns out to be what dq-sandbox-03 shows
            // (docs/engine-sandbox.md "The bundle's own files"). A copy
            // into a memfd this process created itself carries no such
            // label; reopening ITS /proc/self/fd entry checks this
            // process's access to its own memory-backed file instead.
            val dexSources = dexFds.map { received -> ownedCopy(received)?.also { heldFds += it } ?: received }
            val nativeLibrarySources = nativeLibraryFds.map { received ->
                ownedCopy(received)?.also { heldFds += it } ?: received
            }
            val nativeLibraryFdPaths = nativeLibraryNames.indices.associate {
                nativeLibraryNames[it] to procFdPath(nativeLibrarySources[it])
            }
            val loaded = loadEnginePluginFromFds(
                this@IsolatedRuntimeService, dexSources.map(::procFdPath), entrypointClass,
                nativeLibraryFdPaths, classLoader,
            )
            resourceHandles += loaded.resourceHandles
            val session = EnginePluginSession(
                // No real bundle directory in an isolated process: dex and
                // native libraries arrive as descriptors (above), not a
                // path this session could read further into. A plugin
                // that needs to read its OWN other bundled assets at
                // runtime -- none does yet -- is out of this milestone's
                // scope, same as resourceApks below.
                NO_BUNDLE_DIRECTORY, /* display = */ null, host, /* gamePath = */ "", engine,
                engineContext, engineVersion, runtimeVersion, capabilityId, execFile, optionsJson,
                runtimeRequirements,
            )
            loaded.plugin.onCreate(session)
            val driven = loaded.plugin as? EngineStepDriven
                ?: error("${loaded.plugin.javaClass.name} does not implement EngineStepDriven; not eligible to run isolated")
            plugin = loaded.plugin
            stepDriven = driven
        }

        override fun pixelWidth(): Int = stepDriven?.pixelWidth() ?: 0
        override fun pixelHeight(): Int = stepDriven?.pixelHeight() ?: 0

        override fun step(pixels: IntArray): Int = runCatching { stepDriven?.step(pixels) ?: -1 }
            .onFailure { Log.e(TAG, "step failed", it) }
            .getOrDefault(-1)

        override fun onControllerEvent(
            action: String,
            value: Float,
            deviceId: Int,
            deviceDescriptor: String,
            eventTime: Long,
        ): Boolean = runCatching {
            plugin?.onControllerEvent(EngineControllerEvent(action, value, deviceId, deviceDescriptor, eventTime))
        }.getOrDefault(false) == true

        override fun onPointerMove(x: Int, y: Int) {
            runCatching { stepDriven?.onPointerMove(x, y) }
        }

        override fun onPointerUp(x: Int, y: Int) {
            runCatching { stepDriven?.onPointerUp(x, y) }
        }

        override fun pauseRuntime() {
            runCatching { plugin?.onPause() }
        }

        override fun resumeRuntime() {
            runCatching { plugin?.onResume() }
        }

        override fun destroyRuntime() {
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "enginehost-isolated-runtime"
        /**
         * Not a real bundle directory: this process never has one (see
         * init()). Its only use is EnginePluginSession's own non-null
         * requirement; nothing reads it.
         */
        private val NO_BUNDLE_DIRECTORY = File("/proc/self/fd")
    }
}

/** The path a ParcelFileDescriptor is reachable at from this process's own side of it: the same open file, by fd number. */
private fun procFdPath(pfd: ParcelFileDescriptor): String = "/proc/self/fd/${pfd.fd}"

/**
 * A copy of pfd's bytes into an anonymous, memory-backed file this
 * process created itself (memfd_create(2)), or null when that is not
 * available (below API 30, where android.system.Os has no Java binding
 * for it yet) or fails for any reason -- the caller falls back to the
 * received descriptor unchanged either way, exactly today's behaviour.
 * Reads pfd directly (no reopen: this is the one operation Binder's own
 * fd transfer already cleared), so this needs no permission this process
 * does not already have from receiving pfd in the first place.
 */
private fun ownedCopy(pfd: ParcelFileDescriptor): ParcelFileDescriptor? {
    if (Build.VERSION.SDK_INT < 30) return null
    return try {
        val memFd = Os.memfd_create("enginehost-bundle", 0)
        try {
            FileOutputStream(memFd).use { output -> FileInputStream(pfd.fileDescriptor).copyTo(output) }
            Os.lseek(memFd, 0, OsConstants.SEEK_SET)
            ParcelFileDescriptor.dup(memFd)
        } finally {
            Os.close(memFd)
        }
    } catch (e: Exception) {
        Log.w("enginehost-isolated-runtime", "could not copy into an owned memfd; using the received descriptor as-is", e)
        null
    }
}

/** [EngineHost] for an isolated launch: file access is broker-only, and every Activity-owned call crosses back to the host. */
private class IsolatedEngineHost(
    private val ctx: android.content.Context,
    private val callback: IEngineRuntimeCallback,
    private val restartArgs: Array<String>,
    private val gameBrokerImpl: EngineFileBroker?,
    private val saveBrokerImpl: EngineFileBroker?,
    private val audioBufferImpl: ParcelFileDescriptor?,
    private val audioSampleRateImpl: Int,
) : EngineHost {
    override fun context(): android.content.Context = ctx

    // Neither is meaningful in isolated mode: the isolated UID has no
    // shared storage of its own to resolve saveDirectory() into, and
    // fileSystem() is the in-process stream API that gameBroker()/
    // saveBroker() exist to replace (docs/engine-sandbox.md). A plugin
    // that implements EngineStepDriven is expected to check
    // gameBroker()/saveBroker() instead, as CatSystem2Plugin does.
    override fun saveDirectory(): File = throw UnsupportedOperationException(
        "isolated runtime: use EngineHost.saveBroker(), not saveDirectory()",
    )
    override fun fileSystem(): EngineFileSystem = throw UnsupportedOperationException(
        "isolated runtime: use EngineHost.gameBroker()/saveBroker(), not fileSystem()",
    )

    /** This process's own private cache -- its own UID's storage, not shared storage; no broker needed. */
    override fun cacheDirectory(): File = ctx.cacheDir

    override fun gameBroker(): EngineFileBroker? = gameBrokerImpl
    override fun saveBroker(): EngineFileBroker? = saveBrokerImpl
    override fun isolatedAudioBuffer(): ParcelFileDescriptor? = audioBufferImpl
    override fun isolatedAudioSampleRate(): Int = audioSampleRateImpl

    override fun log(priority: Int, tag: String, message: String, error: Throwable?) {
        val detail = error?.let { "\n${Log.getStackTraceString(it)}" }.orEmpty()
        runCatching { callback.log(priority, tag, message + detail) }
        // Isolated processes still have their own logcat identity; log
        // locally too so a run that never reaches the host still says
        // something under this process's own tag.
        Log.println(priority, "enginehost-isolated/$tag", message + detail)
    }

    override fun rumbleController(deviceId: Int, durationMs: Long, amplitude: Int): Boolean =
        runCatching { callback.rumbleController(deviceId, durationMs, amplitude) }.getOrDefault(false)

    override fun finish() {
        runCatching { callback.finish() }
    }

    override fun restart(arguments: Array<String>) {
        runCatching { callback.restart(arguments) }
    }

    override fun restartArguments(): Array<String> = restartArgs

    override fun fail(message: String) {
        runCatching { callback.fail(message) }
    }
}

/** [EngineFileBroker] (plugin-api, what a plugin's JNI layer calls) backed by the AIDL connection to the host. */
private class AidlFileBrokerAdapter(private val remote: IEngineFileBroker) : EngineFileBroker {
    override fun list(relativePath: String): Array<String> =
        runCatching { remote.list(relativePath) }.getOrNull() ?: emptyArray()

    override fun openRead(relativePath: String): ParcelFileDescriptor =
        remote.openRead(relativePath) ?: throw FileNotFoundException(relativePath)

    override fun openWrite(relativePath: String): ParcelFileDescriptor =
        remote.openWrite(relativePath) ?: throw IOException("cannot open $relativePath for writing")

    override fun commitWrite(relativePath: String) {
        remote.commitWrite(relativePath)
    }

    override fun delete(relativePath: String) {
        remote.delete(relativePath)
    }
}
