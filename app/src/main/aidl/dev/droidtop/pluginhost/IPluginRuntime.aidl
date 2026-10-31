package dev.droidtop.pluginhost;

import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import dev.droidtop.pluginhost.IPluginHostBroker;
import dev.droidtop.pluginhost.IPluginRuntimeCallback;

/**
 * The one binder contract between :app and a plugin process (docs/SPEC.md
 * 12a, docs/plugin-api.md 5.3): a full-trust process under droidtop's own
 * UID, or a contained one, an isolated process with a UID of its own.
 * Everything crosses as plain strings
 * (JSON in, JSON out) rather than a richer Parcelable, on purpose: the
 * boundary is untrusted-input-shaped either way ("droidtop treats what a
 * plugin returns as untrusted input"), so there is no value in a typed
 * Parcelable a malformed plugin could still violate, and a string payload
 * is trivially size-capped by the caller before it ever reaches this
 * interface (PluginRuntimeService rejects anything over the cap itself,
 * belt and braces).
 */
interface IPluginRuntime {
    /**
     * Registers a callback to receive every crash/job-progress/job-complete
     * notification this process reports, ALONGSIDE every other currently
     * registered callback -- PluginRuntimeService holds these in a
     * RemoteCallbackList and broadcasts to all of them, not just the
     * most recently registered one. This replaced a single nullable
     * field (setCallback) that a second concurrent binder connection
     * would silently overwrite (found and fixed 2026-09-27, dq-pluginui-01):
     * :app opens ONE connection per in-flight job (PluginJobsCenter) plus
     * short-lived ones for plain invoke() calls, all bound to the SAME
     * :pluginhost process/Service instance, so a single-callback field
     * meant only the last-connected caller ever heard about anything --
     * an earlier job's own progress/completion silently vanished the
     * moment a second job or even an unrelated settings-screen call
     * connected. Every registered caller now gets every event and is
     * expected to filter by the pluginId/jobId it already knows about
     * (see PluginJobsCenter's own doc comment for exactly how).
     */
    void registerCallback(IPluginRuntimeCallback callback);

    /** The other half of {@link #registerCallback} -- called on unbind so a dead or no-longer-interested connection stops receiving broadcasts. Safe to call with a callback that was never registered, or twice. */
    void unregisterCallback(IPluginRuntimeCallback callback);

    /**
     * Loads one plugin's already-validated, already-installed bundle.
     * installDir is this plugin's private per-plugin directory
     * (PluginStore.dataDirFor); entryClass is the manifest's declared
     * entry point. Validation (hashes, signature, ABI, schema) already
     * happened in :app's process before this is ever called -- this
     * method only loads code that has already passed every check in
     * PluginBundleInstaller.
     *
     * Returns false (never throws across the binder) when the class
     * can't be loaded or doesn't implement the plugin API; a plugin that
     * fails to load is disabled, exactly like one that crashes after
     * loading.
     *
     * rootApproved is PluginRecord.rootApproved at the moment :app
     * issued this load -- droidtop's own approval state never lives in
     * this process, so it is handed down on every load (see
     * PluginContext.hasRootApproval).
     *
     * broker is this plugin's own IPluginHostBroker object (docs/plugin-api.md
     * 1.4): the only way the plugin reaches droidtop. :app makes one per
     * plugin, so a call is attributed by WHICH object it arrived on, never
     * by an id the plugin claims.
     */
    boolean loadPlugin(String pluginId, String installDir, String entryClass, boolean rootApproved, IPluginHostBroker broker);

    /**
     * Loads a contained plugin (docs/plugin-api.md 5.3) in an isolated
     * process, which can open no file of droidtop's: :app opens every file
     * the plugin's kind needs (its code, and for python the runtime) and
     * hands them over as descriptors, files[i] named names[i].
     * manifestJson is the verified manifest. Returns false, never throws,
     * with the reason in lastLoadError. A full-trust process refuses it.
     */
    boolean loadContained(String pluginId, String manifestJson, in ParcelFileDescriptor[] files, in String[] names, IPluginHostBroker broker);

    /**
     * What this process can reach, as JSON (the containment check,
     * docs/plugin-api.md 5.3): whether it is isolated, its UID, whether a
     * network socket opens, whether droidtop's files and shared storage
     * list, and for a contained python plugin how its runtime loaded.
     */
    String reachability();

