package com.flix.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Who's watching?": the Nuvio profiles as big round cartoon faces in a row, the name under
 * each, the current one marked. OK picks; the face lifts and gains a white ring on focus.
 */
object WhosWatching {
    fun show(activity: Activity, profiles: List<NuvioAccount.Profile>, current: NuvioAccount.Profile?, onPick: (NuvioAccount.Profile) -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        for (p in profiles) {
            val face = ImageView(activity).apply { foreground = ProfilePicture.ring(dp); isDuplicateParentStateEnabled = true }
            ProfilePicture.into(face, p)
            val isCurrent = p.index == current?.index
            val name = TextView(activity).apply {
                text = p.name.ifBlank { "Profile ${p.index}" }
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(if (isCurrent) Color.WHITE else 0xFF9A9A9A.toInt())
                typeface = Typeface.create("sans-serif-medium", if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setPadding(0, px(12), 0, 0)
            }
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                clipChildren = false; clipToPadding = false
                setPadding(px(12), px(12), px(12), px(12))
                addView(face, LinearLayout.LayoutParams(px(132), px(132)))
                addView(name, LinearLayout.LayoutParams(px(180), -2))
                setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(220).setInterpolator(RowRecyclerView.EASE).start()
                    name.setTextColor(if (has || isCurrent) Color.WHITE else 0xFF9A9A9A.toInt())
                }
                setOnClickListener { dialog.dismiss(); onPick(p) }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { if (isCurrent) v.post { v.requestFocus() } }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
            row.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(22); marginEnd = px(22) })
        }
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            setBackgroundColor(0xF2101214.toInt())
            addView(TextView(activity).apply {
                text = "Who's watching?"
                textSize = 36f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, px(40))
            })
            addView(row)
        }
        dialog.setContentView(page, ViewGroup.LayoutParams(-1, -1))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setWindowAnimations(0)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        page.alpha = 0f
        dialog.show()
        page.animate().alpha(1f).setDuration(300).start()
    }
}
