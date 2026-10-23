package com.homehalo.tv

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** YouTube-style banner shown for SmartTube: red play button and wordmark on near-black. */
class YouTubeBanner(private val iconOnly: Boolean = false) : Drawable() {
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0033.toInt() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = -0.03f
    }
    private val rect = RectF()
    private val triangle = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        background.shader = LinearGradient(
            0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
            0xFF212121.toInt(), 0xFF0F0F0F.toInt(), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawRect(b, background)

        if (iconOnly) {
            val bw = w * 0.56f
            val bh = bw / 1.42f
            rect.set(b.left + (w - bw) / 2f, b.top + (h - bh) / 2f, b.left + (w + bw) / 2f, b.top + (h + bh) / 2f)
            canvas.drawRoundRect(rect, bh * 0.28f, bh * 0.28f, red)
            drawPlay(canvas, bh)
            return
        }

        text.textSize = h * 0.26f
        val label = "YouTube"
        val buttonH = h * 0.34f
        val buttonW = buttonH * 1.42f
        val gap = h * 0.07f
        val totalW = buttonW + gap + text.measureText(label)
        val left = b.left + (w - totalW) / 2f
        val top = b.top + (h - buttonH) / 2f

        rect.set(left, top, left + buttonW, top + buttonH)
        canvas.drawRoundRect(rect, buttonH * 0.28f, buttonH * 0.28f, red)

        drawPlay(canvas, buttonH)

        val cy = rect.centerY()
        val baseline = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, rect.right + gap, baseline, text)
    }

    private fun drawPlay(canvas: Canvas, buttonH: Float) {
        val cx = rect.centerX() + buttonH * 0.04f
        val cy = rect.centerY()
        val t = buttonH * 0.2f
        triangle.reset()
        triangle.moveTo(cx - t * 0.85f, cy - t)
        triangle.lineTo(cx + t * 1.05f, cy)
        triangle.lineTo(cx - t * 0.85f, cy + t)
        triangle.close()
        canvas.drawPath(triangle, white)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.OPAQUE
}
