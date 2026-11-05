package com.flix.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The "Poster wall" home layout: one shelf at a time as a dense grid of posters, picked from a
 * strip of names above it (Continue watching, then each addon row). For people who choose a
 * film by its artwork. It shows the titles [MovieRows] already loaded; nothing extra is fetched.
 */
class PosterWall(
    private val activity: Activity,
    private val rows: () -> List<MovieRows.Row>,
    private val onOpen: (Catalog.Title) -> Unit,
    private val onMenu: (Catalog.Title) -> Unit,
) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val main = Handler(Looper.getMainLooper())

    private val chips = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(44), px(4), px(44), px(4))
    }
    private val details = TextView(activity).apply {
        textSize = 13f
        setTextColor(0xB3FFFFFF.toInt())
        maxLines = 1
        setPadding(px(48), px(8), px(48), 0)
    }
    private val adapter = PosterAdapter(px(POSTER_W), px(POSTER_W * 3 / 2), dp, springy = false,
        onFocus = ::describe, onClick = onOpen, onLongClick = onMenu, grows = false)
    private val grid = RecyclerView(activity).apply {
        layoutManager = GridLayoutManager(activity, COLUMNS)
        adapter = this@PosterWall.adapter
        itemAnimator = null
        isNestedScrollingEnabled = false
        clipChildren = false
        clipToPadding = false
        setPadding(px(38), px(10), px(38), px(24))
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                out.set(px(5), px(8), px(5), px(8))
            }
        })
    }

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, px(10), 0, 0)
        addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chips)
        })
        addView(details)
        addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The shelf on show, by its row key, kept across refreshes. */
    private var selected: String? = null

    /** Re-reads the rows: new titles arrived, or the Movies/Shows tab changed. */
    fun refresh() {
        val shelves = rows().filter { it.visible && it.shown.any { t -> t.id != MovieRows.SIGN_IN.id } }
        val pick = shelves.firstOrNull { it.source.key == selected } ?: shelves.firstOrNull()
        selected = pick?.source?.key
        // Rebuild the chips only when the set of shelves changed, so focus on one isn't lost.
        val keys = shelves.map { it.source.key }
        if (keys != chips.tag) {
            chips.tag = keys
            val focusedKey = (0 until chips.childCount).map { chips.getChildAt(it) }.firstOrNull { it.isFocused }?.tag
            chips.removeAllViews()
            shelves.forEach { row -> chips.addView(chip(row), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(10) }) }
            focusedKey?.let { k -> (0 until chips.childCount).map { chips.getChildAt(it) }.firstOrNull { it.tag == k }?.requestFocus() }
        }
        paintChips()
        val items = pick?.shown.orEmpty().filter { it.id != MovieRows.SIGN_IN.id }.take(MAX)
        if (adapter.items.map { it.id } != items.map { it.id }) adapter.items = items
    }

    private fun chip(row: MovieRows.Row) = TextView(activity).apply {
        text = row.label
        tag = row.source.key
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true
        setPadding(px(18), px(9), px(18), px(9))
        // Landing on a name switches to it, as the top menu does, after a short pause so
        // sliding across names doesn't redraw the grid for each one.
        val pick = Runnable { if (isFocused && selected != row.source.key) { selected = row.source.key; refresh() } }
        setOnFocusChangeListener { _, has -> paintChips(); main.removeCallbacks(pick); if (has) main.postDelayed(pick, 300) }
        setOnClickListener { selected = row.source.key; refresh(); grid.post { grid.getChildAt(0)?.requestFocus() } }
    }

    private fun paintChips() {
        for (i in 0 until chips.childCount) (chips.getChildAt(i) as TextView).apply {
            val on = tag == selected
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(if (on) Color.WHITE else 0x1FFFFFFF)
                if (isFocused) setStroke(px(2), if (on) Look.focus(activity) else Color.WHITE)
            }
            // Brightness through the text colour: the view's alpha is the page's to fade lines with.
            setTextColor(if (on) Color.BLACK else if (isFocused) Color.WHITE else 0xCCFFFFFF.toInt())
        }
    }

    private fun describe(t: Catalog.Title) {
        details.text = listOfNotNull(t.name, t.episodeLabel, t.year, Rating.text(t.rating)?.toString(), t.genres.take(2).joinToString(" · ").ifBlank { null })
            .joinToString("   ·   ")
    }

    private companion object {
        const val COLUMNS = 7
        const val POSTER_W = 112
        const val MAX = 42
    }
}
