package dev.enginehost.web;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Serves a game folder to the WebView as an https origin of its own.
 *
 * Every request the page makes arrives through shouldInterceptRequest and
 * is answered from the game folder; nothing is opened as a file:// URL and
 * nothing outside the folder is reachable. The private origin is what makes
 * a page behave like a page: fetch works, media seeks (Range requests are
 * honoured), and the game's own scripts see a normal document. An HTML
 * document gets the localStorage script from {@link LocalStorageBridge},
 * then the core's own {@link Site#pageScript}, ahead of its own scripts.
 *
 * The one copy for every web core (Droidtop/tracker#768: the HTML and
 * RPG Maker MV/MZ cores each carried one, and they had drifted apart). A
 * core says what differs about its games through {@link Site}.
 */
public final class GameServer {
    public static final String HOST = "game.enginehost.local";
    public static final String ORIGIN = "https://" + HOST;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private static final Pattern HEAD = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOT = Pattern.compile("<html(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    /** How many names a missing-entry message lists at most. */
    private static final int LISTED = 5;
    private static final long MAX_DOCUMENT_BYTES = 64L * 1024 * 1024;
    private static final Map<String, String> MIME = new HashMap<>();

    static {
        String[][] table = {
            {"html", "text/html"}, {"htm", "text/html"}, {"js", "text/javascript"}, {"mjs", "text/javascript"},
            {"css", "text/css"}, {"json", "application/json"}, {"xml", "text/xml"}, {"txt", "text/plain"},
            {"wasm", "application/wasm"},
            {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"gif", "image/gif"},
            {"webp", "image/webp"}, {"svg", "image/svg+xml"}, {"ico", "image/x-icon"}, {"bmp", "image/bmp"},
            {"ogg", "audio/ogg"}, {"oga", "audio/ogg"}, {"m4a", "audio/mp4"}, {"mp3", "audio/mpeg"},
            {"wav", "audio/wav"}, {"flac", "audio/flac"}, {"opus", "audio/ogg"},
            {"mp4", "video/mp4"}, {"webm", "video/webm"}, {"ogv", "video/ogg"},
            {"woff", "font/woff"}, {"woff2", "font/woff2"}, {"ttf", "font/ttf"}, {"otf", "font/otf"},
            {"pdf", "application/pdf"},
        };
        for (String[] entry : table) MIME.put(entry[0], entry[1]);
    }

    /** What a core's games need that another core's do not. */
    public interface Site {
        /** The pages tried, in order, when neither execFile nor the entryPoint option names one. */
        String[] entryPages();

        /** A file to serve in place of [missing], which does not exist, or null to answer 404. */
        default File standIn(File missing) { return null; }

        /** Script run in every document after the storage script and before the page's own, or "". */
        default String pageScript() { return ""; }
    }

    private final File gameRoot;
    private final Site site;
    private final boolean allowNetwork;
    private final File entry;
    private final String headTag;

    public GameServer(File gameRoot, String execFile, JSONObject options, Site site) throws IOException {
        this.gameRoot = gameRoot.getCanonicalFile();
        this.site = site;
        this.allowNetwork = options.optBoolean("allowNetwork", false);
        this.entry = resolvePage(execFile, options.optString("entryPoint", ""));
        this.headTag = "<script>" + LocalStorageBridge.script() + site.pageScript() + "</script>";
    }

    public String entryUrl() { return ORIGIN + urlPath(entry); }

    /** Whether the page may navigate to [uri] at all. */
    public boolean serves(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || "about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)) return true;
        if ("https".equals(scheme) && HOST.equals(uri.getHost())) return true;
        return allowNetwork && ("http".equals(scheme) || "https".equals(scheme));
    }

    /** The response for one request, or null to let the WebView fetch it itself (permitted network only). */
    public WebResourceResponse respond(WebResourceRequest request) throws IOException {
        Uri uri = request.getUrl();
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) {
            if (serves(uri) && !"about".equals(uri.getScheme())) return null;
            return status(403, "Blocked");
        }
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        File file = requested(path);
        if (file == null) return status(403, "Outside the game folder");
        if (file.isDirectory()) file = new File(file, "index.html");
        if (!file.isFile()) {
            file = site.standIn(file);
            if (file == null || !file.isFile()) return status(404, "Not found");
        }
        String mime = mimeFor(file.getName());
        if ("text/html".equals(mime)) return document(file);
        return fileResponse(file, mime, request.getRequestHeaders().get("Range"));
    }

