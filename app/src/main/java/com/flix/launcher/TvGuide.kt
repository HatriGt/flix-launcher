package com.flix.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "TV guide" home layout: two calm lists, like a channel guide. On the left, what you
 * were watching with the episode and time left; on the right, what's popular from your first
 * row. Small pictures, big text, no motion: for people who mostly watch live TV and apps and
 * just want to see what's next. Uses the titles [MovieRows] already loaded.
 */
class TvGuide(
    private val activity: Activity,
    private val rows: () -> List<MovieRows.Row>,
    private val onOpen: (Catalog.Title) -> Unit,
    private val onMenu: (Catalog.Title) -> Unit,
) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val resumeList = column()
    private val popularList = column()
    private val leftTitle = heading()
    private val rightTitle = heading()

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(44), px(14), px(44), px(24))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(leftTitle)
            addView(resumeList)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(20) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(rightTitle)
            addView(popularList)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** What the lists show, so a refresh with the same titles doesn't redraw (and drop focus). */
    private var shownIds: List<String>? = null

    fun refresh() {
        val visible = rows().filter { it.visible }
        val resume = visible.firstOrNull { it.source === MovieRows.CONTINUE }
        val popular = visible.firstOrNull { it.source !== MovieRows.CONTINUE }
        val leftItems = resume?.shown.orEmpty().take(MAX)
        val rightItems = popular?.shown.orEmpty().take(MAX)
        val ids = (leftItems + rightItems).map { it.id + it.episodeLabel + it.progress }
        if (ids == shownIds) return
        shownIds = ids
        leftTitle.text = if (resume != null) "Continue watching" else ""
        rightTitle.text = popular?.label.orEmpty()
        fill(resumeList, leftItems)
        fill(popularList, rightItems)
    }

    private fun fill(list: LinearLayout, titles: List<Catalog.Title>) {
        val focused = (0 until list.childCount).firstOrNull { list.getChildAt(it).isFocused }
        list.removeAllViews()
        titles.forEach { list.addView(item(it)) }
        focused?.let { list.getChildAt(it.coerceAtMost(list.childCount - 1))?.requestFocus() }
    }

    private fun item(t: Catalog.Title): View {
        val bg = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.TRANSPARENT) }
        val thumb = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 8 * dp) }
            }
            setBackgroundColor(0x1FFFFFFF)
        }
        (t.backdrop ?: t.poster)?.let { url -> Images.into(thumb, url, px(THUMB_W)) }
        val progress = t.progress
        // Track and fill in one drawable: the fill is clipped to the share watched.
        val bar = View(activity).apply {
            visibility = if (progress != null && progress > 0f) View.VISIBLE else View.GONE
            val fill = android.graphics.drawable.ClipDrawable(
                GradientDrawable().apply { cornerRadius = 2 * dp; setColor(Look.accent(activity)) }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            fill.level = ((progress ?: 0f).coerceIn(0.03f, 1f) * 10_000).toInt()
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = 2 * dp; setColor(0x33FFFFFF) }, fill))
        }
        val sub = listOfNotNull(
            t.episodeLabel,
            t.minutesLeft?.let { "$it min left" },
            t.year.takeIf { t.progress == null },
            Rating.text(t.rating)?.toString()?.takeIf { t.progress == null },
            t.genres.firstOrNull()?.takeIf { t.progress == null },
        ).joinToString("  ·  ")
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = bg
            setPadding(px(8), px(7), px(12), px(7))
            addView(thumb, LinearLayout.LayoutParams(px(THUMB_W), px(THUMB_W * 9 / 16)).apply { marginEnd = px(14) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(t.name, 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (sub.isNotEmpty()) addView(text(sub, 12.5f, alpha = 0.6f).apply { setPadding(0, px(2), 0, 0); maxLines = 1 })
                addView(bar, LinearLayout.LayoutParams(px(140), px(3)).apply { topMargin = px(7) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnFocusChangeListener { _, has ->
                bg.setColor(if (has) 0x24FFFFFF else Color.TRANSPARENT)
                bg.setStroke(px(2), if (has) Color.WHITE else Color.TRANSPARENT)
            }
            setOnClickListener { onOpen(t) }
            setOnLongClickListener { onMenu(t); true }
        }
        return row
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun heading() = text("", 15f, bold = true).apply { setPadding(px(8), 0, 0, px(8)) }

    private fun text(s: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(activity).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private companion object {
        const val MAX = 6
        const val THUMB_W = 120
    }
}
