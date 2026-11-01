package com.flix.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * An app tile that cuts its own shape. Outline clipping alone isn't enough: some TVs
 * (the NVIDIA Shield on Android 11, for one) draw the artwork as a plain square anyway,
 * so the tile also clips its canvas to the circle or rounded square before drawing.
 */
class ShapeTile @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val path = Path()
    private var circle = true
    private var radius = 0f

    fun setShape(circle: Boolean, radius: Float) {
        if (this.circle == circle && this.radius == radius) return
        this.circle = circle
        this.radius = radius
        rebuild()
        invalidateOutline()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        path.reset()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (circle) path.addOval(0f, 0f, w, h, Path.Direction.CW)
        else path.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        if (path.isEmpty) return super.draw(canvas)
        val save = canvas.save()
        canvas.clipPath(path)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}
