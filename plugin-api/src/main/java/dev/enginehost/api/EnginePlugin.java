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

    /** Return true when the engine consumed this host-normalized controller action. */
    default boolean onControllerEvent(EngineControllerEvent event) throws Exception { return false; }

    default void onStart() throws Exception {}
    default void onResume() throws Exception {}
    default void onPause() throws Exception {}
    default void onStop() throws Exception {}
    default void onDestroy() throws Exception {}

    /**
     * True when this plugin renders GPU frames onto a real
     * {@code android.view.Surface} (docs/engine-sandbox.md "Surface
     * handoff") rather than the software pixel buffer
     * {@link EngineStepDriven#step} drives. Checked once under isolation,
     * after {@link #onCreate}: a plugin answering true is not required to
     * implement {@link EngineStepDriven}, and one answering false (the
     * default) must implement it to be eligible to run isolated at all
     * (IsolatedRuntimeService's own check). Never checked for an
     * in-process launch, which already gets a real {@code ViewGroup} from
     * {@link EnginePluginSession#display()} to attach its own rendering
     * surface into directly, in the same process.
     */
    default boolean usesSurface() { return false; }

    /**
     * Hands this plugin the {@code Surface} an isolated launch's
     * host-owned view created (docs/engine-sandbox.md "Surface handoff");
     * called at most once, only when {@link #usesSurface()} answered true
     * and only under isolation. An in-process launch never calls this.
     */
    default void attachIsolatedSurface(android.view.Surface surface) {}
}
