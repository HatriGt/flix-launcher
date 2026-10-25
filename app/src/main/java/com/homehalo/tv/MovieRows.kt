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
    class Row(val source: Addons.Row, val view: View, val list: RecyclerView, val adapter: PosterAdapter,
              val details: View, val meta: TextView, val about: TextView) {
        var all: List<Catalog.Title> = emptyList()
    }

    /** The row whose details line is showing (only the focused row shows one, as on Netflix). */
    private var detailed: Row? = null

    /** Netflix's billboard at the top of the rows: one featured title with Play and More info. */
    val billboard = Billboard(activity, container) { onOpen(it) }

    val rows = mutableListOf<Row>()
    private val main = Handler(Looper.getMainLooper())
    private val dp = activity.resources.displayMetrics.density
    private val sharedPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 16) }

    /** Rebuilds the rows from the current settings; data comes from the disk cache first. */
    fun rebuild() {
        container.removeAllViews()
        rows.clear()
        detailed = null
        billboard.reset()
        if (!HaloSettings.isOn(activity, HaloSettings.SUGGESTIONS)) return
        val genre = HaloSettings.get(activity, HaloSettings.GENRE).takeIf { it != "all" }
        val sources = Addons.rows(activity).filter { it.enabled }
        // Tall posters, as on Netflix; the focused one widens to 16:9 at the same height.
        val (w, h) = when (HaloSettings.get(activity, HaloSettings.POSTER_SIZE)) {
            "small" -> 112 to 168
            "large" -> 144 to 216
            else -> 128 to 192
        }
        container.addView(billboard.view)
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
        // Top 10: by default ranked from the family's own first movie row (their language),
        // or Cinemeta's worldwide chart, or not at all.
        topFrom = null
        val topSetting = HaloSettings.get(activity, HaloSettings.TOP_TEN)
        val mine = sources.firstOrNull { it.type == "movie" }
        val topSource = when {
            topSetting == "world" -> TOP_TEN
            topSetting == "mine" && mine != null -> mine.copy(title = "Top 10 in ${mine.title}").also { topFrom = mine }
            else -> null
        }
        if (topSource != null) {
            val row = makeRow(topSource, (w * dp).roundToInt(), (h * dp).roundToInt(), null, ranked = true)
            rows += row
            container.addView(row.view)
            row.view.visibility = View.GONE
            topRow = row
            if (topSource === TOP_TEN) load(row, null)
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

    private fun makeRow(source: Addons.Row, w: Int, h: Int, genre: String?, ranked: Boolean = false): Row {
        val springy = HaloSettings.get(activity, HaloSettings.MOTION) == "spring"
        lateinit var row: Row
        val adapter = PosterAdapter(w, h, dp, springy, { t -> describe(row, t); onFocus(t) }, onOpen, onMenu, ranked)
        val title = TextView(activity).apply {
            val kind = if (source.type == "series") "Series" else "Movies"
            // Addons often reuse a name for their movie and series lists; tell them apart.
            val clash = Addons.rows(activity).count { it.enabled && it.title == source.title } > 1
            val base = if (clash) "${source.title} · $kind" else source.title
            text = if (genre == null || !source.supportsGenre) base else "$base · $genre"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.01f
            setPadding((44 * dp).toInt(), (6 * dp).toInt(), 0, 0)
        }
        val grow = (8 * dp).roundToInt() // room for the focus ring; cards widen rather than scale
        val list = RowRecyclerView(activity).apply {
            this.adapter = adapter
            itemAnimator = null
            setHasFixedSize(true)
            clipToPadding = false
            clipChildren = false
            // A few spare cards per row, and one pool shared by all rows: idle rows no longer
            // each keep a dozen detached cards alive.
            setItemViewCacheSize(4)
            setRecycledViewPool(sharedPool)
            setPadding((37 * dp).toInt(), grow, (37 * dp).toInt(), grow)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * PosterAdapter.GROW).toInt() + grow * 2)
            if (ranked) addItemDecoration(RankNumbers(adapter.rankRoom, dp))
        }
        // Netflix puts the focused title's details under the row, not over the artwork.
        val meta = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }
        val about = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(0xB3FFFFFF.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        val details = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((44 * dp).toInt() + if (ranked) adapter.rankRoom - (7 * dp).toInt() else 0, (2 * dp).toInt(), (44 * dp).toInt(), (6 * dp).toInt())
            visibility = View.GONE
            // Fixed sizes: changing the text then only redraws it instead of re-laying out the page.
            addView(meta, LinearLayout.LayoutParams((760 * dp).toInt(), (20 * dp).toInt()))
            addView(about, LinearLayout.LayoutParams((560 * dp).toInt(), (44 * dp).toInt()))
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(title)
            addView(list)
            addView(details)
        }
        row = Row(source, view, list, adapter, details, meta, about)
        return row
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

    /** Fills the focused row's details line: rating, year, runtime, genres, then the synopsis. */
    private fun describe(row: Row, t: Catalog.Title) {
        row.meta.text = android.text.SpannableStringBuilder().apply {
            if (t.type == "series") append("Series    ")
            Rating.text(t.rating)?.let { append(it).append("    ") }
            append(listOfNotNull(t.year, runtimeText(t.runtime), t.genres.take(3).joinToString(" · ").ifBlank { null }).joinToString("    "))
        }
        row.about.text = t.description
        if (detailed !== row) {
            detailed?.details?.visibility = View.GONE
            row.details.visibility = View.VISIBLE
            detailed = row
        }
    }

    /** Hides the details line when focus leaves the rows. */
    fun clearDetails() {
        detailed?.details?.visibility = View.GONE
        detailed = null
    }

    /** Shows only movie or only series rows (the Movies and Shows tabs), or all of them. */
    fun filter(type: String?) {
        rows.forEach { r ->
            val match = type == null || r.source.type == type || r.source === CONTINUE
            r.view.visibility = if (match && r.adapter.items.isNotEmpty()) View.VISIBLE else View.GONE
        }
        typeFilter = type
        billboard.feature(type)
    }

    private var typeFilter: String? = null

    /** The ranked row, and the catalogue it takes its ten from when it's the family's own. */
    private var topRow: Row? = null
    private var topFrom: Addons.Row? = null

    private fun fillTop(row: Row, titles: List<Catalog.Title>) {
        val hidden = Catalog.hiddenTitles(activity)
        row.adapter.items = titles.filter { it.poster != null && it.id !in hidden }.take(10)
        val match = typeFilter == null || typeFilter == "movie"
        row.view.visibility = if (row.adapter.items.isEmpty() || !match) View.GONE else View.VISIBLE
    }

    private fun show(row: Row, titles: List<Catalog.Title>) {
        row.all = titles
        if (row === topRow) return fillTop(row, titles)
        if (row.source === topFrom) topRow?.let { fillTop(it, titles) }
        if (row.source !== CONTINUE) billboard.offer(titles)
        val hidden = Catalog.hiddenTitles(activity)
        row.adapter.items = titles.filter { it.id !in hidden }
        val match = typeFilter == null || row.source.type == typeFilter || row.source === CONTINUE
        row.view.visibility = if (row.adapter.items.isEmpty() || !match) View.GONE else View.VISIBLE
    }

    /** Re-applies the hidden-titles filter without refetching. */
    fun refreshHidden() = rows.forEach { show(it, it.all) }

    companion object {
        /** Stand-in source for the Continue watching row, which comes from the Nuvio account rather than an addon. */
        val CONTINUE = Addons.Row("nuvio:continue", "Nuvio", "movie", "continue", "Continue watching")

        /** Cinemeta's most popular films right now, shown as Netflix's numbered Top 10 row. */
        val TOP_TEN = Addons.Row(Addons.CINEMETA, "Cinemeta", "movie", "top", "Top 10 Movies Today")

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

    /** Netflix's small "N SERIES" line above the title: a red bar and the type, spaced out. */
    private val kind: TextView = TextView(root.context).apply {
        textSize = 12f
        letterSpacing = 0.3f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val d = root.resources.displayMetrics.density
        setCompoundDrawablesRelativeWithIntrinsicBounds(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFE50914.toInt()); setSize((4 * d).toInt(), (16 * d).toInt())
        }, null, null, null)
        compoundDrawablePadding = (10 * d).toInt()
        setPadding(0, 0, 0, (8 * d).toInt())
    }.also { (info as android.view.ViewGroup).addView(it, 0) }
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
    /**
     * Ambient light: a soft glow in the focused title's own colour rising from the bottom
     * of the screen behind the rows, the way a lit room picks up the colour of the film.
     */
    // A radial gradient computed for every pixel of the screen cost this TV's GPU ~6 ms a
    // frame; instead a tiny pre-drawn falloff is stretched over the lower-left of the screen
    // (where the rows are) and tinted, which costs a fraction of that.
    private val glow = android.graphics.drawable.BitmapDrawable(root.resources, falloff()).apply { isFilterBitmap = true }
    // Left out on lite TVs (weak GPUs), where even this tinted layer costs ~3 ms a frame.
    private val glowView = View(root.context).apply { background = glow; alpha = 0f; if (lite) visibility = View.GONE }.also { v ->
        val shade = root.findViewById<View>(R.id.hero_shade)
        val parent = shade.parent as android.view.ViewGroup
        val m = root.resources.displayMetrics
        parent.addView(v, parent.indexOfChild(shade) + 1,
            android.widget.FrameLayout.LayoutParams((m.widthPixels * 0.8f).toInt(), (m.heightPixels * 0.8f).toInt(),
                android.view.Gravity.BOTTOM or android.view.Gravity.START))
    }

    /** White with alpha falling off smoothly from the bottom-left corner. */
    private fun falloff(): android.graphics.Bitmap {
        val n = 48
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until n) for (x in 0 until n) {
            val d = kotlin.math.hypot(x / (n - 1f), (n - 1 - y) / (n - 1f)).coerceAtMost(1f)
            val a = ((1 - d) * (1 - d) * 255).toInt()
            bmp.setPixel(x, y, (a shl 24) or 0xFFFFFF)
        }
        return bmp
    }

    private var glowAnim: android.animation.ValueAnimator? = null
    private var glowNow = 0

    private fun ambient(target: Int) {
        if (lite) return
        val to = (target and 0x00FFFFFF) or 0xFF000000.toInt()
        if (glowNow == to) return
        val from = glowNow
        glowAnim?.cancel()
        // Just under half strength at its brightest, so it tints rather than glares.
        glowView.animate().alpha(0.45f).setDuration(500).start()
        glowAnim = android.animation.ValueAnimator.ofArgb(if (from == 0) to else from, to).apply {
            duration = 700
            addUpdateListener {
                glowNow = it.animatedValue as Int
                glow.setTint(glowNow)
            }
            start()
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
        // Waits until the card has finished growing, so the two big animations never share frames.
        main.postDelayed({ if (current?.id == title.id) apply(title) }, RowRecyclerView.DURATION + 60)
    }

    private fun apply(title: Catalog.Title) {
        info.animate().cancel()
        info.animate().alpha(0f).setDuration(120).withEndAction {
            name.text = title.name
            kind.text = if (title.type == "series") "SERIES" else "FILM"
            // Netflix leads with a highlighted score; ours is the rating, in gold.
            val rating = Rating.text(title.rating)
            val rest = listOfNotNull(title.year, runtimeText(title.runtime),
                title.genres.take(3).joinToString(" • ").ifBlank { null }).joinToString("    ")
            meta.text = android.text.SpannableStringBuilder().apply {
                if (rating != null) append(rating).append("    ")
                append(rest)
            }
            meta.visibility = if (meta.text.isBlank()) View.GONE else View.VISIBLE
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

        val art = title.poster ?: title.backdrop
        if (art == null) ambient(CoverArt.accentOf(title.name))
        else Images.accent(art) { c -> if (current?.id == title.id) ambient(c ?: CoverArt.accentOf(title.name)) }

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
            next.animate().alpha(1f).setDuration(400).start()
            prev.animate().alpha(0f).setStartDelay(300).setDuration(250).withEndAction { prev.setImageDrawable(null) }.start()
            front = 1 - front
        }
    }
}

/** "122" or "122 min" becomes "2h 2m", as Netflix writes it. */
fun runtimeText(raw: String?): String? {
    val m = raw?.filter { it.isDigit() }?.toIntOrNull() ?: return raw
    if (raw.contains("h")) return raw
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/** Draws the Top 10 row's huge outlined rank numbers in the space left of each poster. */
class RankNumbers(@Suppress("unused") private val room: Int, private val dp: Float) : RecyclerView.ItemDecoration() {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0A0D13.toInt()
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.RIGHT
        letterSpacing = -0.08f
    }
    private val edge = android.graphics.Paint(fill).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = 0xB3FFFFFF.toInt()
    }

    override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos !in 0..9) continue
            fill.textSize = child.height * 0.98f
            edge.textSize = fill.textSize
            // Right-aligned to the poster's left edge and tucked a little behind it.
            val x = child.x + 14 * dp
            val y = child.y + child.height - 2 * dp
            val text = (pos + 1).toString()
            c.drawText(text, x, y, fill)
            c.drawText(text, x, y, edge)
        }
    }
}
