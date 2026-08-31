package dev.enginehost.api;

/** Module format and binary contract constants shared by hosts and module builds. */
public final class EnginePluginContract {
    private EnginePluginContract() {}

    public static final int API_VERSION = 1;
    public static final int ENGINE_BUNDLE_FORMAT_VERSION = 1;
    public static final String ENGINE_BUNDLE_MANIFEST = "enginehost-bundle.json";
    public static final String ENGINE_BUNDLE_SIGNATURE = "enginehost-bundle.sig";
}
