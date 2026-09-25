package dev.enginehost.api;

import android.os.ParcelFileDescriptor;
import java.io.IOException;

/**
 * Host-brokered file access for a plugin whose runtime is isolated
 * (android:isolatedProcess, docs/engine-sandbox.md "Layer 2"). The isolated
 * process holds no storage permission of its own -- MANAGE_EXTERNAL_STORAGE
 * belongs to the main UID, not this one -- so every open crosses to the
 * host process over Binder, which resolves and opens the real file with
 * its own, already-granted permission and hands back the descriptor. The
 * isolated UID's own attempt to open the same path directly would fail:
 * shared storage is arbitrated by the FUSE daemon against the *calling*
 * UID's storage grants, which an isolated UID never has.
 *
 * {@link EngineHost#gameBroker()} and {@link EngineHost#saveBroker()} are
 * null for an ordinary in-process launch, where a plugin already has plain
 * File/{@link EngineFileSystem} access; both are non-null only when this
 * runtime is isolated. Returned descriptors carry seekable, mmap-able fds
 * (an ordinary open file), unlike {@link EngineFileSystem}'s streams --
 * this exists specifically for native code that keeps a file open and
 * seeks around it for the life of a session (an archive reader), which a
 * one-shot InputStream copy cannot serve without buffering the whole file
 * in Java first.
 *
 * A broker instance is scoped to exactly one root -- the game folder, or
 * the save folder -- and enforces that scope itself: {@code relativePath}
 * may not escape it (no "..", no absolute path), checked host-side, not
 * merely by the caller's good behaviour. The game-folder broker is
 * read-only: {@link #openWrite}, {@link #commitWrite} and {@link #delete}
 * throw on it.
 */
public interface EngineFileBroker {
    /** Entries directly inside {@code relativePath} ({@code ""} for the root itself). */
    String[] list(String relativePath) throws IOException;

    /** A read-only descriptor. Native code may keep it open and seek for the session's life. */
    ParcelFileDescriptor openRead(String relativePath) throws IOException;

    /**
     * A descriptor to a fresh location for {@code relativePath}'s new
     * content -- not yet visible under that name. Nothing appears there
     * until {@link #commitWrite}; a crash, or a write never committed,
     * leaves nothing behind under {@code relativePath}. Read-only brokers
     * throw {@link UnsupportedOperationException}.
     */
    ParcelFileDescriptor openWrite(String relativePath) throws IOException;

    /** Makes the bytes from the matching {@link #openWrite} visible under {@code relativePath}. */
    void commitWrite(String relativePath) throws IOException;

    /** Removes {@code relativePath} if present; a no-op otherwise. Read-only brokers throw. */
    void delete(String relativePath) throws IOException;
}
