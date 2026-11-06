package com.flix.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Artwork for titles that come without a poster, made to look like a one-sheet rather than
 * a placeholder: a deep two-tone from a small set of film palettes (picked by the title's
 * name, so a title always gets the same cover), a spotlight falling from the top, a strip of
 * film perforations along the top edge, the first letter as a huge outlined watermark, and a
 * dark fade at the bottom where the card prints the title.
 */
class CoverArt(name: String) : Drawable() {
    private val palette = PALETTES[(name.hashCode() and 0x7fffffff) % PALETTES.size]
    private val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: ""
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holes = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x2EFFFFFF
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        fill.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            palette.first, palette.second, Shader.TileMode.CLAMP)
        // A spotlight from just above the top centre, as if the card were lit on a stage.
        spot.shader = RadialGradient(b.left + w * 0.5f, b.top - h * 0.08f, h * 0.75f,
            intArrayOf(0x55FFFFFF, 0x14FFFFFF, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        fade.shader = LinearGradient(0f, b.top + h * 0.5f, 0f, b.bottom.toFloat(), 0, 0xD9000000.toInt(), Shader.TileMode.CLAMP)
        mark.textSize = h * 0.72f
        mark.strokeWidth = maxOf(2f, w * 0.012f)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawRect(b, fill)
        canvas.drawRect(b, spot)
        // The letter, outlined and faint, centred a little high so the title below stays clear.
        canvas.drawText(letter, b.left + w / 2, b.top + h * 0.6f, mark)
        // Film perforations along the top edge.
        val hole = w * 0.055f
        val gap = hole * 0.9f
        var x = b.left + gap * 0.6f
        val y = b.top + h * 0.035f
        while (x + hole < b.right) {
            r.set(x, y, x + hole, y + hole * 0.7f)
            canvas.drawRoundRect(r, hole * 0.18f, hole * 0.18f, holes)
            x += hole + gap
        }
        canvas.drawRect(b, fade)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        /** Deep film palettes, light corner to dark corner: never a flat green box. */
        private val PALETTES = listOf(
            0xFF1F4E5F.toInt() to 0xFF0B1418.toInt(), // teal night
            0xFF6B1E2E.toInt() to 0xFF160709.toInt(), // wine
            0xFF2A3A7A.toInt() to 0xFF0A0E1F.toInt(), // midnight blue
            0xFF5B2A6E.toInt() to 0xFF120816.toInt(), // plum
            0xFF7A4A1C.toInt() to 0xFF1A0F06.toInt(), // amber noir
            0xFF2F5A3A.toInt() to 0xFF0A140D.toInt(), // forest
            0xFF7A2A1E.toInt() to 0xFF180806.toInt(), // ember
            0xFF3A3F4A.toInt() to 0xFF0C0D10.toInt(), // steel
        )

        /** The cover's main colour, used for the ambient glow when a title has no artwork at all. */
        fun accentOf(name: String) = PALETTES[(name.hashCode() and 0x7fffffff) % PALETTES.size].first
    }
}
