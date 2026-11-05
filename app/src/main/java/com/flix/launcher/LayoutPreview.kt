package com.flix.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * A sketch of one home layout for the Settings picker: the dock on top in every one, then
 * the carousel and rows (Cinema), a grid of posters (Poster wall) or two lists (TV guide).
 */
class LayoutPreview(context: Context, private val layout: String) : View(context) {
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), 10 * dp, 10 * dp, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF2B3A55.toInt(), 0xFF141A28.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        val pad = w * 0.05f
        val accent = Look.accent(context)

        // The dock with app circles, shared by every layout.
        val dockTop = h * 0.08f
        val dockH = h * 0.17f
        fill.color = 0x40000000
        r.set(pad * 0.6f, dockTop, w - pad * 0.6f, dockTop + dockH)
        canvas.drawRoundRect(r, dockH / 2, dockH / 2, fill)
        fill.color = 0x80FFFFFF.toInt()
        for (i in 0 until 4) canvas.drawCircle(pad * 0.6f + dockH * 0.55f + i * dockH * 0.85f, dockTop + dockH / 2, dockH * 0.3f, fill)

        val top = dockTop + dockH + h * 0.07f
        val bottom = h - pad * 0.7f
        when (layout) {
            "wall" -> {
                // Name chips, then a dense grid of posters.
                for (i in 0 until 3) {
                    fill.color = if (i == 0) 0xFFFFFFFF.toInt() else 0x33FFFFFF
                    r.set(pad + i * w * 0.2f, top, pad + i * w * 0.2f + w * 0.17f, top + h * 0.07f)
                    canvas.drawRoundRect(r, h, h, fill)
                }
                val cols = 7
                val gap = 2.5f * dp
                val cw = (w - pad * 2 - gap * (cols - 1)) / cols
                val ch = cw * 1.45f
                var y = top + h * 0.12f
                var row = 0
                while (y < bottom) {
                    for (c in 0 until cols) {
                        fill.color = if (row == 0 && c == 0) 0xCCFFFFFF.toInt() else 0x40FFFFFF
                        r.set(pad + c * (cw + gap), y, pad + c * (cw + gap) + cw, minOf(y + ch, bottom))
                        canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)
                    }
                    y += ch + gap
                    row++
                }
            }
            "guide" -> {
                // Two lists of small pictures with a title line, one with progress bars.
                val colW = (w - pad * 2.6f) / 2
                for (col in 0 until 2) {
                    val x = pad + col * (colW + pad * 0.6f)
                    fill.color = 0xB3FFFFFF.toInt()
                    r.set(x, top, x + colW * 0.45f, top + h * 0.045f)
                    canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)
                    var y = top + h * 0.1f
                    var i = 0
                    while (y + h * 0.13f < bottom) {
                        fill.color = if (col == 0 && i == 0) 0x2EFFFFFF else 0x00000000
                        r.set(x - 2 * dp, y - 2 * dp, x + colW, y + h * 0.13f)
                        canvas.drawRoundRect(r, 4 * dp, 4 * dp, fill)
                        fill.color = 0x4DFFFFFF
                        r.set(x, y, x + colW * 0.3f, y + h * 0.11f)
                        canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)
                        fill.color = 0xCCFFFFFF.toInt()
                        r.set(x + colW * 0.36f, y + h * 0.015f, x + colW * 0.85f, y + h * 0.045f)
                        canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)
                        if (col == 0) {
                            fill.color = 0x33FFFFFF
                            r.set(x + colW * 0.36f, y + h * 0.08f, x + colW * 0.8f, y + h * 0.09f)
                            canvas.drawRect(r, fill)
                            fill.color = accent
                            r.set(x + colW * 0.36f, y + h * 0.08f, x + colW * (0.45f + 0.08f * i), y + h * 0.09f)
                            canvas.drawRect(r, fill)
                        }
                        y += h * 0.16f
                        i++
                    }
                }
            }
            else -> {
                // Cinema: the featured carousel, then a row of posters.
                fill.shader = LinearGradient(pad, 0f, w - pad, 0f, 0xFF3D2A2A.toInt(), 0xFF6B4B3A.toInt(), Shader.TileMode.CLAMP)
                r.set(pad, top, w - pad, top + h * 0.33f)
                canvas.drawRoundRect(r, 4 * dp, 4 * dp, fill)
                fill.shader = null
                fill.color = 0xE6FFFFFF.toInt()
                r.set(pad + w * 0.05f, top + h * 0.08f, pad + w * 0.32f, top + h * 0.13f)
                canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)
                val pTop = top + h * 0.39f
                val pw = (w - pad * 2) / 5.4f
                // The focused card is wide, the rest are posters.
                var x = pad
                for (i in 0 until 6) {
                    val cw = if (i == 0) pw * 1.6f else pw
                    fill.color = if (i == 0) 0xCCFFFFFF.toInt() else 0x40FFFFFF
                    r.set(x, pTop, x + cw, bottom)
                    canvas.drawRoundRect(r, 3 * dp, 3 * dp, fill)
                    x += cw + pw * 0.1f
                }
            }
        }
        canvas.restore()
    }
}
