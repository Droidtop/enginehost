package dev.enginehost.api;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

/** Optional host I/O facade for engines that do not require mmap/native paths. */
public interface EngineFileSystem {
    InputStream openRead(String relativePath) throws FileNotFoundException;
    OutputStream openWrite(String relativePath, boolean append) throws FileNotFoundException;
    boolean exists(String relativePath);
    String[] list(String relativePath);
}
