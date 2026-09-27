package dev.enginehost.debug;

import android.view.Surface;

/**
 * Debug/CI-only feasibility exerciser for docs/engine-sandbox.md's "Surface
 * handoff" open question: can an android:isolatedProcess="true" service
 * actually composite into a real android.view.Surface it never created
 * itself (one handed across Binder from the host process), i.e. does the
 * isolated_app SELinux domain allow reaching SurfaceFlinger/gralloc for
 * this at all. Absent from release builds (app/src/debug only).
 */
interface IDebugSurfaceTest {
    /**
     * Fills [surface] with a solid colour via Canvas (surface.lockCanvas()/
     * unlockCanvasAndPost()), the simplest possible composite -- no GL/EGL
     * context, so a failure here isolates the SurfaceFlinger-reachability
     * question from anything GL-specific. Returns null on success, or the
     * exception's own message/class name on failure (rather than letting
     * the call throw across Binder, matching this codebase's own lesson
     * that not every exception type survives an AIDL transaction).
     */
    String fillSurface(in Surface surface);
}
