package dev.enginehost.plugin.godot;

import org.godotengine.godot.GodotActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Hosts the official Godot Android runtime around an in-place project/PCK. */
public final class EngineHostGodotActivity extends GodotActivity {
    @Override
    public List<String> getCommandLine() {
        List<String> arguments = new ArrayList<>(super.getCommandLine());
        try {
            File root = gameRoot();
            File pack = selectedPack(root);
            if (pack != null) {
                arguments.add("--main-pack");
                arguments.add(pack.getAbsolutePath());
            } else if (new File(root, "project.godot").isFile()) {
                arguments.add("--path");
                arguments.add(root.getAbsolutePath());
            } else {
                throw new IOException("Godot game folder has no project.godot or selected PCK/ZIP");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        return arguments;
    }

    private File gameRoot() throws IOException {
        String path = getIntent().getStringExtra("path");
        if (path == null) throw new IOException("enginehost did not provide a game folder");
        File root = new File(path).getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("Godot game folder is not readable");
        return root;
    }

    private File selectedPack(File root) throws IOException {
        String execFile = getIntent().getStringExtra("execFile");
        if (execFile != null && !execFile.trim().isEmpty()) return confined(root, execFile);
        File[] packs = root.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".pck") || lower.endsWith(".zip");
        });
        if (packs != null && packs.length == 1) return packs[0].getCanonicalFile();
        return null;
    }

    private File confined(File root, String relative) throws IOException {
        File file = new File(root, relative).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile()) {
            throw new IOException("Godot execFile is not inside the game folder");
        }
        return file;
    }
}
