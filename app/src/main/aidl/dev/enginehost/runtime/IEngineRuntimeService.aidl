package dev.enginehost.runtime;

import android.os.ParcelFileDescriptor;
import dev.enginehost.runtime.IEngineFileBroker;
import dev.enginehost.runtime.IEngineRuntimeCallback;

/**
 * Host -> isolated runtime service (docs/engine-sandbox.md "Layer 2", first
 * milestone). One instance per launch: init() starts exactly one plugin
 * session; destroyRuntime() ends it and the process is not reused for
 * another launch.
 *
 * There is no Surface or SurfaceControlViewHost here on purpose: the first
 * milestone targets an EngineStepDriven plugin (plugin-api/EngineStepDriven),
 * whose engine renders a software pixel buffer rather than owning a real
 * drawing surface, so the host drives frames itself instead of depending on
 * the isolated process reaching SurfaceFlinger's vsync source, which is
 * untried (see the design doc's "surfaces and input").
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
     * (PluginDexLoader.findLibrary).
     */
    void init(in ParcelFileDescriptor[] dexFds, String entrypointClass,
              in String[] nativeLibraryNames, in ParcelFileDescriptor[] nativeLibraryFds,
              String engine, String engineContext, String engineVersion, String runtimeVersion,
              String capabilityId, String execFile, String optionsJson,
              in String[] runtimeRequirementKeys, in String[] runtimeRequirementValues,
              in String[] restartArguments,
              IEngineFileBroker gameBroker, IEngineFileBroker saveBroker,
              IEngineRuntimeCallback callback);

    int pixelWidth();
    int pixelHeight();

    /**
     * One frame, exactly EngineStepDriven.step()'s contract: pixels is
     * pixelWidth() * pixelHeight() ARGB_8888 ints; the return value is
     * (firstChangedRow << 16) | changedRowCount, 0, or -1 once the engine
     * has ended.
     */
    int step(out int[] pixels);

    /** Host-normalized controller input, exactly EngineControllerEvent's fields. Returns whether the plugin consumed it. */
    boolean onControllerEvent(String action, float value, int deviceId, String deviceDescriptor, long eventTime);

    /** A tap or drag, already translated into the engine's own pixel space (EngineStepDriven). */
    void onPointerMove(int x, int y);
    void onPointerUp(int x, int y);

    void pauseRuntime();
    void resumeRuntime();
    void destroyRuntime();
}
