package com.flix.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable

/**
 * A rounded capsule of dark glass whose edge is a ribbon of light slowly travelling around
 * it: a sweep of Flix red, warm gold and violet. The light moves by turning the gradient, so
 * the shape itself never redraws differently. [angle] is driven from outside.
 */
class GlowBorder(private val dp: Float) : Drawable() {
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF20C0C10.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f * dp; alpha = 70 }
    private val r = RectF()
    private val m = Matrix()
    private var sweep: SweepGradient? = null

    /** Degrees the light has travelled round the capsule. */
    var angle = 0f
        set(v) { field = v; invalidateSelf() }

    /** Focused: the edge burns brighter and the glass lifts a shade. */
    var lit = false
        set(v) { field = v; glass.color = if (v) 0xF21A1418.toInt() else 0xF20C0C10.toInt(); bloom.alpha = if (v) 120 else 70; invalidateSelf() }

    override fun onBoundsChange(b: Rect) {
        sweep = SweepGradient(b.exactCenterX(), b.exactCenterY(),
            intArrayOf(0xFFE50914.toInt(), 0xFFFFB347.toInt(), 0x00FFFFFF, 0x00FFFFFF, 0xFF8B5CF6.toInt(), 0xFFE50914.toInt()),
            floatArrayOf(0f, 0.16f, 0.32f, 0.62f, 0.82f, 1f))
        edge.shader = sweep
        bloom.shader = sweep
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = bloom.strokeWidth / 2
        r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val radius = r.height() / 2
        m.setRotate(angle, b.exactCenterX(), b.exactCenterY())
        sweep?.setLocalMatrix(m)
        canvas.drawRoundRect(r, radius, radius, glass)
        canvas.drawRoundRect(r, radius, radius, bloom)
        canvas.drawRoundRect(r, radius, radius, edge)
    }

    override fun setAlpha(alpha: Int) { glass.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
