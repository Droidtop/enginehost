package dev.enginehost.runtime;

import android.os.ParcelFileDescriptor;
import dev.enginehost.runtime.BrokerListing;

/**
 * Binder-facing counterpart of dev.enginehost.api.EngineFileBroker
 * (plugin-api). The host implements one of these per root -- the game
 * folder, or the save folder -- and hands the isolated runtime service a
 * live binder to it at IEngineRuntimeService.init(); see
 * docs/engine-sandbox.md "Host file service design". The game folder is
 * read-only unless the bundle declares writesGameFolder; a read-only
 * instance throws (as an IllegalStateException, which crosses Binder to
 * the caller normally) from openWrite, commitWrite, delete, makeDirectory
 * and rename.
 */
interface IEngineFileBroker {
    String[] list(String relativePath);
    ParcelFileDescriptor openRead(String relativePath);
    ParcelFileDescriptor openWrite(String relativePath);
    void commitWrite(String relativePath);
    boolean delete(String relativePath);

    /**
     * Directory relativePath's entries with their kind, size and modified
     * time, for the native file layer (IsolatedVfs); null when it is not a
     * directory.
     */
    BrokerListing listEntries(String relativePath);

    /** Creates directory relativePath (its parent must exist); false if it could not. */
    boolean makeDirectory(String relativePath);

    /** Moves one entry to another name in the same root, replacing a file already there; false if it could not. */
    boolean rename(String fromPath, String toPath);
}
