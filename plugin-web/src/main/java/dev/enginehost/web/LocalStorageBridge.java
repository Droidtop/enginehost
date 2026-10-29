package dev.enginehost.web;

import android.os.Handler;
import android.os.HandlerThread;
import android.webkit.JavascriptInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The page's localStorage, kept on disk in the game's save folder.
 *
 * A WebView keeps localStorage in the app's private data, where it cannot
 * be backed up, moved to another device, or found by the person who owns
 * the saves; and every page served from the same origin shares one store,
 * so two games would overwrite each other. {@link GameServer} injects a
 * script before the page's first script runs that replaces
 * window.localStorage with an object calling these methods. Calls are
 * synchronous, so the page sees ordinary Storage semantics; writes are
 * coalesced and land in localStorage.json a moment later, and always on pause.
 * {@link GameWebView} hands it to the page.
 */
public final class LocalStorageBridge {
    public static final String JS_NAME = "EnginehostStorage";
    private static final long FLUSH_DELAY_MS = 250;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    public interface Logger { void log(int priority, String message, Throwable error); }

    private final File file;
    private final Logger logger;
    private final Map<String, String> entries = new LinkedHashMap<>();
    private final Handler writer;
    private boolean dirty;
    private final Runnable flushTask = this::flush;

    public LocalStorageBridge(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        HandlerThread thread = new HandlerThread("enginehost-web-saves");
        thread.start();
        writer = new Handler(thread.getLooper());
        read();
    }

    private void read() {
        if (!file.isFile()) return;
        try {
            if (file.length() > MAX_FILE_BYTES) throw new IOException("localStorage.json is too large to be a save file");
            JSONObject json = new JSONObject(new String(GameServer.readAll(file), StandardCharsets.UTF_8));
            for (Iterator<String> keys = json.keys(); keys.hasNext();) {
                String key = keys.next();
                entries.put(key, json.getString(key));
            }
        } catch (IOException | JSONException error) {
            logger.log(android.util.Log.ERROR, "Could not read " + file.getName() + "; starting with no saved data", error);
        }
    }

    /** Everything stored, as one JSON object, for the page to start from. */
    @JavascriptInterface public synchronized String load() { return snapshot().toString(); }

    @JavascriptInterface public synchronized String getItem(String key) { return entries.get(key); }

    @JavascriptInterface public synchronized void setItem(String key, String value) {
        if (key == null) return;
        entries.put(key, value == null ? "null" : value);
        markDirty();
    }

    @JavascriptInterface public synchronized void removeItem(String key) {
        if (entries.remove(key) != null) markDirty();
    }

    @JavascriptInterface public synchronized void clear() {
        if (entries.isEmpty()) return;
        entries.clear();
        markDirty();
    }

    private void markDirty() {
        dirty = true;
        writer.removeCallbacks(flushTask);
        writer.postDelayed(flushTask, FLUSH_DELAY_MS);
    }

    private JSONObject snapshot() {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (JSONException ignored) {
                // A value JSONObject refuses cannot round-trip; nothing to store.
            }
        }
        return json;
    }

    /** Write the store to disk now if anything changed. Safe from any thread. */
    public void flush() {
        String content;
        synchronized (this) {
            if (!dirty) return;
            content = snapshot().toString();
            dirty = false;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            logger.log(android.util.Log.ERROR, "Could not create the save folder " + parent, null);
            return;
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (!temporary.renameTo(file)) throw new IOException("rename failed");
        } catch (IOException error) {
            logger.log(android.util.Log.ERROR, "Could not write " + file, error);
            synchronized (this) { dirty = true; }
        }
    }

    /**
     * The script that makes the page's localStorage this store. Injected at
     * the top of every HTML document served, ahead of any game script. It
     * mirrors the store into a plain object so reads never cross the bridge,
     * and forwards every write.
     */
    static String script() {
        return "(function(){\n"
            + "var bridge=window." + JS_NAME + ";if(!bridge)return;\n"
            + "var data={};try{data=JSON.parse(bridge.load()||'{}')||{};}catch(e){data={};}\n"
            + "function own(k){return Object.prototype.hasOwnProperty.call(data,k);}\n"
            + "var store={\n"
            + " key:function(i){var k=Object.keys(data);return i>=0&&i<k.length?k[i]:null;},\n"
            + " getItem:function(k){k=String(k);return own(k)?data[k]:null;},\n"
            + " setItem:function(k,v){k=String(k);v=String(v);data[k]=v;bridge.setItem(k,v);},\n"
            + " removeItem:function(k){k=String(k);if(own(k)){delete data[k];bridge.removeItem(k);}},\n"
            + " clear:function(){data={};bridge.clear();}\n"
            + "};\n"
            + "Object.defineProperty(store,'length',{get:function(){return Object.keys(data).length;}});\n"
            + "var proxy=new Proxy(store,{\n"
            + " get:function(t,p){if(p in t){var v=t[p];return typeof v==='function'?v.bind(t):v;}\n"
            + "  return typeof p==='string'&&own(p)?data[p]:undefined;},\n"
            + " set:function(t,p,v){if(typeof p==='string')t.setItem(p,v);return true;},\n"
            + " deleteProperty:function(t,p){if(typeof p==='string')t.removeItem(p);return true;},\n"
            + " has:function(t,p){return (p in t)||(typeof p==='string'&&own(p));},\n"
            + " ownKeys:function(t){return Object.keys(data);},\n"
            + " getOwnPropertyDescriptor:function(t,p){if(typeof p==='string'&&own(p))return{value:data[p],writable:true,enumerable:true,configurable:true};return undefined;}\n"
            + "});\n"
            + "try{Object.defineProperty(window,'localStorage',{get:function(){return proxy;},configurable:true});}catch(e){}\n"
            + "})();\n";
    }
}
