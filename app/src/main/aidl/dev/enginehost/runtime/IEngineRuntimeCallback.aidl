package dev.enginehost.runtime;

/**
 * Isolated runtime service -> host: the EngineHost calls that only the
 * process actually holding the window, the vibrator service and the
 * launch's Activity result can carry out. IsolatedRuntimeHost implements
 * this and hands it to the service at init(); the service's own
 * EngineHost implementation (in IsolatedRuntimeService) is a thin proxy
 * onto it.
 */
interface IEngineRuntimeCallback {
    void log(int priority, String tag, String message);
    boolean rumbleController(int deviceId, long durationMs, int amplitude);
    void finish();
    void fail(String message);
    void restart(in String[] arguments);
    /** EngineHost.openLink: offered to the person by the host (GameLinkOffer); returns at once. */
    void openLink(String url);
    /** EngineHost.emitText: one ehtext/1 event (GameText); oneway, so a slow host never stalls the engine. */
    oneway void emitText(String event);
    /** EngineHost.replaceText: the translated line to print, from what the host holds; never waits on a network. */
    String replaceText(String event);
}
