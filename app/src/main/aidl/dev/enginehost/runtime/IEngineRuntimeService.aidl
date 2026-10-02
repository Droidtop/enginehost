package dev.enginehost.runtime;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import dev.enginehost.runtime.IEngineFileBroker;
import dev.enginehost.runtime.IEngineRuntimeCallback;

/**
 * Host -> isolated runtime service (docs/engine-sandbox.md "Layer 2", first
 * milestone). One instance per launch: init() starts exactly one plugin
 * session; destroyRuntime() ends it and the process is not reused for
 * another launch.
 *
 * The first milestone (CatSystem2, CMVS) targeted an EngineStepDriven
 * plugin (plugin-api/EngineStepDriven), whose engine renders a software
 * pixel buffer rather than owning a real drawing surface, so the host
 * drove frames itself instead of depending on the isolated process
 * reaching SurfaceFlinger's vsync source, which was untried at the time
 * (see docs/engine-sandbox.md's "surfaces and input"). usesSurface()/
 * setGameSurface() below are the later, still-unconfirmed-on-a-device
 * mechanism for a plugin that cannot avoid owning a real Surface (SDL and
 * other GPU-rendering engines migrating off runtimeTransport: android-activity,
 * docs/engine-sandbox.md "Surface handoff") -- a plugin using it does not
 * implement EngineStepDriven and pixelWidth()/pixelHeight()/step()/
 * setFrameBuffer() are meaningless for it.
 */
interface IEngineRuntimeService {
    /**
     * dexFds and nativeLibraryFds are already-open, host-verified
     * descriptors for the installed bundle's own dex file(s) and native
     * library file(s) -- not a directory path, because this process
     * cannot always reach the bundle's own files by path at all: Android
     * 10+ makes the app's private data directory 0700, closing the
     * traversal permission EngineBundleInstaller's world-readable chmod
     * relied on for older releases (docs/engine-sandbox.md "The bundle's
     * own files"). The host opens each against the already hash-verified
     * bundle (InstalledBundleVerifier, called before this launch ever
     * reaches here) using the exact same path-safety check every other
     * bundle-file read in this app uses. nativeLibraryNames/nativeLibraryFds
     * and runtimeRequirementKeys/runtimeRequirementValues are parallel
     * arrays (AIDL has no Map); a library's name is what
     * System.loadLibrary(name) would be asked for at the far end
     * (PluginDexLoader.findLibrary). audioBuffer is the shared PCM ring
     * (EngineHost.isolatedAudioBuffer(); null when the host could not set
     * audio up, exactly like a device with no audio at all), rendered at
     * audioSampleRate -- an isolated process cannot reach AudioFlinger to
     * open its own output (docs/engine-sandbox.md "Audio"), which is why
     * this is a buffer the host itself reads rather than a real device.
     */
    void init(in ParcelFileDescriptor[] dexFds, String entrypointClass,
              in String[] nativeLibraryNames, in ParcelFileDescriptor[] nativeLibraryFds,
              String engine, String engineContext, String engineVersion, String runtimeVersion,
              String capabilityId, String execFile, String optionsJson,
              in String[] runtimeRequirementKeys, in String[] runtimeRequirementValues,
              in String[] runtimeComponentKeys, in String[] runtimeComponentValues,
              in String[] restartArguments,
              IEngineFileBroker gameBroker, IEngineFileBroker saveBroker,
              in ParcelFileDescriptor audioBuffer, int audioSampleRate,
              IEngineRuntimeCallback callback);

    int pixelWidth();
    int pixelHeight();

    /**
     * True when this launch's plugin renders GPU frames onto a real
     * Surface (docs/engine-sandbox.md "Surface handoff") rather than the
     * EngineStepDriven pixel-buffer path pixelWidth()/pixelHeight()/step()
     * below drive -- EnginePlugin.usesSurface(), asked once, right after
     * init() succeeds. When true, the host skips pixelWidth()/pixelHeight()/
     * setFrameBuffer()/step() entirely (they stay meaningless, matching
     * EngineStepDriven not being implemented) and calls setGameSurface()
     * instead, once its own SurfaceView is ready.
     */
    boolean usesSurface();

    /**
     * The host's own SurfaceView's Surface (Parcelable; the same
     * cross-process handoff MediaCodec.configure and Camera2's capture
     * targets already use to hand a decoder or camera pipeline in another
     * process somewhere to draw), for a plugin that answered true from
     * usesSurface(). Called at most once, before any input or lifecycle
     * call reaches the plugin. Whether an isolated_app UID may actually
     * reach SurfaceFlinger for this at all is not established from source
     * reading alone (docs/engine-sandbox.md "Surface handoff") -- this
     * method exists so that question can be tested on a device, not
     * because the answer is assumed here.
     */
    void setGameSurface(in Surface surface);

    /**
     * The host's own frame buffer: a plain file the host owns, sized
     * pixelWidth() * pixelHeight() * 4 bytes (ARGB_8888), that step()
     * below writes changed rows into instead of returning them as a
     * Binder array. Called exactly once, right after pixelWidth()/
     * pixelHeight() are known and before the first step().
     *
     * dq-sandbox-05 (BlueStacks): the isolated process died silently,
     * unexplained, about 11 seconds into frame stepping, with the host
     * discovering it only via a failed step() Binder call on an
     * unrelated, tiny follow-up transaction -- consistent with the
     * process's Binder transaction buffer having been exhausted by
     * `out int[] pixels` marshalling a full frame (CatSystem2's default
     * 1024x576 is ~2.25MB) through Binder's own flat buffer on every
     * step(), 60 times a second. Frame data crosses the boundary as
     * shared memory instead, the same way audio does (docs/engine-sandbox.md
     * "Audio"), so a step() call itself is a few bytes regardless of
     * picture size.
     */
    void setFrameBuffer(in ParcelFileDescriptor buffer);

    /**
     * One frame. Exactly EngineStepDriven.step()'s own return contract --
     * (firstChangedRow << 16) | changedRowCount, 0, or -1 once the engine
     * has ended -- but the changed rows themselves are written into the
     * buffer given to setFrameBuffer, not returned here.
     */
    int step();

    /** Host-normalized controller input, exactly EngineControllerEvent's fields. Returns whether the plugin consumed it. */
    boolean onControllerEvent(String action, float value, int deviceId, String deviceDescriptor, long eventTime);

    /** A tap or drag, already translated into the engine's own pixel space (EngineStepDriven). */
    void onPointerMove(int x, int y);
    void onPointerUp(int x, int y);

    void pauseRuntime();
    void resumeRuntime();
    void destroyRuntime();
}
