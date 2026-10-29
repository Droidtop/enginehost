package dev.enginehost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * A plain software-rendered View for an [dev.enginehost.api.EngineStepDriven]
 * plugin's isolated runtime: the same letterboxed-bitmap draw and
 * pointer-to-engine-space scaling CatSystem2's own in-process ScreenView
 * does, written once here because it is generic to any pixel-buffer engine
 * this milestone's shape covers (docs/engine-sandbox.md roadmap), not
 * specific to CatSystem2.
 */
internal class IsolatedFrameView(
    context: Context,
    private val engineWidth: Int,
    private val engineHeight: Int,
    private val onPointerMove: (Int, Int) -> Unit,
    private val onPointerUp: (Int, Int) -> Unit,
) : View(context) {
    private val frame = Bitmap.createBitmap(engineWidth, engineHeight, Bitmap.Config.ARGB_8888)
    private val source = Rect(0, 0, engineWidth, engineHeight)
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
    }

    /** [pixels] is engineWidth * engineHeight ARGB_8888 ints; [band] is EngineStepDriven.step()'s return, already known > 0. */
    fun applyFrame(pixels: IntArray, band: Int) {
        val top = band ushr 16
        val rows = band and 0xffff
        frame.setPixels(pixels, top * engineWidth, engineWidth, 0, top, engineWidth, rows)
        invalidate()
    }

    private fun scale(): Float = minOf(width / engineWidth.toFloat(), height / engineHeight.toFloat())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = scale()
        if (scale <= 0f) return
        val left = (width - engineWidth * scale) / 2
        val top = (height - engineHeight * scale) / 2
        destination.set(left, top, left + engineWidth * scale, top + engineHeight * scale)
        canvas.drawBitmap(frame, source, destination, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scale = scale()
        if (scale <= 0f) return true
        val left = (width - engineWidth * scale) / 2
        val top = (height - engineHeight * scale) / 2
        val x = ((event.x - left) / scale).roundToInt()
        val y = ((event.y - top) / scale).roundToInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> onPointerMove(x, y)
            MotionEvent.ACTION_UP -> {
                onPointerMove(x, y)
                onPointerUp(x, y)
            }
        }
        return true
    }
}
