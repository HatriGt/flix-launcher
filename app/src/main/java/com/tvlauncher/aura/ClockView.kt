package com.tvlauncher.aura

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Large clock: hours in a medium weight, minutes thin with a soft gradient.
 * When the time changes, only the digits that changed roll up into place.
 */
class ClockView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 74f, resources.displayMetrics)
    private val hourPaint = textPaint("sans-serif-medium")
    private val minutePaint = textPaint("sans-serif-thin")
    private val colonPaint = textPaint("sans-serif-thin").apply { color = 0xB3FFFFFF.toInt() }

    private var hours = ""
    private var minutes = ""
    private var oldText = ""
    private var progress = 1f
    private val roll = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700
        interpolator = DecelerateInterpolator(2.2f)
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    private fun textPaint(family: String) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(family, Typeface.NORMAL)
        textSize = size
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(18f, 0f, 2f, 0x66000000)
    }

    fun setTime(h: String, m: String) {
        val newText = "$h:$m"
        val current = "$hours:$minutes"
        if (newText == current) return
        val first = hours.isEmpty()
        oldText = current
        hours = h
        minutes = m
        minutePaint.shader = null
        requestLayout()
        if (first || !isShown) {
            progress = 1f
            invalidate()
        } else {
            roll.start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = hourPaint.measureText(hours) + colonWidth() + minutePaint.measureText(minutes)
        val fm = hourPaint.fontMetrics
        val height = (fm.descent - fm.ascent) * 0.86f
        setMeasuredDimension(width.toInt() + paddingLeft + paddingRight + 8, height.toInt() + paddingTop + paddingBottom)
    }

    private fun colonWidth() = colonPaint.measureText(":") * 1.25f

    override fun onDraw(canvas: Canvas) {
        val fm = hourPaint.fontMetrics
        val lineHeight = measuredHeight.toFloat()
        val baseline = paddingTop - fm.ascent * 0.9f
        val rollDistance = lineHeight * 0.55f

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        var x = paddingLeft.toFloat()
        x = drawDigits(canvas, hours, oldText.substringBefore(':'), hourPaint, x, baseline, rollDistance)
        canvas.drawText(":", x + colonWidth() * 0.1f, baseline - size * 0.05f, colonPaint)
        x += colonWidth()
        if (minutePaint.shader == null) {
            val w = minutePaint.measureText(minutes)
            minutePaint.shader = LinearGradient(x, 0f, x + w, lineHeight, 0xFFFFFFFF.toInt(), 0xFFA9DCFF.toInt(), Shader.TileMode.CLAMP)
        }
        drawDigits(canvas, minutes, oldText.substringAfter(':', ""), minutePaint, x, baseline, rollDistance)
        canvas.restore()
    }

    private fun drawDigits(canvas: Canvas, now: String, before: String, paint: Paint, startX: Float, baseline: Float, distance: Float): Float {
        var x = startX
        val baseAlpha = paint.alpha
        for (i in now.indices) {
            val ch = now[i].toString()
            val old = before.getOrNull(i - (now.length - before.length))?.toString()
            if (progress < 1f && old != null && old != ch) {
                paint.alpha = (baseAlpha * (1f - progress)).toInt()
                canvas.drawText(old, x, baseline - distance * progress, paint)
                paint.alpha = (baseAlpha * progress).toInt()
                canvas.drawText(ch, x, baseline + distance * (1f - progress), paint)
                paint.alpha = baseAlpha
            } else {
                canvas.drawText(ch, x, baseline, paint)
            }
            x += paint.measureText(ch)
        }
        return x
    }
}
