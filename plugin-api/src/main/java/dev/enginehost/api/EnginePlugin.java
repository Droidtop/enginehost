package dev.enginehost.api;

/**
 * Stable Enginehost-owned lifecycle implemented by every runtime module.
 *
 * Engine bundles must compile this artifact as compileOnly. Enginehost supplies
 * the actual API classes from the parent class loader at runtime.
 */
public interface EnginePlugin {
    /** Called exactly once, on the runtime process main thread. */
    void onCreate(EnginePluginSession session) throws Exception;

    default void onStart() throws Exception {}
    default void onResume() throws Exception {}
    default void onPause() throws Exception {}
    default void onStop() throws Exception {}
    default void onDestroy() throws Exception {}
}
