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
}
