package dev.enginehost.plugin.cmvs;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Experimental PS2/PS3 dialogue presenter; general CMVS opcodes are not yet executed. */
public final class CmvsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { setContentView(new DialogueView(CmvsScript.read(resolveScript()))); }
        catch (IOException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); finish(); }
    }

    private File resolveScript() throws IOException {
        String path = getIntent().getStringExtra("path");
        if (path == null) throw new IOException("enginehost did not provide a game folder");
        File root = new File(path).getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("CMVS game folder is unreadable");
        String exec = getIntent().getStringExtra("execFile");
        if (exec != null && !exec.isBlank()) {
            File selected = new File(root, exec).getCanonicalFile();
            if (!selected.getPath().startsWith(root.getPath() + File.separator) || !selected.isFile())
                throw new IOException("CMVS execFile is outside the game folder");
            return selected;
        }
        File[] scripts = root.listFiles((dir, name) -> name.toLowerCase().endsWith(".ps2") || name.toLowerCase().endsWith(".ps3"));
        if (scripts == null || scripts.length == 0) throw new IOException("No extracted CMVS PS2/PS3 script found; set execFile explicitly");
        Arrays.sort(scripts);
        return scripts[0].getCanonicalFile();
    }

    private final class DialogueView extends View {
        private final List<String> strings;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int cursor;
        DialogueView(List<String> strings) { super(CmvsActivity.this); this.strings = strings; paint.setColor(Color.WHITE); paint.setTextSize(34); setBackgroundColor(Color.BLACK); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            String value = strings.isEmpty() ? "No dialogue references found" : strings.get(Math.min(cursor, strings.size() - 1));
            float y = getHeight() * .65f;
            for (int start = 0; start < value.length();) {
                int count = paint.breakText(value, start, value.length(), true, getWidth() - 96, null);
                if (count <= 0) break;
                canvas.drawText(value, start, start + count, 48, y, paint); start += count; y += 44;
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && cursor + 1 < strings.size()) { cursor++; invalidate(); }
            return true;
        }
    }
}
