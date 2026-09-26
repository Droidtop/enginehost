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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
            // dq-sandbox-05 (emulator-5560): a ClassNotFoundException out of
            // loadEnginePluginFromFds below reached "JavaBinder:" in logcat
            // -- proof it WAS thrown -- yet svc.init() on the host side
            // returned as if nothing had happened, and the launch limped on
            // to an unrelated "no picture size" failure instead. The reason:
            // android.os.Binder only marshals RemoteException, RuntimeException
            // and OutOfMemoryError back across a transaction automatically;
            // a plain checked Exception (ClassNotFoundException extends
            // ReflectiveOperationException, neither of those) propagates
            // past that machinery uncaught and is simply lost rather than
            // reaching the caller as a thrown exception. Wrapping every
            // failure from this method's real body in a well-known Binder
            // exception type here is what makes it actually cross the
            // boundary, so IsolatedRuntimeHost's own catch (Throwable) --
            // and the on-screen failure it shows -- see the real reason.
            // dq-sandbox-07 (emulator-5560): a checked ErrnoException
            // thrown inside ownedCopy(), even after this exact wrapping
            // existed, still did not surface on screen -- the launch
            // continued as if init() had succeeded. android.os.Parcel's
            // Binder exception marshalling only reliably reconstructs a
            // fixed set of well-known types on the calling side
            // (SecurityException, IllegalArgumentException,
            // NullPointerException, IllegalStateException, and a few
            // others); a generic, unrecognised RuntimeException is not
            // guaranteed the same treatment, which is the leading
            // explanation for that miss. Every one of Binder's own
            // recognised types passes through unchanged below; anything
            // else is wrapped in IllegalStateException, one of those
            // recognised types, rather than a plain RuntimeException a
            // second time.
            try {
                initInternal(
                    dexFds, entrypointClass, nativeLibraryNames, nativeLibraryFds,
                    engine, engineContext, engineVersion, runtimeVersion, capabilityId,
                    execFile, optionsJson, runtimeRequirementKeys, runtimeRequirementValues,
                    restartArguments, gameBroker, saveBroker, audioBuffer, audioSampleRate, callback,
                )
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: NullPointerException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "isolated init failed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
        }

        private fun initInternal(
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
            // dq-sandbox-09: Android 14's "safer dynamic code loading"
            // refuses ANY path-based dex file this process could itself
            // have written, seal or no seal -- confirmed directly (a
            // genuinely sealed, F_GET_SEALS-verified memfd was still
            // rejected). No path-based mechanism can satisfy that for a
            // dex this process makes itself, so the dex is read directly
            // into memory instead (loadEnginePluginFromInMemoryDex,
            // InMemoryDexClassLoader) -- reading bytes from the received
            // fd is not a reopen (the one operation Binder's own transfer
            // already cleared), so it needs no additional permission
            // either way, on any API level.
            val dexBuffers = dexFds.map { received ->
                ByteBuffer.wrap(FileInputStream(received.fileDescriptor).readBytes())
            }.toTypedArray()
            // The native library is not a dex/jar/apk, so it was never
            // subject to the check above -- dlopen(), which
            // IsolatedNativeBridge uses, never consulted ART's dex
            // loader at all. What it IS still subject to is the SELinux
            // denial dq-sandbox-05 found reopening the bundle's own raw
            // fd by path (isolated_app -> app_data_file): a memfd copy
            // sidesteps that the same way it always did, sealed or not,
            // which is why this half of ownedCopy() stays. Below API 30
            // there is no memfd_create binding at all, so the plain
            // received descriptor is the only option -- proven fine
            // there since dq-sandbox-03 (BlueStacks, API 28).
            val ownedCopiesRequired = Build.VERSION.SDK_INT >= 30
            val nativeLibrarySources = nativeLibraryFds.map { received ->
                if (ownedCopiesRequired) ownedCopy(received).also { heldFds += it } else received
            }
            val nativeLibraryFdPaths = nativeLibraryNames.indices.associate {
                nativeLibraryNames[it] to procFdPath(nativeLibrarySources[it])
            }
            dexFds.forEachIndexed { i, pfd -> Log.i("enginehost-isolated-runtime", "loader input: dex[$i] -> ${dexBuffers[i].remaining()} bytes read directly from ${procFdPath(pfd)}, no path handed to any loader") }
            nativeLibrarySources.forEachIndexed { i, pfd ->
                logLoaderInput("nativeLib[$i]=${nativeLibraryNames.getOrNull(i)}", pfd)
            }
            val loaded = loadEnginePluginFromInMemoryDex(dexBuffers, entrypointClass, nativeLibraryFdPaths, classLoader)
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

        /** The host's own frame buffer (docs/engine-sandbox.md "Audio" precedent); see the AIDL doc comment. */
        override fun setFrameBuffer(buffer: ParcelFileDescriptor) {
            heldFds += buffer
            frameBufferFd = buffer
        }

        private var frameBufferFd: ParcelFileDescriptor? = null
        private var frameScratch: IntArray = IntArray(0)
        private var frameBytes: ByteArray = ByteArray(0)

        override fun step(): Int = runCatching {
            val driven = stepDriven ?: return@runCatching -1
            val width = driven.pixelWidth()
            val height = driven.pixelHeight()
            if (frameScratch.size != width * height) frameScratch = IntArray(width * height)
            val band = driven.step(frameScratch)
            if (band > 0) writeChangedRows(width, band)
            band
        }.onFailure { Log.e(TAG, "step failed", it) }.getOrDefault(-1)

        /**
         * Copies the rows [band] says changed into [frameBufferFd] at
         * their byte offset -- never the whole frame, matching the same
         * row-diff [band] already carries so this is no more data than
         * the old `out int[] pixels` transferred, just moved off Binder
         * and onto a plain pwrite (dq-sandbox-05, "the frame-transfer
         * path" -- see the AIDL doc comment on setFrameBuffer).
         */
        private fun writeChangedRows(width: Int, band: Int) {
            val fd = frameBufferFd ?: return
            val top = band ushr 16
            val rows = band and 0xffff
            val byteLength = rows * width * 4
            if (frameBytes.size < byteLength) frameBytes = ByteArray(byteLength)
            ByteBuffer.wrap(frameBytes, 0, byteLength).order(ByteOrder.nativeOrder())
                .asIntBuffer().put(frameScratch, top * width, rows * width)
            val byteOffset = top.toLong() * width * 4
            var done = 0
            while (done < byteLength) {
                val n = Os.pwrite(fd.fileDescriptor, frameBytes, done, byteLength - done, byteOffset + done)
                if (n <= 0) break
                done += n
            }
        }

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
 * What this process is about to hand [IsolatedNativeBridge] for one
 * native library, and whether the kernel itself agrees it is sealed
 * non-writable right now -- not whether [ownedCopy] believes it sealed
 * something earlier. Added after dq-sandbox-08 found a fd ART rejected
 * as writable that neither of that run's two ownedCopy() calls (both
 * confirmed sealed by their own readback) accounted for -- dq-sandbox-09
 * later found that fd was the dex itself (now loaded a different way
 * entirely, see [loadEnginePluginFromInMemoryDex]) and that sealing was
 * never what ART's check inspects in the first place, but the direct,
 * per-input trail this produces stays useful for the native library,
 * which still goes through [ownedCopy] for an unrelated reason (the
 * SELinux denial reopening the bundle's own raw fd, dq-sandbox-05).
 */
private fun logLoaderInput(label: String, pfd: ParcelFileDescriptor) {
    val seals = runCatching { Os.fcntlInt(pfd.fileDescriptor, F_GET_SEALS, 0) }.getOrElse { -2 }
    Log.i(
        "enginehost-isolated-runtime",
        "loader input: $label -> ${procFdPath(pfd)} " +
            "(F_GET_SEALS=$seals, F_SEAL_WRITE ${if (seals >= 0 && seals and F_SEAL_WRITE != 0) "set" else "NOT set"})",
    )
}

/**
 * A copy of pfd's bytes into an anonymous, memory-backed file this
 * process created itself (memfd_create(2)), sealed non-writable before
 * being handed anywhere. Used only for the plugin's native library now
 * ([loadEnginePluginFromInMemoryDex] reads the dex directly into memory
 * instead, needing no path-based copy at all -- dq-sandbox-09 found
 * that sealing a memfd copy of the DEX never actually satisfied ART's
 * writable-dex check, which is not seal-based; see that function's own
 * doc comment). Called only when the caller has already checked API
 * 30+ ([Os.memfd_create] has no Java binding below it); on any failure
 * this THROWS rather than returning a fallback value -- dq-sandbox-05
 * (emulator-5560, SELinux enforcing) found the caller's old fallback
 * (the plain received descriptor, reopened by /proc/self/fd path)
 * genuinely cannot work on a device that reaches this function at all:
 * it hits a real avc denial (isolated_app -> app_data_file,
 * permissive=0) opening the bundle's own file by that path. Below API
 * 30, callers never call this at all and keep using the plain
 * descriptor directly, unchanged -- proven to work there since
 * dq-sandbox-03 (BlueStacks, API 28).
 *
 * Reads pfd directly (no reopen: this is the one operation Binder's own
 * fd transfer already cleared), so this needs no permission this
 * process does not already have from receiving pfd in the first place.
 *
 * Sealed non-writable (F_ADD_SEALS) before it is handed anywhere: a
 * memfd this process created is otherwise still writable by it (the
 * mode bits say so, even though nothing here writes to it again), and
 * dq-sandbox-04 on API 34 found ART's own dex loader refuses exactly
 * that -- "SecurityException: Writable dex file '...' is not allowed."
 * -- for a dex opened from such a descriptor. ART's own loader checks
 * F_GET_SEALS for F_SEAL_WRITE as its accepted proof a writable-looking
 * fd will not actually be written to again (the same mechanism used
 * elsewhere on Android for handing over dex/APK content by descriptor),
 * so sealing is what satisfies it, not merely reopening read-only.
 * Applied to native-library copies too, not only dex: harmless for
 * dlopen and one less distinct code path to reason about.
 *
 * MFD_ALLOW_SEALING is required at creation time: without it,
 * memfd_create(2) starts the file with F_SEAL_SEAL already set, which
 * blocks every later F_ADD_SEALS call with EPERM (dq-sandbox-05,
 * emulator-5560) -- confirmed against memfd_create(2)'s own man page,
 * not guessed a second time after this same file's F_ADD_SEALS name
 * mistake (fcntlLong -> fcntlInt) one commit earlier.
 *
 * dq-sandbox-07 (emulator-5560, API 34): the seal alone -- F_ADD_SEALS/
 * F_SEAL_WRITE -- is what satisfies ART's dex loader; confirmed by the
 * writable-dex rejection actually clearing on this rig. A second
 * hardening step tried here in the previous pass, `Os.fchmod(memFd,
 * 0444)`, is itself denied by SELinux (`avc: denied { setattr }` on the
 * memfd's own `appdomain_tmpfs` object) and removed rather than fought:
 * the seal is the layer ART actually checks and the isolated app domain
 * is allowed to apply, so it is also the only layer this needs.
 *
 * Every failure in this function -- memfd_create, the copy, the seal,
 * the dup -- is deliberately caught here and rethrown as
 * [IllegalStateException] rather than left to propagate as whatever
 * exception type actually occurred: `android.os.Parcel`'s Binder
 * exception marshalling only reliably reconstructs a fixed set of
 * well-known types on the calling side (`SecurityException`,
 * `IllegalArgumentException`, `NullPointerException`,
 * `IllegalStateException`, and a few others) -- an arbitrary checked
 * exception, or even a plain unrecognised `RuntimeException`, is not
 * guaranteed the same treatment. dq-sandbox-07's own fchmod failure
 * reached the isolated process's logcat clearly but never surfaced on
 * screen at all (the launch continued to the fixture's ordinary
 * "no picture size" failure instead) -- consistent with exactly this:
 * a checked `ErrnoException` thrown here, uncaught by this function
 * itself before this pass, apparently did not survive the trip back to
 * [IsolatedRuntimeService.init]'s own caller the way [SecurityException]
 * reliably has in every other rig capture. Catching every failure at
 * its true source and always re-throwing one of Binder's own
 * known-safe types removes that ambiguity for good, rather than relying
 * on init()'s outer wrapper (IsolatedRuntimeService.init) to have
 * guessed the same thing correctly for whatever exception type happens
 * to reach it.
 */
private fun ownedCopy(pfd: ParcelFileDescriptor): ParcelFileDescriptor {
    // dq-sandbox-06 (emulator-5560, API 34): the writable-dex
    // SecurityException came back with NONE of this function's own log
    // lines anywhere in logcat -- meaning it either was not reached, or
    // ran and returned successfully (this function never logged its own
    // success, only its failure, so silence was ambiguous either way).
    // Logged explicitly now so a future rig run can say for certain
    // which it was, per the coordinator's own request, rather than
    // inferring it from an absence.
    Log.i("enginehost-isolated-runtime", "ownedCopy: sealing a memfd copy for ${procFdPath(pfd)}")
    try {
        val memFd = Os.memfd_create("enginehost-bundle", MFD_ALLOW_SEALING)
        try {
            FileOutputStream(memFd).use { output -> FileInputStream(pfd.fileDescriptor).copyTo(output) }
            Os.lseek(memFd, 0, OsConstants.SEEK_SET)
            val seals = F_SEAL_SEAL or F_SEAL_SHRINK or F_SEAL_GROW or F_SEAL_WRITE
            Os.fcntlInt(memFd, F_ADD_SEALS, seals)
            val readBack = Os.fcntlInt(memFd, F_GET_SEALS, 0)
            Log.i(
                "enginehost-isolated-runtime",
                "ownedCopy: sealed (F_GET_SEALS=$readBack, F_SEAL_WRITE " +
                    "${if (readBack and F_SEAL_WRITE != 0) "set" else "NOT set"})",
            )
            return ParcelFileDescriptor.dup(memFd)
        } finally {
            Os.close(memFd)
        }
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Throwable) {
        Log.e("enginehost-isolated-runtime", "ownedCopy failed for ${procFdPath(pfd)}", e)
        throw IllegalStateException("could not prepare a sealed copy of the bundle's own code: " + (e.message ?: e.javaClass.simpleName), e)
    }
}

// linux/fcntl.h and linux/memfd.h -- not exposed as named constants on
// android.system.OsConstants (only MFD_CLOEXEC is; confirmed against
// developer.android.com's own OsConstants reference), so these are the
// raw values memfd_create(2)'s own man page documents.
private const val F_ADD_SEALS = 1033
private const val F_GET_SEALS = 1034
private const val F_SEAL_SEAL = 0x0001
private const val F_SEAL_SHRINK = 0x0002
private const val F_SEAL_GROW = 0x0004
private const val F_SEAL_WRITE = 0x0008
private const val MFD_ALLOW_SEALING = 0x0002

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
