package dev.enginehost.api;

/**
 * Optional: a plugin whose engine renders one fixed-size pixel buffer and
 * can be driven a frame at a time from outside its own process, instead of
 * owning a View and a Choreographer loop itself.
 *
 * A plugin implements this to be eligible for an isolated runtime
 * (docs/engine-sandbox.md "Layer 2", first milestone). Under isolation
 * {@link EnginePluginSession#display()} is null -- there is no host
 * ViewGroup to attach a View into, because the process drawing the frame
 * has no window of its own -- and the host would otherwise have no way to
 * pace the engine: the isolated process is not assumed able to reach
 * SurfaceFlinger's vsync source (untried, and deliberately not depended on
 * for this first milestone; see the design doc). Instead the host, which
 * already runs in a process with full platform access, calls {@link #step}
 * on a schedule of its own -- a fixed interval for this first milestone,
 * not tied to this process's own vsync -- and draws the result itself.
 *
 * A plugin that does not implement this is never launched isolated even
 * if its bundle declares itself eligible; the host checks for the
 * interface before trying.
 */
public interface EngineStepDriven {
    int pixelWidth();
    int pixelHeight();

    /**
     * One frame. {@code pixels} is {@code pixelWidth() * pixelHeight()}
     * ARGB_8888 ints, host-owned and reused on every call -- only entries
     * inside the returned band are meaningful. Returns
     * {@code (firstChangedRow << 16) | changedRowCount} for a frame that
     * changed, {@code 0} for one that did not, or {@code -1} once the
     * engine has ended (the game closed, or a script fault) -- after which
     * this method is not called again.
     */
    int step(int[] pixels);

    /** A finger moving or held, already translated into this engine's own pixel space. */
    void onPointerMove(int x, int y);

    /** The finger lifting at this position: the tap that follows the moves it made getting there. */
    void onPointerUp(int x, int y);
}
