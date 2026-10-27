package com.flix.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The sun's path for today, from sunrise to sunset, with a glowing dot where it is now. */
class SunArcView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = 0x55FFFFFF
        pathEffect = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)
    }
    private val travelled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        color = 0x40FFFFFF
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 9.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
    }

    private val arc = Path()
    private val partial = Path()
    private val measure = PathMeasure()
    private val point = FloatArray(2)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var sunrise: Date? = null
    private var sunset: Date? = null

    fun setSun(rise: Date?, set: Date?) {
        sunrise = rise
        sunset = set
        visibility = if (rise != null && set != null) VISIBLE else GONE
        invalidate()
    }

    /** Called once a minute so the sun creeps along its path. */
    fun tick() = invalidate()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 6 * density
        val bottom = h - 16 * density
        arc.reset()
        arc.moveTo(pad, bottom)
        arc.cubicTo(w * 0.25f, pad - 2 * density, w * 0.75f, pad - 2 * density, w - pad, bottom)
        travelled.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFFFFB35C.toInt(), 0xFFFFE6A8.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val rise = sunrise ?: return
        val set = sunset ?: return
        val bottom = height - 16 * density
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, horizon)
        canvas.drawPath(arc, track)

        val now = todayAt(Date())
        val fraction = ((now - todayAt(rise)).toFloat() / (todayAt(set) - todayAt(rise))).coerceIn(0f, 1f)
        val daytime = now in todayAt(rise)..todayAt(set)

        measure.setPath(arc, false)
        partial.reset()
        measure.getSegment(0f, measure.length * fraction, partial, true)
        if (daytime) canvas.drawPath(partial, travelled)
        measure.getPosTan(measure.length * fraction, point, null)

        val r = 4.5f * density
        val color = if (daytime) 0xFFFFD27A.toInt() else 0xFFCFE3FF.toInt()
        glow.shader = RadialGradient(point[0], point[1], r * 3.5f, color and 0x80FFFFFF.toInt(), 0, Shader.TileMode.CLAMP)
        canvas.drawCircle(point[0], point[1], r * 3.5f, glow)
        dot.color = color
        canvas.drawCircle(point[0], point[1], r, dot)

        val labelY = height - 3 * density
        canvas.drawText("↑ ${timeFormat.format(rise)}", 0f, labelY, label)
        val setText = "${timeFormat.format(set)} ↓"
        canvas.drawText(setText, width - label.measureText(setText), labelY, label)
    }

    /** Minutes since midnight in ms, so the stored sunrise/sunset compare against today's clock. */
    private fun todayAt(date: Date): Long {
        val c = Calendar.getInstance().apply { time = date }
        return (c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60_000L
    }
}
