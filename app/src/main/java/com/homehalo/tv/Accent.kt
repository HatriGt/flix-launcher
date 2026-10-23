package com.homehalo.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

/** Picks the most saturated, reasonably bright colour of a drawable for the focus glow. */
object Accent {
    private const val SAMPLE = 24
    private const val FALLBACK = 0xFFFFFFFF.toInt()

    fun of(drawable: Drawable): Int = runCatching {
        val bitmap = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val old = drawable.copyBounds()
        drawable.setBounds(0, 0, SAMPLE, SAMPLE)
        drawable.draw(canvas)
        drawable.bounds = old

        val hsv = FloatArray(3)
        var best = FALLBACK
        var bestScore = 0.25f
        for (y in 0 until SAMPLE) for (x in 0 until SAMPLE) {
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            Color.colorToHSV(c, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        bitmap.recycle()
        best
    }.getOrDefault(FALLBACK)

    /** True when the drawable paints its whole square (an old-style square icon), so it can fill a round tile edge to edge. */
    fun isFullBleed(drawable: Drawable): Boolean = runCatching {
        val bitmap = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
        val old = drawable.copyBounds()
        drawable.setBounds(0, 0, SAMPLE, SAMPLE)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = old
        val edge = SAMPLE - 2
        val corners = listOf(bitmap.getPixel(1, 1), bitmap.getPixel(edge, 1), bitmap.getPixel(1, edge), bitmap.getPixel(edge, edge))
        bitmap.recycle()
        corners.all { Color.alpha(it) > 230 }
    }.getOrDefault(false)
}
