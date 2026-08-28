package dev.enginehost.plugin.catsystem2;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.InflaterInputStream;

/** Minimal, bounds-checked reader for CatSystem2 CatScene/CST scripts. */
final class CstScript {
    static final int INPUT = 0x02;
    static final int PAGE = 0x03;
    static final int MESSAGE = 0x20;
    static final int NAME = 0x21;
    static final int COMMAND = 0x30;

    record Line(int type, String content) {}

    static List<Line> read(File file) throws IOException {
        byte[] container = readAll(file);
        if (container.length < 16 || !"CatScene".equals(new String(container, 0, 8, Charset.forName("US-ASCII")))) {
            throw new IOException("Not a CatSystem2 CatScene script: " + file.getName());
        }
        ByteBuffer header = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN);
        int compressedSize = header.getInt(8);
        int decompressedSize = header.getInt(12);
        if (decompressedSize <= 0 || decompressedSize > 128 * 1024 * 1024) {
            throw new IOException("Unsafe CST decompressed size");
        }
        byte[] script;
        if (compressedSize == 0) {
            if (16L + decompressedSize > container.length) throw new IOException("Truncated CST data");
            script = Arrays.copyOfRange(container, 16, 16 + decompressedSize);
        } else {
            if (compressedSize < 0 || 16L + compressedSize > container.length) throw new IOException("Truncated CST stream");
            script = inflate(Arrays.copyOfRange(container, 16, 16 + compressedSize), decompressedSize);
        }
        return parseLines(script);
    }

    private static List<Line> parseLines(byte[] data) throws IOException {
        if (data.length < 16) throw new IOException("Truncated CST script header");
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int offsetTable = buffer.getInt(8);
        int stringTable = buffer.getInt(12);
        long offsetsStart = 16L + offsetTable;
        long stringsStart = 16L + stringTable;
        if (offsetsStart < 16 || stringsStart < offsetsStart || stringsStart > data.length ||
            (stringsStart - offsetsStart) % 4 != 0) throw new IOException("Invalid CST table layout");
        int count = (int) ((stringsStart - offsetsStart) / 4);
        if (count > 1_000_000) throw new IOException("CST line table is too large");

        List<Line> lines = new ArrayList<>(count);
        Charset shiftJis = Charset.forName("Shift_JIS");
        for (int index = 0; index < count; index++) {
            int relative = buffer.getInt((int) offsetsStart + index * 4);
            long position = stringsStart + Integer.toUnsignedLong(relative);
            if (position + 2 > data.length || data[(int) position] != 0x01) continue;
            int type = Byte.toUnsignedInt(data[(int) position + 1]);
            int start = (int) position + 2;
            int end = start;
            while (end < data.length && data[end] != 0) end++;
            String content = new String(data, start, end - start, shiftJis);
            lines.add(new Line(type, content));
        }
        return lines;
    }

    private static byte[] inflate(byte[] input, int expected) throws IOException {
        try (InflaterInputStream inflater = new InflaterInputStream(new java.io.ByteArrayInputStream(input));
             ByteArrayOutputStream output = new ByteArrayOutputStream(expected)) {
            byte[] chunk = new byte[8192];
            int total = 0;
            for (int count; (count = inflater.read(chunk)) >= 0;) {
                total += count;
                if (total > expected || total > 128 * 1024 * 1024) throw new IOException("CST stream exceeds declared size");
                output.write(chunk, 0, count);
            }
            if (total != expected) throw new IOException("CST decompressed size mismatch");
            return output.toByteArray();
        }
    }

    private static byte[] readAll(File file) throws IOException {
        if (file.length() > 128L * 1024 * 1024) throw new IOException("CST file is too large");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] chunk = new byte[8192];
            for (int count; (count = input.read(chunk)) >= 0;) output.write(chunk, 0, count);
            return output.toByteArray();
        }
    }
}
