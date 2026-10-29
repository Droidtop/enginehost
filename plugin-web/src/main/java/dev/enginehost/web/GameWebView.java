package dev.enginehost.web;

import android.annotation.SuppressLint;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/**
 * A web game in a confined browser: the one WebView setup every web core uses.
 *
 * The game is served from its own folder over a private https origin (see
 * {@link GameServer}) rather than opened as file:// URLs, so the page has a
 * real origin and stays inside its folder; file and content access are off.
 * Saves never live in the WebView: {@link LocalStorageBridge} replaces
 * localStorage with a store whose file sits in the save folder Enginehost
 * chose for this game. A link the page opens is offered by Enginehost, never
 * followed here. A core adds only what is its own: the engine check, input,
 * and its {@link GameServer.Site}.
 *
 * Options read from the game's config: javaScript (default true),
 * mediaPlaybackRequiresGesture (false), userAgent, webContentsDebugging
 * (false), allowNetwork (false) and entryPoint.
 */
public final class GameWebView {
    private final EnginePluginSession session;
    private final String tag;
    private final GameServer server;
    private final LocalStorageBridge storage;
    private WebView webView;

    private GameWebView(EnginePluginSession session, String tag, GameServer server, LocalStorageBridge storage) {
        this.session = session;
        this.tag = tag;
        this.server = server;
        this.storage = storage;
    }

    /** Serves the session's game into a WebView added to the session's display, and loads its entry page. */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public static GameWebView open(EnginePluginSession session, String tag, GameServer.Site site) throws IOException {
        File gameRoot = new File(session.gamePath()).getCanonicalFile();
        if (!gameRoot.isDirectory()) throw new IOException("The game folder is not readable");
        JSONObject options;
        try {
            options = new JSONObject(session.optionsJson() == null ? "{}" : session.optionsJson());
        } catch (org.json.JSONException error) {
            throw new IOException("The game's options are not valid JSON", error);
        }
        LocalStorageBridge storage = new LocalStorageBridge(new File(session.host().saveDirectory(), "localStorage.json"),
            (priority, message, error) -> session.host().log(priority, tag, message, error));
        GameServer server = new GameServer(gameRoot, session.execFile(), options, site);
        GameWebView game = new GameWebView(session, tag, server, storage);

        WebView webView = new WebView(session.host().context());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(options.optBoolean("javaScript", true));
        settings.setDomStorageEnabled(true); // sessionStorage; saves go through the bridge
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(options.optBoolean("mediaPlaybackRequiresGesture", false));
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        String userAgent = options.optString("userAgent", "");
        if (!userAgent.isBlank()) settings.setUserAgentString(userAgent);
        WebView.setWebContentsDebuggingEnabled(options.optBoolean("webContentsDebugging", false));
        webView.setBackgroundColor(0xFF000000);
        webView.addJavascriptInterface(storage, LocalStorageBridge.JS_NAME);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                int priority = message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? android.util.Log.ERROR : android.util.Log.DEBUG;
                session.host().log(priority, tag, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")", null);
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (server.serves(request.getUrl())) return false;
                // A link the game opens (an <a>, a location change or window.open, which stays in
                // this view): offered by Enginehost and never opened here (Enginehost docs/SPEC.md,
                // "Links a game opens"; Droidtop/tracker#635). Only a navigation the person caused
                // is offered, so a game cannot raise the sheet by itself; the view never leaves it.
                if (request.isForMainFrame() && request.hasGesture()) game.offerLink(request.getUrl().toString());
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    return server.respond(request);
                } catch (IOException error) {
                    session.host().log(android.util.Log.WARN, tag, "Could not serve " + request.getUrl(), error);
                    return GameServer.status(500, "Internal error");
                }
            }
        });
        session.display().addView(webView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        game.webView = webView;
        webView.loadUrl(server.entryUrl());
        return game;
    }

    /** The view, for a core's input; null once destroyed. */
    public WebView view() { return webView; }

    private void offerLink(String url) {
        try {
            session.host().openLink(url);
        } catch (IncompatibleClassChangeError olderHost) {
            // An Enginehost from before openLink(): the link is not opened, as before.
            session.host().log(android.util.Log.INFO, tag, "This Enginehost cannot offer a link; it was not opened", null);
        }
    }

    public void onResume() { if (webView != null) webView.onResume(); }

    public void onPause() {
        if (webView != null) webView.onPause();
        storage.flush();
    }

    public void onDestroy() {
        storage.flush();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }
}
