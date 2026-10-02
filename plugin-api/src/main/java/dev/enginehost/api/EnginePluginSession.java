package dev.enginehost.api;

import android.view.ViewGroup;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable launch state after folder config and capability resolution. */
public final class EnginePluginSession {
    private final File bundleDirectory;
    private final ViewGroup display;
    private final EngineHost host;
    private final String gamePath;
    private final String engine;
    private final String engineContext;
    private final String engineVersion;
    private final String runtimeVersion;
    private final String capabilityId;
    private final String execFile;
    private final String optionsJson;
    private final Map<String, String> runtimeRequirements;
    private final Map<String, String> runtimeComponents;

    public EnginePluginSession(
            File bundleDirectory,
            ViewGroup display,
            EngineHost host,
            String gamePath,
            String engine,
            String engineContext,
            String engineVersion,
            String runtimeVersion,
            String capabilityId,
            String execFile,
            String optionsJson,
            Map<String, String> runtimeRequirements,
            Map<String, String> runtimeComponents) {
        this.bundleDirectory = Objects.requireNonNull(bundleDirectory);
        // Null under an isolated runtime, which owns no window of its own
        // (docs/engine-sandbox.md "Layer 2"); see EngineStepDriven.
        this.display = display;
        this.host = Objects.requireNonNull(host);
        this.gamePath = Objects.requireNonNull(gamePath);
        this.engine = Objects.requireNonNull(engine);
        this.engineContext = Objects.requireNonNull(engineContext);
        this.engineVersion = Objects.requireNonNull(engineVersion);
        this.runtimeVersion = Objects.requireNonNull(runtimeVersion);
        this.capabilityId = Objects.requireNonNull(capabilityId);
        this.execFile = execFile;
        this.optionsJson = optionsJson;
        this.runtimeRequirements = Collections.unmodifiableMap(new LinkedHashMap<>(runtimeRequirements));
        this.runtimeComponents = Collections.unmodifiableMap(new LinkedHashMap<>(runtimeComponents));
    }

    /** Read-only root containing this engine bundle's dex, native libraries, assets and notices. */
    public File bundleDirectory() { return bundleDirectory; }
    /**
     * Host-owned root into which the plugin attaches its rendering view, or
     * null when this runtime is isolated and has no window of its own -- see
     * {@link EngineStepDriven}, the alternative a plugin implements to run
     * under an isolated runtime.
     */
    public ViewGroup display() { return display; }
    public EngineHost host() { return host; }
    public String gamePath() { return gamePath; }
    public String engine() { return engine; }
    public String engineContext() { return engineContext; }
    public String engineVersion() { return engineVersion; }
    public String runtimeVersion() { return runtimeVersion; }
    public String capabilityId() { return capabilityId; }
    public String execFile() { return execFile; }
    public String optionsJson() { return optionsJson; }
    public Map<String, String> runtimeRequirements() { return runtimeRequirements; }
    /**
     * The runtime components (name to version) the selected capability
     * declares in its {@code runtimeComponents}: what this launch loads
     * beside the engine. It covers every name in {@link #runtimeRequirements()},
     * which says only what the game asked for. A plugin carrying loadable
     * components loads exactly these (docs/engine-bundle-format.md, "Runtime
     * components (subplugins)"). Added after apiVersion 1 shipped: a plugin
     * that may run on an older Enginehost catches {@code NoSuchMethodError}
     * and falls back to its own table keyed by {@link #capabilityId()}.
     */
    public Map<String, String> runtimeComponents() { return runtimeComponents; }
}
