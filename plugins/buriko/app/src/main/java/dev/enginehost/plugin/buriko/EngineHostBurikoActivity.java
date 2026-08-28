package dev.enginehost.plugin.buriko;

import android.os.Bundle;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;

/** SDL entry point for the native OpenBGI interpreter. */
public final class EngineHostBurikoActivity extends SDLActivity {
    private String gamePath;

    @Override
    protected void onCreate(Bundle state) {
        String path = getIntent().getStringExtra("path");
        if (path == null || !new File(path).isDirectory()) {
            Toast.makeText(this, "enginehost did not provide a readable BGI folder", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        gamePath = new File(path).getAbsolutePath();
        super.onCreate(state);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected String[] getArguments() {
        return new String[] { gamePath };
    }
}
