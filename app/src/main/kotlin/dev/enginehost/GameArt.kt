package dev.enginehost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * A game's picture, from local sources only (Droidtop/tracker#240): a picture the person
 * chose for it, else the game's own icon ([GameIcon]: the one its engine configures, or its
 * executable's). Never fetched, never made up: a game with neither is drawn as a plate of
 * its engine's colour with its initial, the same honest stand-in the game page uses.
 *
 * Every screen asks here: the game page, the launch screen, the loading cover, and the
 * library's rows and shelves, which draw through [bind]. A row's picture is resolved once,
 * off the main thread, and kept as a small thumbnail in the app's cache keyed by the game's
 * path and the folder's and the chosen picture's modification times, with the newest in
 * memory; scrolling a long library reads nothing on the main thread.
 */
object GameArt {
    /** The thumbnail edge on rows and shelves, in pixels; big enough for a 72 dp slot at 3x density. */
    private const val THUMB_PX = 216

    /** Largest edge a chosen picture is kept at. */
    private const val CHOSEN_PX = 1024

    private val memory = object : LruCache<String, Bitmap>(8 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Games with no picture, so a row does not look again on every bind. */
    private val none = LruCache<String, Boolean>(4096)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "enginehost-art").apply { isDaemon = true } }

    /** The full-size picture, for the game page and the launch screen. Reads files: not for the main thread. */
    fun load(context: Context, gameFolder: File, engine: String?, engineContext: String?, execFile: String?): Bitmap? =
        chosenFile(context, gameFolder).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
            ?: GameIcon.load(gameFolder, engine, engineContext, execFile)

    fun load(context: Context, gameFolder: File, config: EngineConfig): Bitmap? =
        load(context, gameFolder, config.engine, config.engineContext, config.execFile)

    /**
     * Draws [row]'s picture into [view]: at once from memory, else its plate now and the
     * picture when the worker has it, if the view still shows that game by then.
     */
    fun bind(view: ImageView, row: GameRow) {
        view.tag = row.path
        val key = row.path
        memory.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(plate(view.context, row.name, row.engine))
        // A scan's archive or loose file has no folder to look in.
        if (row.kind != FindingKind.FOLDER || none.get(key) == true) return
        val context = view.context.applicationContext
        worker.execute {
            val thumb = runCatching { thumbnail(context, row) }.getOrNull()
            if (thumb == null) {
                none.put(key, true)
                return@execute
            }
            memory.put(key, thumb)
            view.post { if (view.tag == key) view.setImageBitmap(thumb) }
        }
    }

    /** Keeps [uri]'s picture as [gameFolder]'s, scaled down; reads and writes files, so off the main thread. */
    fun choose(context: Context, gameFolder: File, uri: Uri) {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            decodeScaled(bytes, CHOSEN_PX)
        } ?: throw IllegalArgumentException("That file is not a picture")
        val target = chosenFile(context, gameFolder)
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".partial")
        partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!partial.renameTo(target)) throw IllegalStateException("Could not keep the picture")
        forget(gameFolder)
    }

    /** Drops [gameFolder]'s chosen picture; its own icon is used again. */
    fun clearChosen(context: Context, gameFolder: File) {
        chosenFile(context, gameFolder).delete()
        forget(gameFolder)
    }

    fun hasChosen(context: Context, gameFolder: File): Boolean = chosenFile(context, gameFolder).isFile

    private fun forget(gameFolder: File) {
        memory.remove(gameFolder.path)
        none.remove(gameFolder.path)
    }

    private fun thumbnail(context: Context, row: GameRow): Bitmap? {
        val folder = File(row.path)
        val chosen = chosenFile(context, folder)
        val stamp = "${folder.lastModified()}-${if (chosen.isFile) chosen.lastModified() else 0}"
        val cached = File(File(context.cacheDir, "game-art"), "${key(folder)}-$stamp.png")
        if (cached.isFile) BitmapFactory.decodeFile(cached.path)?.let { return it }
        val full = load(context, folder, row.engine, row.engineContext, row.launch) ?: return null
        val thumb = scale(full, THUMB_PX)
        cached.parentFile?.mkdirs()
        runCatching { cached.outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return thumb
    }

    /** Plates by engine and initial: a long list has few distinct ones. */
    private val plates = LruCache<String, Bitmap>(256)

    /** The plate a game without a picture wears: its engine's colour, its initial. */
    fun plate(context: Context, name: String, engine: String?): Drawable {
        val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ""
        val plateKey = "${engine.orEmpty()}|$letter|${context.resources.configuration.uiMode}|${Accent.generation}"
        plates.get(plateKey)?.let { return BitmapDrawable(context.resources, it) }
        val size = THUMB_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = engine?.let { EngineHues.color(context, it) } ?: AccentViewInflater.colour(context, R.color.eh_surface_variant)
        }
        val radius = size * 0.12f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, fill)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (AccentPalette.contrast(0xFF000000.toInt(), fill.color) >= 4.5) 0xFF06121C.toInt() else 0xFFFFFFFF.toInt()
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val y = size / 2f - (ink.descent() + ink.ascent()) / 2
        canvas.drawText(letter, size / 2f, y, ink)
        plates.put(plateKey, bitmap)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun chosenFile(context: Context, gameFolder: File): File =
        File(File(context.filesDir, "game-art"), "${key(gameFolder)}.png")

    private fun key(gameFolder: File): String {
        val path = runCatching { gameFolder.canonicalPath }.getOrDefault(gameFolder.absolutePath)
        return MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
    }

    private fun decodeScaled(bytes: ByteArray, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return scale(decoded, edge)
    }

    private fun scale(bitmap: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= edge) return bitmap
        val ratio = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }
}
