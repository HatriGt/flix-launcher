package com.flix.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan

/** The IMDb rating that addons and Cinemeta report, drawn as IMDb's own yellow tag. */
object Rating {
    /** "IMDb 7.4" with the yellow tag, or null when there is no believable rating (IMDb never goes below 1). */
    fun text(raw: String?): CharSequence? {
        val value = raw?.trim()?.toFloatOrNull()?.takeIf { it in 1f..10f } ?: return null
        return SpannableStringBuilder().apply {
            append("IMDb", ImdbTag, 0)
            append(" ")
            append(String.format(java.util.Locale.US, "%.1f", value))
        }
    }

    private object ImdbTag : ReplacementSpan() {
        private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5C518.toInt() }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        private val box = RectF()

        private fun size(paint: Paint) { ink.textSize = paint.textSize * 0.78f }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            size(paint)
            fm?.let { paint.getFontMetricsInt(it) }
            return (ink.measureText("IMDb") + paint.textSize * 0.62f).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            size(paint)
            val w = ink.measureText("IMDb") + paint.textSize * 0.5f
            val h = paint.textSize * 1.08f
            val centre = y - paint.textSize * 0.36f
            box.set(x, centre - h / 2, x + w, centre + h / 2)
            canvas.drawRoundRect(box, h * 0.2f, h * 0.2f, tag)
            canvas.drawText("IMDb", x + paint.textSize * 0.25f, centre + ink.textSize * 0.36f, ink)
        }
    }
}
