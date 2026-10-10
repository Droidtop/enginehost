package dev.enginehost.libretro;

import android.os.ParcelFileDescriptor;
import dev.enginehost.api.EngineControllerEvent;
import dev.enginehost.api.EngineHost;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import dev.enginehost.api.EngineStepDriven;
import java.io.IOException;
import java.util.Map;

/**
 * An engine with a libretro core, run in Enginehost's sandbox
 * (enginehost_libretro.c beside this file; Enginehost docs/engine-sandbox.md
 * "Engines with a libretro core"). A plugin subclasses this, says which
 * contexts it runs and what file the core loads, and its bundle names the
 * subclass as its entry point; the core, this frontend and Enginehost's file
 * layer are one native library the isolated runtime loads.
 *
 * <p>Copied verbatim from Enginehost's plugin-native/libretro/; change it
 * there.
 */
public abstract class LibretroPlugin implements EnginePlugin, EngineStepDriven {
    // RETRO_DEVICE_ID_JOYPAD_* (libretro.h).
    protected static final int JOYPAD_B = 0;
    protected static final int JOYPAD_Y = 1;
    protected static final int JOYPAD_SELECT = 2;
    protected static final int JOYPAD_START = 3;
    protected static final int JOYPAD_UP = 4;
    protected static final int JOYPAD_DOWN = 5;
    protected static final int JOYPAD_LEFT = 6;
    protected static final int JOYPAD_RIGHT = 7;
    protected static final int JOYPAD_A = 8;
    protected static final int JOYPAD_X = 9;
    protected static final int JOYPAD_L = 10;
    protected static final int JOYPAD_R = 11;
    protected static final int JOYPAD_L2 = 12;
    protected static final int JOYPAD_R2 = 13;
    protected static final int JOYPAD_L3 = 14;
    protected static final int JOYPAD_R3 = 15;

    private long core;

    /** Whether this plugin runs the session's engine context at all. */
    protected abstract boolean supports(String engineContext);

    /** The path retro_load_game is given: the game folder, or a file in it. */
    protected abstract String contentPath(EnginePluginSession session) throws IOException;

    /** Core options set before the core starts, over the core's own defaults. */
    protected Map<String, String> options(EnginePluginSession session) {
        return Map.of();
    }

    /**
     * The RetroPad button a host action presses, or -1. The default reads
     * the host's own pad vocabulary and its common one alike: confirm is A,
     * cancel is B, menu is Start, history Select, skip X, auto Y, the page
     * actions L and R.
     */
    protected int joypadButton(String action) {
        switch (action) {
            case "up": case "pad_dpup": return JOYPAD_UP;
            case "down": case "pad_dpdown": return JOYPAD_DOWN;
            case "left": case "pad_dpleft": return JOYPAD_LEFT;
            case "right": case "pad_dpright": return JOYPAD_RIGHT;
            case "confirm": case "pad_a": return JOYPAD_A;
            case "cancel": case "pad_b": return JOYPAD_B;
            case "skip": case "pad_x": return JOYPAD_X;
            case "auto": case "pad_y": return JOYPAD_Y;
            case "menu": case "pad_start": return JOYPAD_START;
            case "history": case "pad_back": return JOYPAD_SELECT;
            case "page_previous": case "quick_save": case "pad_leftshoulder": return JOYPAD_L;
            case "page_next": case "quick_load": case "pad_rightshoulder": return JOYPAD_R;
            default: return -1;
        }
    }

    @Override
    public void onCreate(EnginePluginSession session) throws Exception {
        if (!supports(session.engineContext())) {
            throw new IOException("Unsupported " + session.engine() + " context " + session.engineContext());
        }
        if (session.display() != null) {
            throw new IOException("This engine runs only in Enginehost's sandbox");
        }
        EngineHost host = session.host();
        Map<String, String> options = options(session);
        String[] keys = options.keySet().toArray(new String[0]);
        String[] values = new String[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = options.get(keys[i]);
        String save = host.saveDirectory() == null ? null : host.saveDirectory().getAbsolutePath();
        ParcelFileDescriptor ring = host.isolatedAudioBuffer();
        core = nativeStart(contentPath(session), session.gamePath(), save, keys, values,
                ring == null ? -1 : ring.getFd(), host.isolatedAudioSampleRate());
        if (ring != null) ring.close();
        if (core == 0) throw new IOException(nativeError());
    }

    @Override public int pixelWidth() { return core == 0 ? 0 : nativeWidth(core); }
    @Override public int pixelHeight() { return core == 0 ? 0 : nativeHeight(core); }
    @Override public int step(int[] pixels) { return core == 0 ? -1 : nativeStep(core, pixels); }

    @Override public void onPointerMove(int x, int y) {
        if (core != 0) nativePointer(core, x, y, true);
    }

    @Override public void onPointerUp(int x, int y) {
        if (core != 0) nativePointer(core, x, y, false);
    }

    @Override
    public boolean onControllerEvent(EngineControllerEvent event) {
        if (core == 0) return false;
        int button = joypadButton(event.action());
        if (button < 0) return false;
        nativeJoypad(core, button, event.pressed());
        return true;
    }

    @Override
    public void onDestroy() {
        if (core != 0) nativeStop(core);
        core = 0;
    }

    /** For a subclass whose engine reads a keyboard (RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK): a retro_key and its character. */
    protected final void key(int retroKey, int character, boolean down) {
        if (core != 0) nativeKey(core, retroKey, character, down);
    }

    /** For a subclass whose engine reads a mouse: button 0 left, 1 right; wheel steps up are positive. */
    protected final void mouseButton(int button, boolean down) {
        if (core != 0) nativeMouse(core, button, down);
    }

    protected final void mouseWheel(int steps) {
        if (core != 0) nativeWheel(core, steps);
    }

    static native long nativeStart(String content, String systemDir, String saveDir,
                                   String[] optionKeys, String[] optionValues, int audioRingFd, int audioSampleRate);
    static native String nativeError();
    static native int nativeWidth(long core);
    static native int nativeHeight(long core);
    static native int nativeStep(long core, int[] pixels);
    static native void nativeJoypad(long core, int id, boolean down);
    static native void nativePointer(long core, int x, int y, boolean held);
    static native void nativeMouse(long core, int button, boolean down);
    static native void nativeWheel(long core, int steps);
    static native void nativeKey(long core, int keycode, int character, boolean down);
    static native void nativeStop(long core);
}
