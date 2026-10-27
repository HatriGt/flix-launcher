package com.flix.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Artwork for titles that come without a poster: a two-tone gradient whose colours are
 * picked from the title's name (so a title always gets the same cover), a soft light in
 * the corner and the first letter, huge and faint, as a watermark.
 */
class CoverArt(name: String) : Drawable() {
    private val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
    private val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: ""
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFFFFFF
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    override fun onBoundsChange(b: Rect) {
        fill.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            colorOf(hue), Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.7f, 0.14f)), Shader.TileMode.CLAMP)
        val r = maxOf(b.width(), b.height()) * 0.9f
        light.shader = RadialGradient(b.left.toFloat(), b.top.toFloat(), r, 0x33FFFFFF, 0, Shader.TileMode.CLAMP)
        mark.textSize = b.height() * 0.62f
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, fill)
        canvas.drawRect(b, light)
        canvas.drawText(letter, b.right + b.height() * 0.04f, b.top + b.height() * 0.58f, mark)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private fun colorOf(hue: Float) = Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.46f))

        /** The cover's main colour, used for the ambient glow when a title has no artwork at all. */
        fun accentOf(name: String) = colorOf(((name.hashCode() and 0x7fffffff) % 360).toFloat())
    }
}
