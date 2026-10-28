package dev.enginehost.api;

import android.app.Activity;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.File;

/** Services whose ownership stays with Enginehost rather than a module. */
public interface EngineHost {
    /** Enginehost context. Modules must not retain it beyond the session. */
    Context context();

    /**
     * The real host Activity (RuntimeActivity), for a plugin family whose
     * upstream Java glue genuinely needs a live, attached Activity rather
     * than a Context -- e.g. an SDL-based engine migrating off
     * runtimeTransport: android-activity, whose own SurfaceView-owning
     * glue calls real Activity instance methods (getWindow(),
     * isInMultiWindowMode(), getRequestedOrientation()) directly
     * (docs/engine-sandbox.md "Correction... SDLSurface.java"). Null
     * under isolation (docs/engine-sandbox.md "Layer 2"): an isolated
     * service is never an Activity and never gets one, by the same rule
     * that makes session.display() null there too. A plugin that cannot
     * do without a real Activity cannot be made isolatable.
     */
    default Activity activity() { return null; }
    /**
     * What the engine's SYSTEM save locations mean on this device (a user
     * profile, AppData, a browser's storage), in a folder the person chose.
     * Enginehost does not change where an engine saves: an engine that saves
     * beside the game on its desktop original keeps saving in the game folder
     * and has no use for this.
     */
    File saveDirectory();

    /**
     * The game's own save folder, when the app that launched it gave one
     * (docs/contract.md "A caller's save folder: saveRoot"): exactly the
     * folder the game's save files are in, laid out as the cloud or other
     * source keeps it. When this answers a folder, the engine uses it as the
     * game's save directory itself, adding no folder of its own (no Ren'Py
     * save_directory, no Godot app_userdata/project), and saveDirectory()
     * answers the same folder. Null otherwise, and on a host too old to know.
     */
    default File gameSaveDirectory() { return null; }

    /**
     * Tells Enginehost the folder this game's saves are actually written in,
     * once the engine has worked it out: the folder an engine names for
     * itself inside {@link #saveDirectory()} (Godot's {@code user://}, which
     * is {@code godot/app_userdata/<project>} or the project's custom user
     * directory; Ren'Py's save directory). Only the engine knows that name,
     * so the engine says it, and Enginehost names that folder wherever it
     * says where a game's saves are (docs/contract.md "Game end").
     *
     * <p>A folder that is not {@link #saveDirectory()} itself or inside it is
     * ignored. An engine that saves beside the game, or uses
     * {@link #gameSaveDirectory()} as is, has nothing to report.
     *
     * <p>Added after the first hosts shipped; the default does nothing, so an
     * older host simply does not record it. A plugin that may run on an older
     * Enginehost catches {@link IncompatibleClassChangeError}, as for
     * {@link #restart}.
     */
    default void reportSaveDirectory(File directory) {}

    File cacheDirectory();
    EngineFileSystem fileSystem();

    /**
     * Asks Enginehost for a runtime component (a subplugin) the running game turned out to need,
     * by its ecosystem-scoped name ("spine-godot"): the game asks, and receives
     * (docs/engine-bundle-format.md, "A game asks, and receives"). Enginehost picks the version the
     * game's own files call for, installs the component bundle if it is not installed and has the
     * person accept its licence if it is not approved, while this call waits. It answers the
     * verified, read-only root of the component bundle, laid out as the component's own
     * documentation says (for Godot, {@code godot-<series>/<name>.gdextension}), or null when
     * there is none to be had: nothing published, the person said not now, or the game ended.
     *
     * <p>Blocks, for as long as the person takes: never call it on the UI thread. The engine
     * pauses meanwhile, which an engine can do without the game noticing more than a pause.
     *
     * <p>Added after the first hosts shipped; the default answers null. A plugin that may run on
     * an older Enginehost catches {@link IncompatibleClassChangeError}, as for {@link #restart}.
     */
    default java.io.File requestComponent(String name) { return null; }

    /**
     * A link the game asks to open: a web page, a Discord invite, a store page, an email address.
     * Enginehost checks it, says what it is, and offers it to the person: open it in the app it
     * belongs to, in a browser, copy it, or not (docs/SPEC.md, "Links a game opens"). It never
     * opens one by itself, and a core never calls startActivity for a link: every engine's own
     * route (SDL openURL, Godot OS.shell_open, Ren'Py webbrowser, ...) comes here instead.
     *
     * <p>Returns at once, true when Enginehost took the link (a refused one included: the
     * person is told). Added after the first hosts shipped; the default answers false, and a
     * plugin that may run on an older Enginehost catches {@link IncompatibleClassChangeError},
     * as for {@link #restart}.
     */
    default boolean openLink(String url) { return false; }

    /**
     * One event of the game's text: a line on screen with its speaker, a menu, the reader moving
     * on, a scene change. {@code event} is one JSON object in the {@code ehtext/1} format
     * (docs/text-services.md). Enginehost reads the game's text aloud and translates it from these
     * (Droidtop/tracker#490); a core never does either itself, it only reports what the engine
     * shows. Speaker names come from the engine wherever it knows them.
     *
     * <p>Returns at once and may be called from any thread; an event the host cannot read, or
     * one past the rate cap, is dropped without a word. Added after the first hosts shipped; the
     * default does nothing, and a plugin that may run on an older Enginehost catches
     * {@link IncompatibleClassChangeError}, as for {@link #restart}, and stops sending.
     */
    default void emitText(String event) {}

    /**
     * The text to print instead of a line or a menu, for an engine that prints translated text
     * in its own text box (docs/text-services.md, "Printing translated text"): {@code event} is
     * the {@code line} or {@code choices} event the core is about to show, and the answer is the
     * same event with its text replaced, or null to print the original. Answers from what the
     * host already has and never waits on a network, so an engine may ask on its own thread
     * before it draws. The default, and any host without a chosen translator, answers null.
     */
    default String replaceText(String event) { return null; }

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
     *
     * <p>Also for an engine that cannot go on once its game is running (a
     * script that reaches an instruction the engine cannot run): ending with
     * this instead of a plain end puts the reason and Report a problem in
     * front of the person. The isolated runtime words it as the game having
     * stopped once a frame has been drawn (2026-10-23).
     */
    void fail(String message);
}