    /** An HTML document with the head scripts placed before anything of the page's own runs. */
    private WebResourceResponse document(File file) throws IOException {
        String html = new String(readAll(file), StandardCharsets.UTF_8);
        return textResponse("text/html", inject(html, headTag));
    }

    static String inject(String html, String tag) {
        Matcher head = HEAD.matcher(html);
        if (head.find()) return html.substring(0, head.end()) + tag + html.substring(head.end());
        Matcher root = ROOT.matcher(html);
        if (root.find()) return html.substring(0, root.end()) + tag + html.substring(root.end());
        return tag + html;
    }

    private File resolvePage(String execFile, String entryPoint) throws IOException {
        if (execFile != null && !execFile.isBlank()) return entryFile(execFile);
        if (!entryPoint.isBlank()) return entryFile(entryPoint);
        for (String candidate : site.entryPages()) {
            File file = confine(candidate);
            if (file != null && file.isFile()) return file;
        }
        File[] pages = gameRoot.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".html"));
        if (pages != null && pages.length > 0) {
            Arrays.sort(pages);
            return pages[0].getCanonicalFile();
        }
        throw new IOException("No HTML page found at the game folder's root; set execFile to the page to open");
    }

    /**
     * The configured entry page, or an error that says which of the three
     * things is wrong: the path leaves the game folder, it names a folder,
     * or there is no such file. Those used to share one sentence ("is not
     * inside the game folder"), which for the common case, a page that is
     * simply missing, pointed at the wrong problem (rig, 2026-09-24: the
     * folder held only a Syncthing conflict copy of the page).
     */
    private File entryFile(String relative) throws IOException {
        if (new File(relative).isAbsolute()) throw new IOException("The entry file must be relative to the game folder");
        File file = confine(relative);
        if (file == null || file.equals(gameRoot)) {
            throw new IOException("The entry file " + relative + " points outside the game folder");
        }
        if (file.isDirectory()) throw new IOException("The entry file " + relative + " is a folder, not a page");
        if (!file.isFile()) throw new IOException(missingEntry(relative, file));
        return file;
    }

    /**
     * Says the entry page is missing, naming what the folder it should be in
     * does hold: files whose names start like the page's (a renamed or
     * conflict copy is the usual reason a page goes missing), else the pages
     * that are there, so the fix is in the sentence.
     */
    private static String missingEntry(String relative, File expected) {
        String message = "Cannot find the entry file " + relative + " in the game folder";
        File folder = expected.getParentFile();
        String[] names = folder == null ? null : folder.list();
        if (names == null) return message + ".";
        Arrays.sort(names);
        String wanted = expected.getName().toLowerCase(Locale.ROOT);
        int dot = wanted.lastIndexOf('.');
        String stem = dot > 0 ? wanted.substring(0, dot) : wanted;
        StringBuilder similar = new StringBuilder();
        StringBuilder pages = new StringBuilder();
        int similarCount = 0;
        int pageCount = 0;
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(stem) && similarCount < LISTED) {
                similar.append(similarCount++ == 0 ? "" : ", ").append(name);
            }
            if ((lower.endsWith(".html") || lower.endsWith(".htm")) && pageCount < LISTED) {
                pages.append(pageCount++ == 0 ? "" : ", ").append(name);
            }
        }
        if (similarCount > 0) return message + ". Files there with a similar name: " + similar + ".";
        if (pageCount > 0) return message + ". Pages there: " + pages + ".";
        return message + ". The folder it should be in holds no HTML page.";
    }

    /** The file a URL path names, or null when a segment is not a plain name or it leaves the game folder. */
    private File requested(String path) {
        StringBuilder relative = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            String decoded = Uri.decode(segment);
            if (decoded.equals(".") || decoded.equals("..") || decoded.contains("/") || decoded.contains("\\")) return null;
            if (relative.length() > 0) relative.append(File.separatorChar);
            relative.append(decoded);
        }
        return confine(relative.toString());
    }

    /**
     * The canonical file [relative] names inside the game folder (the folder
     * itself for ""), or null when it resolves outside it. The one
     * confinement check: request paths and entry pages both come through here.
     */
    private File confine(String relative) {
        try {
            File canonical = new File(gameRoot, relative).getCanonicalFile();
            if (canonical.equals(gameRoot) || canonical.getPath().startsWith(gameRoot.getPath() + File.separator)) {
                return canonical;
            }
            return null;
        } catch (IOException error) {
            return null;
        }
    }

    private String urlPath(File file) {
        String relative = file.getPath().substring(gameRoot.getPath().length()).replace(File.separatorChar, '/');
        StringBuilder encoded = new StringBuilder();
        for (String segment : relative.split("/")) {
            if (!segment.isEmpty()) encoded.append('/').append(Uri.encode(segment));
        }
        return encoded.toString();
    }

    static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.get(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    static WebResourceResponse textResponse(String mime, String body) {
        return new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    public static WebResourceResponse status(int code, String reason) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "UTF-8", code, reason, headers,
            new ByteArrayInputStream(reason.getBytes(StandardCharsets.UTF_8)));
    }

    /** A whole file, or the byte range a media element asked for. */
    static WebResourceResponse fileResponse(File file, String mime, String range) throws IOException {
        long length = file.length();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        long[] span = range == null || length <= 0 ? null : span(range.trim(), length);
        if (span != null) {
            if (span.length == 0) {
                headers.put("Content-Range", "bytes */" + length);
                return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, new ByteArrayInputStream(new byte[0]));
            }
            long start = span[0];
            long end = span[1];
            FileInputStream input = new FileInputStream(file);
            skipFully(input, start);
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            headers.put("Content-Length", Long.toString(end - start + 1));
            return new WebResourceResponse(mime, null, 206, "Partial Content", headers, new BoundedInputStream(input, end - start + 1));
        }
        headers.put("Content-Length", Long.toString(length));
        return new WebResourceResponse(mime, null, 200, "OK", headers, new FileInputStream(file));
    }

    /**
     * The first and last byte [range] asks for out of [length]: null when it
     * is not a single byte range (the whole file is served), an empty array
     * when it cannot be satisfied. A number too long for a long is past any
     * file's end, not an exception: the page sets this header, and an
     * uncaught NumberFormatException here would end the game.
     */
    static long[] span(String range, long length) {
        Matcher matcher = RANGE.matcher(range);
        if (!matcher.matches()) return null;
        String first = matcher.group(1);
        String last = matcher.group(2);
        if (first.isEmpty() && last.isEmpty()) return null;
        long start;
        long end;
        if (first.isEmpty()) {
            // A suffix: the last [last] bytes.
            long suffix = parse(last);
            if (suffix == 0) return new long[0];
            start = suffix >= length ? 0 : length - suffix;
            end = length - 1;
        } else {
            start = parse(first);
            end = last.isEmpty() ? length - 1 : Math.min(parse(last), length - 1);
        }
        return start > end || start >= length ? new long[0] : new long[] {start, end};
    }

    /** A run of digits as a long, or Long.MAX_VALUE when it does not fit. */
    private static long parse(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) break;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    public static byte[] readAll(File file) throws IOException {
        long length = file.length();
        if (length > MAX_DOCUMENT_BYTES) throw new IOException(file.getName() + " is too large to read as a document");
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return bytes;
    }

    /** Reads at most the given number of bytes of the wrapped stream. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream input, long limit) {
            super(input);
            remaining = limit;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
