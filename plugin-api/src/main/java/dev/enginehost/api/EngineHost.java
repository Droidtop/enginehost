package dev.enginehost.api;

import android.content.Context;
import java.io.File;

/** Services whose ownership stays with Enginehost rather than a module. */
public interface EngineHost {
    /** Enginehost context. Modules must not retain it beyond the session. */
    Context context();
    File saveDirectory();
    File cacheDirectory();
    EngineFileSystem fileSystem();
    void log(int priority, String tag, String message, Throwable error);
    /** Requests haptic feedback from the controller that produced an event. */
    boolean rumbleController(int deviceId, long durationMs, int amplitude);
    void finish();

    /**
     * Ends this runtime and starts the same game again in a fresh process.
     *
     * For an engine whose game asks to be restarted (Godot's
     * {@code OS.set_restart_on_exit} and its Android restart request, a
     * language change that needs a clean engine). Such engines cannot be
     * de-initialised in place: upstream Godot's own Android app kills its
     * whole process and relaunches for exactly this reason. Enginehost owns
     * the runtime process and the screen that launched it, so the restart is
     * the host's to perform: the launch is planned again from the game
     * folder, which also picks up any setting changed in between.
     *
     * Added after the first hosts shipped. A plugin that may run on an older
     * Enginehost must catch {@link IncompatibleClassChangeError} (a plugin
     * compiles against its own copy of this interface, so on a host that does
     * not declare the method the call fails with NoSuchMethodError, not
     * AbstractMethodError; both are of that type) and fall back to
     * {@link #finish()}, which closes the game instead of leaving a dead
     * screen.
     */
    void restart();
}
