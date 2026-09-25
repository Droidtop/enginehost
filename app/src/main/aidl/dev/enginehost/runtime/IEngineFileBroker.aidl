package dev.enginehost.runtime;

import android.os.ParcelFileDescriptor;

/**
 * Binder-facing counterpart of dev.enginehost.api.EngineFileBroker
 * (plugin-api). The host implements one of these per root -- the game
 * folder, read-only, or the save folder, read-write -- and hands the
 * isolated runtime service a live binder to it at
 * IEngineRuntimeService.init(); see docs/engine-sandbox.md "Host file
 * service design". A read-only instance throws (as a RuntimeException,
 * which crosses Binder to the caller normally) from openWrite, commitWrite
 * and delete.
 */
interface IEngineFileBroker {
    String[] list(String relativePath);
    ParcelFileDescriptor openRead(String relativePath);
    ParcelFileDescriptor openWrite(String relativePath);
    void commitWrite(String relativePath);
    boolean delete(String relativePath);
}
