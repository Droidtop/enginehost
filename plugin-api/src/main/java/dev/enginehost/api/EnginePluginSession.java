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
    private final Map<String, File> runtimeComponentDirectories;

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
        this(bundleDirectory, display, host, gamePath, engine, engineContext, engineVersion, runtimeVersion,
                capabilityId, execFile, optionsJson, runtimeRequirements, runtimeComponents,
                Collections.<String, File>emptyMap());
    }

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
            Map<String, String> runtimeComponents,
            Map<String, File> runtimeComponentDirectories) {
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
        this.runtimeComponentDirectories =
                Collections.unmodifiableMap(new LinkedHashMap<>(runtimeComponentDirectories));
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
     * The runtime components (name to version) this launch loads beside the
     * engine: those the selected capability carries ({@code runtimeComponents})
     * and the separately installed component bundles chosen for the game's
     * requirements ({@link #runtimeComponentDirectory(String)} names where
     * each is). It covers every name in {@link #runtimeRequirements()},
     * which says only what the game asked for. A plugin loads exactly these
     * (docs/engine-bundle-format.md, "Runtime components (subplugins)").
     * Added after apiVersion 1 shipped: a plugin
     * that may run on an older Enginehost catches {@code NoSuchMethodError}
     * and falls back to its own table keyed by {@link #capabilityId()}.
     */
    public Map<String, String> runtimeComponents() { return runtimeComponents; }
    /**
     * The read-only, verified root of the separately installed component
     * bundle that provides {@code name} for this launch, or null when the
     * component is not a separate bundle (the engine carries it itself, or
     * the launch loads no such component). The layout inside is the
     * component's own (docs/engine-bundle-format.md, "Runtime components").
     * Added after apiVersion 1 shipped: a plugin that may run on an older
     * Enginehost catches {@code NoSuchMethodError}; an older Enginehost
     * never selects a capability that needs a separate component.
     */
    public File runtimeComponentDirectory(String name) { return runtimeComponentDirectories.get(name); }
}
