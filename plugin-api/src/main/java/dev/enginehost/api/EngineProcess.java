package dev.enginehost.api;

import android.os.Build;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Whether the calling code is running in Enginehost's own isolated
 * runtime process (docs/engine-sandbox.md "Layer 2"), checked once, the
 * same way, by both the host and every plugin -- rather than each
 * re-deriving it and each risking the same mistake independently.
 *
 * <p>dq-sandbox-11 found exactly that mistake: a plugin's own static
 * initialiser checked {@code processName.endsWith(":runtime_isolated")}
 * directly, which is false on API 34, where Android names an isolated
 * <em>service's</em> process with the service's own component appended
 * after a second colon
 * ({@code "dev.enginehost:runtime_isolated:dev.enginehost.IsolatedRuntimeService"}),
 * not the bare suffix a lower-API device uses. The host's own
 * equivalent check had the identical bug, unnoticed until then because
 * BlueStacks (API 28) happens to name the process the simpler way.
 */
public final class EngineProcess {
    private EngineProcess() {}

    private static final String ISOLATED_SEGMENT = ":runtime_isolated";

    /**
     * {@code true} in Enginehost's own isolated runtime process, on any
     * API level this app supports (minSdk 26). Prefers
     * {@link android.os.Process#isIsolated()} where the platform has it
     * (API 34+, the exact release that also renames isolated service
     * processes the way that broke the naive check above) as the
     * authoritative, Android-maintained answer; falls back to a process
     * name check everywhere else, matching a {@code :runtime_isolated}
     * segment as either the exact suffix or followed immediately by
     * {@code :}, so it accepts both the bare form and the
     * component-appended form without needing to know which API level
     * produces which.
     */
    public static boolean isIsolated() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return android.os.Process.isIsolated();
            } catch (Throwable ignored) {
                // Fall through to the name-based check below rather than
                // treating an unexpected platform failure as a launch
                // failure -- this method only ever answers a question,
                // it never throws one.
            }
        }
        String name = currentProcessName();
        if (name == null) return false;
        int index = name.indexOf(ISOLATED_SEGMENT);
        if (index < 0) return false;
        int after = index + ISOLATED_SEGMENT.length();
        return after == name.length() || name.charAt(after) == ':';
    }

    private static String currentProcessName() {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get("/proc/self/cmdline"));
            String name = new String(bytes, StandardCharsets.UTF_8);
            int nul = name.indexOf('\0');
            return nul >= 0 ? name.substring(0, nul) : name;
        } catch (IOException e) {
            return null;
        }
    }
}
