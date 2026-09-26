package dev.enginehost.api;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.File;

/** Services whose ownership stays with Enginehost rather than a module. */
public interface EngineHost {
    /** Enginehost context. Modules must not retain it beyond the session. */
    Context context();
    /**
     * What the engine's SYSTEM save locations mean on this device (a user
     * profile, AppData, a browser's storage), in a folder the person chose.
     * Enginehost does not change where an engine saves: an engine that saves
     * beside the game on its desktop original keeps saving in the game folder
     * and has no use for this.
     */
    File saveDirectory();
    File cacheDirectory();
    EngineFileSystem fileSystem();

    /**
     * Host-brokered, read-only access to the game folder, present only
     * when this runtime is isolated (docs/engine-sandbox.md "Layer 2").
     * Null on an ordinary in-process launch, and null on a host too old
     * to know about isolation -- the default answers null so no existing
     * EngineHost implementation has to change to keep compiling.
     */
    default EngineFileBroker gameBroker() { return null; }

    /** The isolated runtime's save-folder counterpart to {@link #gameBroker()}; read-write. */
    default EngineFileBroker saveBroker() { return null; }

    /**
     * A shared-memory ring buffer to render 16-bit stereo PCM into at
     * {@link #isolatedAudioSampleRate()}, present only when this runtime
     * is isolated -- an isolated process cannot reach AudioFlinger to
     * open its own audio stream at all (docs/engine-sandbox.md "Audio"),
     * so the host owns the real output and reads what this buffer fills.
     * Null on an ordinary in-process launch, where a plugin opens its own
     * output device directly, and null if the host could not set audio
     * up (a game still plays; it is simply silent, exactly as when no
     * audio device is available today).
     *
     * <p>Layout: a 16-byte header -- write position, read position,
     * capacity, reserved, each a little-endian uint32 -- followed by
     * {@code capacity} bytes of ring data. The plugin owns the write
     * position and only ever advances it; the host owns the read
     * position the same way. Positions are byte offsets that only ever
     * increase, wrapped by {@code % capacity} to address the ring.
     */
    default ParcelFileDescriptor isolatedAudioBuffer() { return null; }

    /** The sample rate (Hz) {@link #isolatedAudioBuffer()} is rendered at; meaningless when that is null. */
    default int isolatedAudioSampleRate() { return 0; }

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
     * {@code arguments} are the game's own restart arguments (the list a
     * Godot game gives {@code OS.set_restart_on_exit}); upstream hands them
     * to the new instance as its command line, so the next run's plugin
     * receives them from {@link #restartArguments()}. Empty when the game
     * gave none.
     *
     * Added after the first hosts shipped. A plugin that may run on an older
     * Enginehost must catch {@link IncompatibleClassChangeError} (a plugin
     * compiles against its own copy of this interface, so on a host that does
     * not declare the method the call fails with NoSuchMethodError, not
     * AbstractMethodError; both are of that type) and fall back to
     * {@link #finish()}, which closes the game instead of leaving a dead
     * screen.
     */
    void restart(String[] arguments);

    /**
     * What the previous run of this game passed to {@link #restart}, for the
     * plugin to give its engine; empty on any launch that is not a restart.
     * Same compatibility rule as {@link #restart}: on an older Enginehost the
     * call fails to link, and there are no arguments.
     */
    String[] restartArguments();

    /**
     * Ends this runtime because the engine could not start, with
     * {@code message} as the reason the launch screen shows (with Report a
     * problem), exactly as when {@link EnginePlugin#onCreate} throws.
     *
     * For an engine that finds out only after onCreate has returned (Godot
     * 4.0 and 4.1 start in the fragment's view, which Android creates later)
     * and would otherwise answer with its own alert over a black screen. Same
     * compatibility rule as {@link #restart}: on an older Enginehost fall back
     * to {@link #finish()}.
     */
    void fail(String message);
}
