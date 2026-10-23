package com.tvlauncher.aura

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** The movie and series rows under the favourites, filled from [Catalog]. */
class MovieRows(
    private val activity: Activity,
    private val container: LinearLayout,
    private val net: Executor,
    private val onFocus: (Catalog.Title) -> Unit,
    private val onOpen: (Catalog.Title) -> Unit,
    private val onMenu: (Catalog.Title) -> Unit,
) {
    class Row(val source: Catalog.Source, val view: View, val list: RecyclerView, val adapter: PosterAdapter) {
        var all: List<Catalog.Title> = emptyList()
    }

    val rows = mutableListOf<Row>()
    private val main = Handler(Looper.getMainLooper())
    private val dp = activity.resources.displayMetrics.density

    /** Rebuilds the rows from the current settings; data comes from the disk cache first. */
    fun rebuild() {
        container.removeAllViews()
        rows.clear()
        if (!AuraSettings.isOn(activity, AuraSettings.SUGGESTIONS)) return
        val genre = AuraSettings.get(activity, AuraSettings.GENRE).takeIf { it != "all" }
        val sources = buildList {
            if (AuraSettings.isOn(activity, AuraSettings.ROW_POPULAR_MOVIES)) add(Catalog.POPULAR_MOVIES)
            if (AuraSettings.isOn(activity, AuraSettings.ROW_POPULAR_SERIES)) add(Catalog.POPULAR_SERIES)
            if (AuraSettings.isOn(activity, AuraSettings.ROW_TOP_RATED)) add(Catalog.TOP_RATED)
        }
        val (w, h) = when (AuraSettings.get(activity, AuraSettings.POSTER_SIZE)) {
            "small" -> 72 to 108
            "large" -> 100 to 150
            else -> 86 to 129
        }
        for (source in sources) {
            val row = makeRow(source, (w * dp).roundToInt(), (h * dp).roundToInt(), genre)
            rows += row
            container.addView(row.view)
            load(row, genre)
        }
    }

    private fun makeRow(source: Catalog.Source, w: Int, h: Int, genre: String?): Row {
        val springy = AuraSettings.get(activity, AuraSettings.MOTION) == "spring"
        val adapter = PosterAdapter(w, h, dp, springy, onFocus, onOpen, onMenu)
        val title = TextView(activity).apply {
            text = if (genre == null) source.title else "${source.title} · $genre"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            setPadding((44 * dp).toInt(), (10 * dp).toInt(), 0, 0)
        }
        val grow = (h * 0.06f + 10 * dp).roundToInt()
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
            setHasFixedSize(true)
            clipToPadding = false
            clipChildren = false
            setItemViewCacheSize(12)
            setPadding((37 * dp).toInt(), grow, (37 * dp).toInt(), grow)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h + grow * 2)
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(title)
            addView(list)
        }
        return Row(source, view, list, adapter)
    }

    private fun load(row: Row, genre: String?) {
        Catalog.cached(activity, row.source, genre)?.let { show(row, it) }
        if (Catalog.isFresh(activity, row.source, genre)) return
        net.execute {
            val titles = Catalog.fetch(activity, row.source, genre) ?: return@execute
            main.post { if (row in rows) show(row, titles) }
        }
    }

    private fun show(row: Row, titles: List<Catalog.Title>) {
        row.all = titles
        val hidden = Catalog.hiddenTitles(activity)
        row.adapter.items = titles.filter { it.id !in hidden }
        row.view.visibility = if (row.adapter.items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Re-applies the hidden-titles filter without refetching. */
    fun refreshHidden() = rows.forEach { show(it, it.all) }

    /** The row whose poster list contains [view], if any. */
    fun rowOf(view: View?): Row? = rows.firstOrNull { it.list === view?.parent }
}

/** The large preview at the top of the screen while a poster is focused. */
class Hero(root: View) {
    private val layer: View = root.findViewById(R.id.hero)
    private val backdrops = listOf<ImageView>(root.findViewById(R.id.hero_backdrop_a), root.findViewById(R.id.hero_backdrop_b))
    private val logo: ImageView = root.findViewById(R.id.hero_logo)
    private val name: TextView = root.findViewById(R.id.hero_title)
    private val meta: TextView = root.findViewById(R.id.hero_meta)
    private val description: TextView = root.findViewById(R.id.hero_description)
    private val info: View = root.findViewById(R.id.hero_info)
    private val main = Handler(Looper.getMainLooper())
    private val width = root.resources.displayMetrics.widthPixels
    private var front = 0
    private var current: Catalog.Title? = null
    var backdropEnabled = true

    val isShown get() = layer.alpha > 0f

    fun show() {
        layer.animate().cancel()
        layer.animate().alpha(1f).setDuration(350).start()
    }

    fun hide() {
        layer.animate().cancel()
        layer.animate().alpha(0f).setDuration(300).start()
        current = null
    }

    /** Switches to [title]; waits a beat so scrolling quickly along a row doesn't flicker through backdrops. */
    fun present(title: Catalog.Title) {
        if (current?.id == title.id) return
        current = title
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (current?.id == title.id) apply(title) }, 160)
    }

    private fun apply(title: Catalog.Title) {
        info.animate().cancel()
        info.animate().alpha(0f).setDuration(120).withEndAction {
            name.text = title.name
            meta.text = listOfNotNull(
                title.rating?.let { "★ $it" },
                title.year,
                title.runtime,
                title.genres.take(3).joinToString(" · ").ifBlank { null },
                if (title.type == "series") "Series" else null,
            ).joinToString("   ")
            description.text = title.description
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            name.visibility = View.VISIBLE
            title.logo?.let { url ->
                Images.get(url, (360 * logo.resources.displayMetrics.density).toInt()) { bitmap ->
                    if (bitmap == null || current?.id != title.id) return@get
                    logo.setImageBitmap(bitmap)
                    logo.visibility = View.VISIBLE
                    name.visibility = View.GONE
                }
            }
            info.translationY = 8f
            info.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }.start()

        val url = title.backdrop
        if (!backdropEnabled || url == null) {
            backdrops.forEach { it.animate().alpha(0f).setDuration(300).start() }
            return
        }
        Images.get(url, width / 2) { bitmap ->
            if (bitmap == null || current?.id != title.id) return@get
            val next = backdrops[1 - front]
            val prev = backdrops[front]
            next.setImageBitmap(bitmap)
            next.bringToFront()
            next.alpha = 0f
            next.scaleX = 1.06f
            next.scaleY = 1.06f
            next.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).start()
            prev.animate().alpha(0f).setStartDelay(400).setDuration(300).start()
            front = 1 - front
        }
    }
}
