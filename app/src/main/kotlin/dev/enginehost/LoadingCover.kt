package dev.enginehost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import kotlin.math.min

/**
 * The game's own loading screen, drawn by the host over the runtime's window
 * from before the core loads until the core's first frame (Droidtop/tracker#640).
 *
 * Two steps, split by the sandbox. [prepare] reads and decodes the picture and
 * must run before [RuntimeSandbox.lockdown]: after it, nothing in the process
 * opens a file except through the broker, and the host is no exception.
 * [show] only draws what [prepare] decoded, so it needs no file. The cover goes
 * at the first frame the game's surface posts ([lift], called by
 * [RuntimeLoadingNotice], which already watches for it).
 *
 * What is shown, first that exists: the engine family's own loading picture
 * ([LoadingImages]), the game's box art ([GameIcon]) over a dark ground, and
 * otherwise nothing, which leaves the plain dark window with the "Loading"
 * bar the notice has always shown. Never a screenshot.
 *
 * One game runs per runtime process, so the cover is process state.
 */
internal object LoadingCover {
    /** What [prepare] decoded: [image] on [background]; [fill] is Ren'Py's progress foreground; [boxArt] is the fallback. */
    class Prepared(val background: Int, val image: Bitmap, val fill: Bitmap?, val boxArt: Boolean)

    private var view: View? = null

    /** A cover is up and is the game's own picture, which the "Loading" bar must not be drawn over. */
    val coversBar: Boolean get() = view != null && ownArt

    private var ownArt = false

    /** A cover is up. */
    val showing: Boolean get() = view != null

    /**
     * Reads the loading picture of [config]'s game. Before the sandbox closes
     * and before any core code runs; never throws, since the only acceptable
     * failure is a missing picture.
     */
    fun prepare(context: Context, gameFolder: File, config: EngineConfig): Prepared? = prepare(
        context, gameFolder, GameRunner.engineFolder(gameFolder, config), config.engine, config.engineContext, config.execFile,
    )

    /** [prepare] for the folders and engine of a launch, as its runtime intent carries them. */
    private fun prepare(
        context: Context,
        gameFolder: File,
        engineFolder: File,
        engine: String,
        engineContext: String?,
        execFile: String?,
    ): Prepared? = try {
        val dark = context.getColor(R.color.eh_background)
        val side = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
        val own = when (val art = LoadingImages.find(engineFolder, engine, engineContext)) {
            is LoadingArt.Still -> decode(art.file, side)?.let { Prepared(art.background ?: dark, it, null, boxArt = false) }
            is LoadingArt.Progress -> {
                val background = decode(art.background, side)
                val foreground = decode(art.foreground, side)
                if (background != null && foreground != null) Prepared(dark, background, foreground, boxArt = false) else null
            }
            null -> null
        }
        own ?: GameArt.load(context, gameFolder, engine, engineContext, execFile)?.let { Prepared(dark, it, null, boxArt = true) }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /**
     * A core that runs an Activity of its own (Ren'Py, KiriKiri: the activity transport) never
     * shows RuntimeActivity, so its cover is read here, from the launch's intent, by the factory
     * that makes the core's Activity, before it locks the process down
     * ([EnginehostComponentFactory]). It goes up when that Activity starts ([showOnEngineActivity]).
     */
    fun prepareForEngineActivity(context: Context, intent: Intent) {
        if (pending != null) return
        val game = intent.getStringExtra(RuntimeActivity.EXTRA_GAME)?.let(::File) ?: return
        val engineFolder = intent.getStringExtra(RuntimeActivity.EXTRA_PATH)?.let(::File) ?: return
        val engine = intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE) ?: return
        pending = prepare(
            context, game, engineFolder, engine,
            intent.getStringExtra(RuntimeActivity.EXTRA_ENGINE_CONTEXT), intent.getStringExtra(RuntimeActivity.EXTRA_EXEC_FILE),
        )
    }

    /** Puts the cover [prepareForEngineActivity] read over [activity], once. Main thread. */
    fun showOnEngineActivity(activity: Activity) {
        val prepared = pending ?: return
        pending = null
        show(activity, prepared)
    }

    /** Read for an engine's own Activity and not shown yet. */
    private var pending: Prepared? = null

    /** A picture decoded no larger than the screen needs; null for one too big or unreadable. */
    private fun decode(file: File, screenSide: Int): Bitmap? {
        if (file.length() !in 1..MAX_FILE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenSide && bounds.outHeight / (sample * 2) >= screenSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Puts [prepared] over everything [activity] shows, now, so the window's first frame is already it. Main thread. */
    fun show(activity: Activity, prepared: Prepared) {
        lift()
        val cover = CoverView(activity, prepared)
        activity.addContentView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = cover
        ownArt = !prepared.boxArt
    }

    /** Takes the cover away: the game drew its first frame, or nothing can tell. Main thread; harmless when none is up. */
    fun lift() {
        val cover = view ?: return
        view = null
        ownArt = false
        (cover.parent as? ViewGroup)?.removeView(cover)
    }

    private const val MAX_FILE_BYTES = 16L shl 20

    private class CoverView(context: Context, private val shown: Prepared) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val startedAt = SystemClock.uptimeMillis()
        private val target = RectF()

        init {
            // Takes no touches and says nothing to accessibility: it is a picture over a game that is starting.
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(shown.background)
            val screenW = width.toFloat()
            val screenH = height.toFloat()
            // Box art is an icon; it is shown at a fixed share of the screen, not stretched across it.
            val boxW = if (shown.boxArt) min(screenW, screenH) * BOX_ART_SHARE else screenW
            val boxH = if (shown.boxArt) min(screenW, screenH) * BOX_ART_SHARE else screenH
            val scale = min(boxW / shown.image.width, boxH / shown.image.height)
            val w = shown.image.width * scale
            val h = shown.image.height * scale
            target.set((screenW - w) / 2, (screenH - h) / 2, (screenW + w) / 2, (screenH + h) / 2)
            canvas.drawBitmap(shown.image, null, target, paint)
            val fill = shown.fill ?: return
            val done = LoadingProgress.estimate(SystemClock.uptimeMillis() - startedAt)
            canvas.save()
            canvas.clipRect(target.left, target.top, target.left + target.width() * done, target.bottom)
            canvas.drawBitmap(fill, null, target, paint)
            canvas.restore()
            postInvalidateOnAnimation()
        }
    }

    private const val BOX_ART_SHARE = 0.4f
}
