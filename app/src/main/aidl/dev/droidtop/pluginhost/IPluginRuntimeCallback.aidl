package dev.droidtop.pluginhost;

/**
 * Handed to {@link IPluginRuntime#registerCallback} so a plugin process
 * can report a crash back to :app without :app having to poll.
 * This is the other half of PluginCrashPolicy's containment: the binder
 * DeathRecipient catches the process dying outright, this callback
 * catches a plugin that threw but left the process alive.
 *
 * PluginRuntimeService now broadcasts every notification to EVERY
 * currently registered callback (a RemoteCallbackList), not just one --
 * see registerCallback's own doc comment for the bug this replaced. A
 * receiver whose own connection is dedicated to one job (PluginJobsCenter's
 * normal shape) will get other jobs' onJobProgress/onJobComplete calls
 * too and is expected to filter by the jobId it already knows about.
 */
oneway interface IPluginRuntimeCallback {
    /** capability is empty ("") when the crash happened outside any one invoke() call, e.g. during load. */
    void onPluginCrashed(String pluginId, String capability, String reason);

    /** One progress tick for a job started with {@link IPluginRuntime#startJob}. percent is -1 for indeterminate. */
    void onJobProgress(String pluginId, String jobId, int percent, String statusLine, String resumePayload);

    /** Fired exactly once per job, success or failure -- resultJson is the same shape {@link IPluginRuntime#invoke} returns. */
    void onJobComplete(String pluginId, String jobId, String resultJson);

    /** The plugin's own screen (IPluginRuntime.attachScreen) closed its last route: droidtop closes the screen. */
    void onScreenClosed(String pluginId);

    /**
     * A contained plugin's screen finished a frame in the shared frames droidtop handed over (frames, framesCapacity on
     * attachScreen): frame [index] (0 or 1) of [width] x [height] RGBA pixels. droidtop copies it into its Surface.
     */
    void onScreenFrame(String pluginId, int index, int width, int height);
}