    /**
     * A contained plugin's own full-screen UI (ui.main, docs/plugin-api.md 1.7):
     * an isolated process has no window, so droidtop's PluginScreenActivity owns
     * the surface and hands it over, the plugin draws into it, and droidtop
     * forwards touch and keys. Returns null once the plugin's entrypoint draws,
     * else why not. The plugin closing its last route arrives as
     * IPluginRuntimeCallback.onScreenClosed.
     */
    String attachScreen(String pluginId, String entrypoint, String library, in Surface surface, in ParcelFileDescriptor frames, long framesCapacity, int width, int height, float density);

    void resizeScreen(String pluginId, int width, int height);

    /**
     * The secret the one-way screen calls below carry (Droidtop/tracker#511): a one-way call has no caller pid, so
     * those calls cannot be checked the way every other call is. droidtop asks for it once, over this checked call,
     * after attachScreen; a process of droidtop's UID that is not droidtop cannot get it.
     */
    long inputToken();

    oneway void screenTouch(long token, String pluginId, in MotionEvent event);

    oneway void screenKey(long token, String pluginId, in KeyEvent event);

    void detachScreen(String pluginId);

    /**
     * Why the last {@link #loadPlugin} of this plugin returned false, in a
     * sentence an end user can read ("the Flutter runtime is not installed",
     * the plugin's own readiness failure, ...), or an empty string when
     * there is nothing to say (it loaded, or was never asked to). Kept per
     * plugin id in this process; the caller reads it right after a false.
     */
    String lastLoadError(String pluginId);

    void unloadPlugin(String pluginId);

    /**
     * Calls one capability with a JSON-encoded argument bundle and
     * returns the plugin's JSON-encoded result, or null on any failure
     * (a timeout, an uncaught exception inside the plugin, a result over
     * the size cap). The caller (NativePluginRunner) applies its own
     * watchdog on top of this call in case the process itself hangs
     * rather than the call returning.
     */
    String invoke(String pluginId, String capability, String argsJson);

    /**
     * The v2 call envelope (docs/plugin-api.md 1.3): envelopeJson is a
     * PluginCall as JSON, the return value a PluginReply as JSON, or null
     * when the plugin threw or is not loaded. A plugin compiled before the
     * envelope existed is answered through its contract 1 capability
     * (LegacyHandle). Bound by the caller's own watchdog, like invoke.
     */
    String handle(String pluginId, String envelopeJson);

    /**
     * Starts a long-running job (docs/SPEC.md 12a's job shape). jobId is
     * chosen by the CALLER (:app's own PluginJobsCenter) and handed in
     * here, rather than generated by this process and returned -- found
     * and fixed 2026-09-27 (dq-pluginui-01) alongside the callback fix
     * above: a server-generated id returned as this call's own result
     * meant the id literally could not be known by the caller until
     * AFTER this call returned, while a fast job's own progress/complete
     * callback could arrive (dispatched to the job executor the instant
     * this call arrives on this side, not after it returns) before that
     * return trip completed -- the caller had nothing to compare the
     * event's jobId against yet, and dropped it. A caller-chosen id
     * removes the ordering requirement entirely: it exists before this
     * call is even made.
     *
     * Returns true when the plugin was loaded and the job was handed to
     * this process's own executor, false when the plugin isn't loaded --
     * NOT a signal that the plugin actually supports jobs (a plugin with
     * no startJob override still returns true here; its rejection
     * arrives as an ordinary job failure over onJobComplete, same as any
     * other job outcome). Progress and completion arrive later through
     * {@link IPluginRuntimeCallback#onJobProgress}/{@code onJobComplete}
     * -- this call itself is not bound by the per-call watchdog the way
     * {@link #invoke} is, since a real job is expected to run minutes.
     */
    boolean startJob(String pluginId, String capability, String argsJson, String jobId);

    void cancelJob(String pluginId, String jobId);

    /**
     * Hands this process a relay droidtop holds for a system service (docs/plugin-api.md 5.6, "Every plugin is
     * isolated, full access included"): an isolated plugin has no package a service would authorize, so its calls go
     * through {@code relay}, a binder in droidtop's process that forwards only what the plugin's declared full-access
     * points prescribe. {@code name} says which service: "shizuku" installs it as this process's Shizuku binder.
     */
    void attachRelay(String pluginId, String name, IBinder relay);
}
