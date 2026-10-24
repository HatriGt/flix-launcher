package com.homehalo.tv

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
    class Row(val source: Addons.Row, val view: View, val list: RecyclerView, val adapter: PosterAdapter) {
        var all: List<Catalog.Title> = emptyList()
    }

    val rows = mutableListOf<Row>()
    private val main = Handler(Looper.getMainLooper())
    private val dp = activity.resources.displayMetrics.density

    /** Rebuilds the rows from the current settings; data comes from the disk cache first. */
    fun rebuild() {
        container.removeAllViews()
        rows.clear()
        if (!HaloSettings.isOn(activity, HaloSettings.SUGGESTIONS)) return
        val genre = HaloSettings.get(activity, HaloSettings.GENRE).takeIf { it != "all" }
        val sources = Addons.rows(activity).filter { it.enabled }
        val (w, h) = when (HaloSettings.get(activity, HaloSettings.POSTER_SIZE)) {
            "small" -> 84 to 126
            "large" -> 112 to 168
            else -> 98 to 147
        }
        if (!NuvioAccount.isSignedIn(activity) && HaloSettings.isOn(activity, HaloSettings.CONTINUE_WATCHING) &&
            activity.packageManager.getLaunchIntentForPackage(MainActivity.NUVIO) != null) {
            val row = makeRow(CONTINUE, (w * dp).roundToInt(), (h * dp).roundToInt(), null)
            rows += row
            container.addView(row.view)
            show(row, listOf(SIGN_IN))
        }
        if (NuvioAccount.isSignedIn(activity) && HaloSettings.isOn(activity, HaloSettings.CONTINUE_WATCHING)) {
            val row = makeRow(CONTINUE, (w * dp).roundToInt(), (h * dp).roundToInt(), null)
            rows += row
            container.addView(row.view)
            row.view.visibility = View.GONE // shown once progress arrives
            refreshContinue(force = true)
        }
        for (source in sources) {
            val row = makeRow(source, (w * dp).roundToInt(), (h * dp).roundToInt(), genre)
            rows += row
            container.addView(row.view)
            load(row, genre)
        }
    }

    private var lastContinueAt = 0L

    /** Re-reads Nuvio watch progress, at most every couple of minutes. */
    fun refreshContinue(force: Boolean = false) {
        val row = rows.firstOrNull { it.source === CONTINUE } ?: return
        if (!force && System.currentTimeMillis() - lastContinueAt < 120_000) return
        lastContinueAt = System.currentTimeMillis()
        net.execute {
            val titles = runCatching { NuvioAccount.continueWatching(activity) }
                .onFailure { android.util.Log.w("HomeHalo", "Continue watching failed", it) }
                .getOrNull() ?: return@execute
            main.post { if (row in rows) show(row, titles) }
        }
    }

    private fun makeRow(source: Addons.Row, w: Int, h: Int, genre: String?): Row {
        val springy = HaloSettings.get(activity, HaloSettings.MOTION) == "spring"
        val adapter = PosterAdapter(w, h, dp, springy, onFocus, onOpen, onMenu)
        val title = TextView(activity).apply {
            val kind = if (source.type == "series") "Series" else "Movies"
            // Addons often reuse a name for their movie and series lists; tell them apart.
            val clash = Addons.rows(activity).count { it.enabled && it.title == source.title } > 1
            val base = if (clash) "${source.title} · $kind" else source.title
            text = if (genre == null || !source.supportsGenre) base else "$base · $genre"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((44 * dp).toInt(), (10 * dp).toInt(), 0, 0)
        }
        val grow = (8 * dp).roundToInt() // room for the focus ring; cards widen rather than scale
        val list = RowRecyclerView(activity).apply {
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

    /** Cached titles first (parsed off the main thread), then a refresh if they're older than six hours. */
    private fun load(row: Row, genre: String?) {
        net.execute {
            Catalog.cached(activity, row.source, genre)?.let { cached -> main.post { if (row in rows) show(row, cached) } }
            if (Catalog.isFresh(activity, row.source, genre)) return@execute
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

    companion object {
        /** Stand-in source for the Continue watching row, which comes from the Nuvio account rather than an addon. */
        val CONTINUE = Addons.Row("nuvio:continue", "Nuvio", "movie", "continue", "Continue watching")

        /** Placeholder card shown in Continue watching while signed out; opening it starts sign-in. */
        val SIGN_IN = Catalog.Title("halo:signin", "movie", "Sign in to Nuvio to see what you were watching",
            null, null, null, null, null, null, emptyList(), "Scan a code with your phone — it takes a few seconds.")
    }

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

    /** On 2 GB TVs (weak GPUs) the backdrop swaps on a single layer instead of crossfading two. */
    private val lite = (root.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
        .let { am -> android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem < 2_600_000_000L }

    init {
        // Both shading gradients baked into one small bitmap: one full-screen draw instead of two.
        val shadeW = 192; val shadeH = 108
        val bmp = android.graphics.Bitmap.createBitmap(shadeW, shadeH, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        root.context.getDrawable(R.drawable.hero_shade)!!.apply { setBounds(0, 0, shadeW, shadeH); draw(c) }
        (backdrops[0].parent as View).let { holder ->
            val parent = holder.parent as android.view.ViewGroup
            val shadeView = parent.getChildAt(parent.indexOfChild(holder) + 2)
            shadeView.background = android.graphics.drawable.BitmapDrawable(root.resources, bmp).apply { isFilterBitmap = true }
        }
    }
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
        main.postDelayed({ if (current?.id == title.id) apply(title) }, 120)
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
            if (lite) {
                val only = backdrops[0]
                backdrops[1].setImageDrawable(null)
                only.setImageBitmap(bitmap)
                only.alpha = 0.35f
                only.animate().alpha(1f).setDuration(200).start()
                return@get
            }
            val next = backdrops[1 - front]
            val prev = backdrops[front]
            next.setImageBitmap(bitmap)
            next.bringToFront()
            next.alpha = 0f
            next.scaleX = 1.06f
            next.scaleY = 1.06f
            next.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).start()
            prev.animate().alpha(0f).setStartDelay(300).setDuration(250).withEndAction { prev.setImageDrawable(null) }.start()
            front = 1 - front
        }
    }
}
