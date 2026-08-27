package dev.enginehost.plugin.web;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs SWF content, including AIR captive-runtime content, with Ruffle Web. */
public final class FlashActivity extends GameWebActivity {
    private static final Pattern AIR_CONTENT = Pattern.compile("<content>\\s*([^<]+)\\s*</content>");

    @Override
    protected void loadGame() throws Exception {
        File swf = resolveSwf();
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        String swfUrl = JSONObject.quote(swf.toURI().toString());
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<style>html,body,#player{margin:0;width:100%;height:100%;background:#000}</style>" +
            "<div id=player></div><script src='file:///android_asset/ruffle/ruffle.js'></script>" +
            "<script>window.addEventListener('load',async()=>{" +
            "const p=window.RufflePlayer.newest().createPlayer();" +
            "document.getElementById('player').appendChild(p);await p.load({url:" + swfUrl + "});});</script>";
        webView.loadDataWithBaseURL(gameRoot.toURI().toString(), html, "text/html", "UTF-8", null);
    }

    private File resolveSwf() throws IOException {
        String requested = getIntent().getStringExtra("execFile");
        if (requested != null && !requested.trim().isEmpty()) return confinedFile(requested);

        File descriptor = new File(gameRoot, "META-INF/AIR/application.xml");
        if (descriptor.isFile()) {
            byte[] bytes = new byte[(int) descriptor.length()];
            try (FileInputStream input = new FileInputStream(descriptor)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
            }
            String xml = new String(bytes, StandardCharsets.UTF_8);
            Matcher matcher = AIR_CONTENT.matcher(xml);
            if (matcher.find()) return confinedFile(matcher.group(1).trim());
        }

        File[] files = gameRoot.listFiles((dir, name) -> name.toLowerCase().endsWith(".swf"));
        if (files != null && files.length == 1) return files[0].getCanonicalFile();
        throw new IOException("Set execFile to the Flash/AIR application's main SWF");
    }
}
