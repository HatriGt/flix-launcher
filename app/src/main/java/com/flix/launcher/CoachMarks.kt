package com.flix.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The first look round the home screen, right after setup: the screen dims and the real
 * thing being described is lit through a rounded window, with a short line beside it. OK
 * moves on, Back skips. Shown once, to new users only.
 */
object CoachMarks {
    class Mark(val target: View, val title: String, val line: String)

    private fun prefs(context: Context) = context.getSharedPreferences("flix", Context.MODE_PRIVATE)

    /** Set when first-run setup finishes, so only new users get the look round. */
    fun pending(context: Context) = prefs(context).getBoolean("coach_pending", false)

    fun request(context: Context) = prefs(context).edit().putBoolean("coach_pending", true).apply()

    fun markDone(context: Context) = prefs(context).edit().putBoolean("coach_pending", false).apply()

    fun show(activity: Activity, marks: List<Mark>, onDone: () -> Unit = {}) {
        val visible = marks.filter { it.target.isShown && it.target.width > 0 }
        if (visible.isEmpty()) return
        val root = activity.window.decorView as ViewGroup
        val dp = activity.resources.displayMetrics.density
        val overlay = Spotlight(activity, dp)
        val title = TextView(activity).apply {
            textSize = 26f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val line = TextView(activity).apply {
            textSize = 16f; setTextColor(0xD9FFFFFF.toInt()); setLineSpacing(0f, 1.25f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val hint = TextView(activity).apply {
            textSize = 13f; setTextColor(0x99FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val caption = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 20 * dp; setColor(0xF20B0E14.toInt()); setStroke((1 * dp).toInt(), 0x26FFFFFF) }
            setPadding((28 * dp).toInt(), (22 * dp).toInt(), (28 * dp).toInt(), (22 * dp).toInt())
            addView(title)
            addView(line, LinearLayout.LayoutParams((520 * dp).toInt(), -2).apply { topMargin = (8 * dp).toInt() })
            addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (18 * dp).toInt() })
        }
        overlay.addView(caption, FrameLayout.LayoutParams(-2, -2))
        root.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        var index = 0
        fun close() {
            markDone(activity)
            overlay.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(overlay); onDone() }.start()
        }
        fun step() {
            val mark = visible[index]
            val at = IntArray(2); mark.target.getLocationInWindow(at)
            val pad = 12 * dp
            val hole = RectF(at[0] - pad, at[1] - pad, at[0] + mark.target.width + pad, at[1] + mark.target.height + pad)
            overlay.moveTo(hole)
            title.text = mark.title
            line.text = mark.line
            hint.text = if (index == visible.lastIndex) "${index + 1} of ${visible.size}   ·   OK to start watching" else "${index + 1} of ${visible.size}   ·   OK next   ·   Back skip"
            // The words go in a panel at the bottom of the screen, or the top when the lit part
            // is low, so they never cover what they describe.
            caption.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST))
            val low = hole.centerY() > root.height / 2f
            (caption.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = (64 * dp).toInt()
                // Above a low target, ending just over it; below a high one, near the bottom.
                topMargin = (if (low) (hole.top - 24 * dp - caption.measuredHeight).coerceAtLeast(24 * dp)
                    else root.height - caption.measuredHeight - 48 * dp).toInt()
            }.also { caption.layoutParams = it }
            caption.alpha = 0f; caption.translationY = 10 * dp
            caption.animate().alpha(1f).translationY(0f).setStartDelay(160).setDuration(320).setInterpolator(RowRecyclerView.EASE).start()
        }
        overlay.isFocusable = true
        overlay.isFocusableInTouchMode = true
        overlay.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (index < visible.lastIndex) { index++; step() } else close()
                KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) { index--; step() }
                KeyEvent.KEYCODE_BACK -> close()
            }
            true
        }
        overlay.alpha = 0f
        overlay.post {
            step()
            overlay.animate().alpha(1f).setDuration(300).start()
            overlay.requestFocus()
        }
    }

    /** The dim layer with a rounded hole that glides from one target to the next. */
    private class Spotlight(context: Context, private val dp: Float) : FrameLayout(context) {
        private val dim = Paint().apply { color = 0xD9000000.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0x80FFFFFF.toInt() }
        private val hole = RectF()
        private var from = RectF()
        private var to = RectF()

        init { setWillNotDraw(false); setLayerType(LAYER_TYPE_HARDWARE, null) }

        fun moveTo(target: RectF) {
            from = if (hole.isEmpty) RectF(target) else RectF(hole)
            to = target
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator = RowRecyclerView.EASE
                addUpdateListener {
                    val f = it.animatedValue as Float
                    hole.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                        from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f)
                    invalidate()
                }
                start()
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val r = 22 * dp
            canvas.drawRoundRect(hole, r, r, clear)
            canvas.drawRoundRect(hole, r, r, ring)
            super.dispatchDraw(canvas)
        }
    }
}
