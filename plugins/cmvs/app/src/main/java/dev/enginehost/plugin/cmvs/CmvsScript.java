package dev.enginehost.plugin.cmvs;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Bounds-checked reader for CMVS PS2A/PS2 and PS3 dialogue data. */
final class CmvsScript {
    private static final int HEADER = 0x30;
    private static final int MAX = 128 * 1024 * 1024;

    static List<String> read(File file) throws IOException {
        if (file.length() < HEADER || file.length() > MAX) throw new IOException("Unsafe CMVS script size");
        byte[] source = Files.readAllBytes(file.toPath());
        if (source[0] != 'P' || source[1] != 'S' ||
            source[2] != '2' || source[3] != 'A') throw new IOException("Not a supported CMVS PS2A script");
        ByteBuffer header = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(4) != HEADER) throw new IOException("Unsupported CMVS header size");
        int indexCount = positive(header.getInt(16), "index count");
        int bytecodeLength = positive(header.getInt(20), "bytecode length");
        int compressed = positive(header.getInt(36), "compressed size");
        int decompressed = positive(header.getInt(40), "decompressed size");
        if (compressed > 0) {
            if (decompressed > MAX - HEADER || HEADER + (long) compressed > source.length)
                throw new IOException("Invalid compressed CMVS PS2 stream");
            byte[] decoded = decrypt(source, HEADER, compressed, header.getInt(12));
            byte[] expanded = lzss(decoded, decompressed);
            source = new byte[HEADER + expanded.length];
            System.arraycopy(header.array(), 0, source, 0, HEADER);
            System.arraycopy(expanded, 0, source, HEADER, expanded.length);
        }
        long bytecodeStart = HEADER + indexCount * 4L;
        long stringsStart = bytecodeStart + bytecodeLength;
        if (bytecodeStart > source.length || stringsStart > source.length) throw new IOException("Truncated CMVS tables");
        String extension = file.getName().toLowerCase();
        if (extension.endsWith(".ps2")) return readStringPool(source, checked(stringsStart, source.length));
        return readPs3References(source, checked(bytecodeStart, source.length), bytecodeLength,
            checked(stringsStart, source.length));
    }

    private static List<String> readPs3References(byte[] data, int codeStart, int codeLength, int stringsStart)
        throws IOException {
        if ((long) codeStart + codeLength > data.length) throw new IOException("Truncated CMVS bytecode");
        List<String> result = new ArrayList<>();
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int p = codeStart; p + 8 <= codeStart + codeLength; p++) {
            if (bytes.getInt(p) == 0x01200201) {
                long address = stringsStart + Integer.toUnsignedLong(bytes.getInt(p + 4));
                if (address < stringsStart || address >= data.length) throw new IOException("Invalid CMVS string reference");
                result.add(readCString(data, (int) address));
                p += 7;
            }
        }
        if (result.isEmpty()) return readStringPool(data, stringsStart);
        return result;
    }

    private static List<String> readStringPool(byte[] data, int start) {
        List<String> result = new ArrayList<>();
        for (int p = start; p < data.length;) {
            int end = p;
            while (end < data.length && data[end] != 0) end++;
            if (end > p) result.add(new String(data, p, end - p, Charset.forName("Shift_JIS")));
            p = end + 1;
        }
        return result;
    }

    private static String readCString(byte[] data, int start) {
        int end = start;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, start, end - start, Charset.forName("Shift_JIS"));
    }

    private static byte[] decrypt(byte[] source, int start, int length, int key) {
        byte[] output = new byte[length];
        int xor = ((key >>> 24) + (key >>> 3)) & 0xff;
        int shift = ((key >>> 20) % 5) + 1;
        for (int i = 0; i < length; i++) {
            int value = ((source[start + i] & 0xff) - 0x7c) & 0xff;
            value ^= xor;
            output[i] = (byte) (((value >>> shift) | (value << (8 - shift))) & 0xff);
        }
        return output;
    }

    private static byte[] lzss(byte[] input, int expected) throws IOException {
        byte[] output = new byte[expected];
        byte[] window = new byte[2048];
        int in = 0, out = 0, flags = 0, windowPosition = 0x7df;
        while (in < input.length && out < expected) {
            flags >>>= 1;
            if ((flags & 0x100) == 0) flags = (input[in++] & 0xff) | 0xff00;
            if ((flags & 1) != 0) {
                if (in >= input.length) break;
                byte value = input[in++]; output[out++] = value;
                window[windowPosition++ & 0x7ff] = value;
            } else {
                if (in + 1 >= input.length) break;
                int offset = input[in++] & 0xff;
                int count = input[in++] & 0xff;
                offset |= (count & 0xe0) << 3;
                count = (count & 0x1f) + 2;
                for (int i = 0; i < count && out < expected; i++) {
                    byte value = window[(offset + i) & 0x7ff]; output[out++] = value;
                    window[windowPosition++ & 0x7ff] = value;
                }
            }
        }
        if (out != expected) throw new IOException("CMVS decompressed size mismatch");
        return output;
    }

    private static int positive(int value, String label) throws IOException {
        if (value < 0) throw new IOException("Invalid CMVS " + label);
        return value;
    }

    private static int checked(long value, int limit) throws IOException {
        if (value < 0 || value > limit) throw new IOException("CMVS offset is outside the script");
        return (int) value;
    }
}
