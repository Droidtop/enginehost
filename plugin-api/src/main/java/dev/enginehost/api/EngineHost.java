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
}
