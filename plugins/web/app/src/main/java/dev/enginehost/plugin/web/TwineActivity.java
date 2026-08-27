package dev.enginehost.plugin.web;

import java.io.File;
import java.io.IOException;

/** Runs a compiled, self-contained Twine story directly from its live folder. */
public final class TwineActivity extends GameWebActivity {
    @Override
    protected void loadGame() throws IOException {
        String requested = getIntent().getStringExtra("execFile");
        File story;
        if (requested != null && !requested.trim().isEmpty()) {
            story = confinedFile(requested);
        } else {
            story = findCompiledStory();
        }
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.loadUrl(story.toURI().toString());
    }

    private File findCompiledStory() throws IOException {
        File index = new File(gameRoot, "index.html");
        if (index.isFile()) return index.getCanonicalFile();
        File[] files = gameRoot.listFiles((dir, name) -> name.toLowerCase().endsWith(".html"));
        if (files != null && files.length == 1) return files[0].getCanonicalFile();
        throw new IOException("Set execFile to the compiled Twine HTML story");
    }
}
