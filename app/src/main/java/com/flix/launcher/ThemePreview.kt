package com.flix.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * A miniature home screen drawn in one theme's colours and shapes: the clock, the dock with
 * a focused app, the featured carousel and a poster row with a progress bar. Lets Settings
 * show what a theme looks like without leaving it.
 */
class ThemePreview(context: Context, private val theme: Look.Theme) : View(context) {
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val clip = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // The scenery behind everything: a dusk sky, the same for every theme so only the theme differs.
        fill.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF2B3A55.toInt(), 0xFF1A2236.toInt(), 0xFF0B0E16.toInt()), null, Shader.TileMode.CLAMP)
        // Everything stays inside the card's rounded corners (the poster row used to run off them).
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), 10 * dp, 10 * dp, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        r.set(0f, 0f, w, h)
        canvas.drawRect(r, fill)
        fill.shader = null

        // Scale the real home screen's corner radii (in dp at 960 dp wide) down to this card.
        val scale = w / (960 * dp)
        val pad = w * 0.05f

        // Clock: two short white bars.
        fill.color = 0xE6FFFFFF.toInt()
        r.set(pad, h * 0.07f, pad + w * 0.12f, h * 0.07f + h * 0.07f)
        canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)
        fill.color = 0x80FFFFFF.toInt()
        r.set(pad + w * 0.15f, h * 0.085f, pad + w * 0.32f, h * 0.085f + h * 0.04f)
        canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)

        // Dock: the theme's glass panel with four app circles, the first one focused.
        val dockTop = h * 0.2f
        val dockH = h * 0.2f
        r.set(pad * 0.6f, dockTop, w - pad * 0.6f, dockTop + dockH)
        panel(canvas, theme.radiusDp * dp * scale * 2.2f)
        val d = dockH * 0.56f
        for (i in 0 until 4) {
            // Inset from the dock's edge by more than the focus ring, so the ring never touches it.
            val cx = pad * 0.6f + dockH * 0.5f + 5 * dp + i * d * 1.4f
            val cy = dockTop + dockH / 2
            fill.color = if (i == 0) 0xFFFFFFFF.toInt() else 0x59FFFFFF
            canvas.drawCircle(cx, cy, d / 2, fill)
            if (i == 0) {
                stroke.color = theme.focus
                stroke.strokeWidth = 1.6f * dp
                canvas.drawCircle(cx, cy, d / 2 + 2.2f * dp, stroke)
            }
        }

        // Featured carousel, with the theme's own roundness and an accent marker.
        val bTop = dockTop + dockH + h * 0.06f
        val bBottom = bTop + h * 0.3f
        r.set(pad, bTop, w - pad, bBottom)
        fill.shader = LinearGradient(pad, 0f, w - pad, 0f, 0xFF3D2A2A.toInt(), 0xFF6B4B3A.toInt(), Shader.TileMode.CLAMP)
        val br = theme.billboardRadiusDp * dp * scale * 2.2f
        canvas.drawRoundRect(r, br, br, fill)
        fill.shader = null
        fill.color = theme.accent
        r.set(pad + w * 0.04f, bTop + h * 0.07f, pad + w * 0.04f + 2 * dp, bTop + h * 0.12f)
        canvas.drawRect(r, fill)
        fill.color = 0xE6FFFFFF.toInt()
        r.set(pad + w * 0.06f, bTop + h * 0.07f, pad + w * 0.3f, bTop + h * 0.12f)
        canvas.drawRoundRect(r, 2 * dp, 2 * dp, fill)

        // A poster row: the first has a progress bar in the accent colour.
        val pTop = bBottom + h * 0.06f
        val pw = (w - pad * 2) / 5.4f
        for (i in 0 until 6) {
            val x = pad + i * pw * 1.1f
            if (x > w) break
            // Posters stop short of the bottom, as the real row sits above the screen's edge.
            val bottom = h - pad * 0.7f
            r.set(x, pTop, x + pw, bottom)
            fill.color = 0x40FFFFFF
            canvas.drawRoundRect(r, br * 0.4f + 1.5f * dp, br * 0.4f + 1.5f * dp, fill)
            if (i == 0) {
                fill.color = theme.accent
                r.set(x + 2 * dp, bottom - 3.5f * dp, x + pw * 0.6f, bottom - 1.5f * dp)
                canvas.drawRoundRect(r, 1 * dp, 1 * dp, fill)
            }
        }
        canvas.restore()
    }

    private fun panel(canvas: Canvas, radius: Float) {
        fill.color = theme.panel or 0x22000000 // a touch darker so it reads at this size
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = theme.panelEdge
        stroke.strokeWidth = 1f * dp
        canvas.drawRoundRect(r, radius, radius, stroke)
    }
}
