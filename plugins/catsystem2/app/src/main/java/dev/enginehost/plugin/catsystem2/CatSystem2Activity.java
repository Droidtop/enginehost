package dev.enginehost.plugin.catsystem2;

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
import java.util.List;

/** Experimental CST dialogue/input interpreter; unsupported commands are skipped. */
public final class CatSystem2Activity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            File script = resolveScript();
            setContentView(new ScriptView(CstScript.read(script)));
        } catch (IOException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private File resolveScript() throws IOException {
        String path = getIntent().getStringExtra("path");
        if (path == null) throw new IOException("enginehost did not provide a game folder");
        File root = new File(path).getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("CatSystem2 game folder is unreadable");
        String exec = getIntent().getStringExtra("execFile");
        if (exec != null && !exec.isBlank()) {
            File selected = new File(root, exec).getCanonicalFile();
            if (!selected.getPath().startsWith(root.getPath() + File.separator) || !selected.isFile()) {
                throw new IOException("CatSystem2 execFile is outside the game folder");
            }
            return selected;
        }
        File scene = new File(root, "scene");
        File[] scripts = (scene.isDirectory() ? scene : root)
            .listFiles((dir, name) -> name.toLowerCase().endsWith(".cst"));
        if (scripts == null || scripts.length == 0) throw new IOException("No extracted CST script found; set execFile explicitly");
        java.util.Arrays.sort(scripts);
        return scripts[0].getCanonicalFile();
    }

    private final class ScriptView extends View {
        private final List<CstScript.Line> lines;
        private final Paint speakerPaint = paint(30, 0xff80cbc4);
        private final Paint textPaint = paint(34, Color.WHITE);
        private int cursor;
        private String speaker = "";
        private String text = "";

        ScriptView(List<CstScript.Line> lines) {
            super(CatSystem2Activity.this);
            this.lines = lines;
            setBackgroundColor(Color.BLACK);
            advance();
        }

        private Paint paint(float size, int color) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(size);
            paint.setColor(color);
            return paint;
        }

        private void advance() {
            StringBuilder message = new StringBuilder();
            while (cursor < lines.size()) {
                CstScript.Line line = lines.get(cursor++);
                if (line.type() == CstScript.NAME) speaker = line.content();
                else if (line.type() == CstScript.MESSAGE) {
                    if (message.length() > 0) message.append('\n');
                    message.append(line.content());
                } else if (line.type() == CstScript.INPUT || line.type() == CstScript.PAGE) {
                    if (message.length() > 0) break;
                }
            }
            text = message.toString();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = 48, y = getHeight() * 0.62f;
            canvas.drawText(speaker, x, y, speakerPaint);
            y += 55;
            for (String paragraph : text.split("\\n", -1)) {
                for (String row : wrap(paragraph, textPaint, getWidth() - 96)) {
                    canvas.drawText(row, x, y, textPaint);
                    y += 44;
                }
            }
        }

        private java.util.List<String> wrap(String value, Paint paint, int width) {
            java.util.List<String> rows = new java.util.ArrayList<>();
            int start = 0;
            while (start < value.length()) {
                int count = paint.breakText(value, start, value.length(), true, width, null);
                if (count <= 0) break;
                rows.add(value.substring(start, start + count));
                start += count;
            }
            if (rows.isEmpty()) rows.add("");
            return rows;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) advance();
            return true;
        }
    }
}
