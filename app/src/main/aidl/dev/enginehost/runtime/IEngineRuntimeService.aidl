package dev.enginehost.runtime;

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
     * bundleDirectory is the installed bundle's own root -- readable by
     * this process because EngineBundleInstaller made an isolatable
     * bundle's files so at install time, not through a broker: bundle
     * bytes are install-time verified and launch-independent, unlike game
     * and save files (docs/engine-bundle-format.md "Sandboxing and the
     * plugin contract"). runtimeRequirement keys/values are parallel
     * arrays (AIDL has no Map).
     */
    void init(String bundleDirectory, String entrypointClass, in String[] dexFiles,
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
