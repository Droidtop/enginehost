package dev.enginehost.plugin.web;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

abstract class GameWebActivity extends Activity {
    protected WebView webView;
    protected File gameRoot;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra("path");
        try {
            if (path == null) throw new IOException("enginehost did not provide a game folder");
            gameRoot = new File(path).getCanonicalFile();
            if (!gameRoot.isDirectory()) throw new IOException("enginehost game folder is not readable");
        } catch (IOException exception) {
            fail(exception.getMessage());
            return;
        }

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);

        try {
            loadGame();
        } catch (Exception exception) {
            fail(exception.getMessage());
        }
    }

    protected abstract void loadGame() throws Exception;

    protected File confinedFile(String requested) throws IOException {
        File file = new File(gameRoot, requested).getCanonicalFile();
        String prefix = gameRoot.getPath() + File.separator;
        if (!file.getPath().startsWith(prefix) || !file.isFile()) {
            throw new IOException("Requested entry file is not inside the game folder");
        }
        return file;
    }

    protected void fail(String message) {
        Toast.makeText(this, message == null ? "Runtime failed" : message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
