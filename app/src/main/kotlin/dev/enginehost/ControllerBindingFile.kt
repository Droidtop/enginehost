package dev.enginehost

import android.content.Context
import android.os.FileObserver
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Where a binding actually lives: one JSON file in its own folder of the
 * app's `filesDir` ([FOLDER]), read by every process that asks for it. Its
 * own folder, because a locked-down game process reads and watches it
 * there and nothing else of the app's (docs/engine-sandbox.md, "Ren'Py
 * under lockdown"; the dev.443 rig run refused a watch on `filesDir`).
 *
 * `SharedPreferences` could not do this job. Its cache is per process and
 * there is no supported way to share one, so the controller screen (the
 * default process) and a running game (`:runtime`) each held their own copy
 * and a binding changed from the in-game menu only reached the game at its
 * next launch. A file has one copy, and a process can be told when it
 * changes.
 *
 * - **Writing** is read-modify-write inside a cross-process [java.nio.channels.FileLock],
 *   landing by atomic rename, so a half-written map is never a state anyone
 *   can read and two processes writing at once cannot lose each other's
 *   keys.
 * - **Reading** is cached, and the cache is dropped by a [FileObserver] on
 *   the *directory* -- the file is replaced rather than edited, so its
 *   inode is not a thing to watch. That signal is the whole of what makes a
 *   change apply immediately; nothing polls and nothing re-reads on resume.
 *
 * One instance per process, because one observer and one cache are enough.
 */
internal class ControllerBindingFile private constructor(directory: File) : BindingStorage {
    private val file = File(directory, NAME)
    private val temporary = File(directory, "$NAME.new")
    private val guard = File(directory, "$NAME.lock")
    private val writing = Any()

    @Volatile private var cache: Map<String, Any>? = null

    /** Held for its lifetime: an observer that is collected stops watching. */
    @Suppress("DEPRECATION", "unused") // The File constructor is API 29; this app runs from 26.
    private val observer = object : FileObserver(
        directory.absolutePath,
        FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == NAME) cache = null
        }
    }.also(FileObserver::startWatching)

    private fun values(): Map<String, Any> = cache ?: load().also { cache = it }

    private fun load(): Map<String, Any> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith(json::get)
    }

    override fun binding(key: String): JSONObject? = values()[key] as? JSONObject

    override fun flag(key: String, fallback: Boolean): Boolean = values()[key] as? Boolean ?: fallback

    override fun contains(key: String): Boolean = values().containsKey(key)

    /** [change] applied to the current map and written back, for every process. */
    override fun edit(change: (MutableMap<String, Any>) -> Unit) {
        synchronized(writing) {
            RandomAccessFile(guard, "rw").use { handle ->
                handle.channel.lock().use {
                    // Loaded inside the lock, not from the cache: another
                    // process may have written since this one last looked.
                    val next = load().toMutableMap().also(change)
                    val json = JSONObject()
                    next.forEach { (key, value) -> json.put(key, value) }
                    temporary.writeText(json.toString())
                    check(temporary.renameTo(file)) { "Could not replace $file" }
                    cache = next
                }
            }
        }
    }

    companion object {
        private const val NAME = "controller-bindings-v1.json"

        /** The folder under `filesDir` that holds the file; a locked-down game may read and write it. */
        const val FOLDER = "controls"

        fun folder(context: Context): File = File(context.applicationContext.filesDir, FOLDER)

        @Volatile private var instance: ControllerBindingFile? = null

        fun of(context: Context): ControllerBindingFile {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ControllerBindingFile(folder(context).apply { mkdirs() })
                    .also { instance = it }
            }
        }
    }
}

/**
 * Where a [ControllerBindingStore] keeps its keys. The real one is the shared file
 * ([ControllerBindingFile]); a test gives the store a map in memory, so the resolution
 * order is checked on the JVM.
 */
internal interface BindingStorage {
    fun binding(key: String): JSONObject?
    fun flag(key: String, fallback: Boolean): Boolean
    fun contains(key: String): Boolean

    /** [change] applied to the current map and written back. */
    fun edit(change: (MutableMap<String, Any>) -> Unit)
}
