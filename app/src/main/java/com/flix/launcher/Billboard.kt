package com.flix.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Netflix's billboard: a large rounded card under the dock featuring a carousel of up to
 * [MAX] titles, with artwork, logo, details and Play / More info buttons.
 *
 * Titles come from the user's own catalogue rows (each [offer] is one row). Every row
 * contributes its best one or two titles with a backdrop, ranked by IMDb rating, then by
 * having a description and a logo, then by recency; the picks are interleaved row by row
 * so the carousel is varied. While the card is unfocused it advances every [INTERVAL_MS]
 * with a crossfade of two stacked backdrops; when focused, LEFT on Play / RIGHT on
 * More info step to the previous / next title. Dots at the bottom-right show the position.
 */
class Billboard(private val activity: Activity, parent: ViewGroup, private val onOpen: (Catalog.Title) -> Unit) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    // Two stacked backdrops: the front one fades in over the back one, then the back is released.
    private val backdrops = List(2) { ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }
    private var front = 0
    private val dots = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private lateinit var info: LinearLayout
    private val logo = ImageView(activity).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
    private val kind = TextView(activity).apply {
        textSize = 11f
        letterSpacing = 0.3f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            setColor(Look.accent(activity)); setSize(px(4), px(14))
        }, null, null, null)
        compoundDrawablePadding = px(8)
    }
    private val name = TextView(activity).apply {
        textSize = 30f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val meta = TextView(activity).apply {
        textSize = 12.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val about = TextView(activity).apply {
        textSize = 12.5f
        setTextColor(0xCCFFFFFF.toInt())
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.15f)
    }
    val play = button("▶   Play", primary = true)
    private val more = button("ⓘ   More info", primary = false)

    val view: View = FrameLayout(activity).apply {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, Look.of(activity).billboardRadiusDp * dp)
        }
        setBackgroundColor(0xFF10141C.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(250)).apply {
            marginStart = px(44); marginEnd = px(44); topMargin = px(4); bottomMargin = px(10)
        }
        visibility = View.GONE
        // The photos get their own layer, so swapping which one is on top never moves the text.
        addView(FrameLayout(activity).apply { backdrops.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) } },
            FrameLayout.LayoutParams(-1, -1))
        addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF20A0D13.toInt(), 0xB30A0D13.toInt(), 0x000A0D13))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(LinearLayout(activity).apply {
            info = this
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(32), px(18), 0, px(18))
            addView(kind)
            addView(logo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(64)).apply { topMargin = px(10) })
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
            addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
            addView(about, LinearLayout.LayoutParams(px(400), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(play)
                addView(more, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(12) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        }, FrameLayout.LayoutParams(px(560), -1))
        addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { marginEnd = px(24); bottomMargin = px(18) })
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = schedule()
            override fun onViewDetachedFromWindow(v: View) = handler.removeCallbacks(tick)
        })
    }

    /** Each offered row's titles that have a backdrop, in arrival order. */
    private val rows = mutableListOf<List<Catalog.Title>>()
    private var type: String? = null
    private var items: List<Catalog.Title> = emptyList()
    private var index = 0
    private val featured get() = items.getOrNull(index)
    private var paused = false

    /** The title the user was last watching; leads the carousel with a Resume button. */
    private var resume: Catalog.Title? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable {
        // The user drives the carousel while the card has focus; otherwise it rolls on.
        if (!view.hasFocus() && view.isShown) step(1)
        schedule()
    }

    init {
        play.setOnClickListener { featured?.let(onOpen) }
        more.setOnClickListener { featured?.let(onOpen) }
        play.setOnKeyListener { _, code, e -> code == KeyEvent.KEYCODE_DPAD_LEFT && stepKey(e, -1) }
        more.setOnKeyListener { _, code, e -> code == KeyEvent.KEYCODE_DPAD_RIGHT && stepKey(e, 1) }
    }

    private fun stepKey(e: KeyEvent, by: Int): Boolean {
        if (items.size < 2) return false
        if (e.action == KeyEvent.ACTION_DOWN) { step(by); schedule() }
        return true
    }

    private fun button(label: String, primary: Boolean) = TextView(activity).apply {
        text = label
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(px(20), px(8), px(22), px(8))
        isFocusable = true
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        // Netflix: Play is solid white; More info is a translucent pill. Focus adds a bright ring.
        background = StateListDrawable().apply {
            setEnterFadeDuration(120)
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(if (primary) Color.WHITE else 0x66FFFFFF)
                setStroke(px(3), Look.focus(activity))
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(if (primary) 0xF2FFFFFF.toInt() else 0x40FFFFFF)
            })
        }
    }

    /** Offered each catalogue row as it loads; its best titles join the carousel. */
    fun offer(titles: List<Catalog.Title>) {
        // Real titles only: addons slip in promo cards like "🎬 New Hindi Dubbed Catalog!".
        val usable = titles.take(20).filter { it.backdrop != null && !isPromo(it.name) }
        if (usable.isEmpty()) return
        rows += usable
        rebuild()
    }

    /**
     * Pins [title] (the newest Continue watching entry) as the carousel's first card, with
     * a Resume button that goes straight to its streams. Null removes it.
     */
    fun resumeWith(title: Catalog.Title?) {
        val usable = title?.takeIf { (it.backdrop ?: it.poster) != null }
        if (usable?.id == resume?.id && usable?.episode == resume?.episode) return
        resume = usable
        rebuild()
    }

    /** Whether the carousel is showing with a Continue watching title pinned first. */
    val hasResume get() = resume != null && view.visibility == View.VISIBLE && items.firstOrNull() === resume

    /** Turns the carousel back to the pinned Continue watching title. */
    fun showResume() {
        if (!hasResume) return
        if (index != 0) { index = 0; show(animate = true) }
        schedule()
    }

    /** Limits the carousel to [type] ("movie", "series" or null for any), as the Movies and Shows tabs ask. */
    fun feature(type: String?) {
        if (type == this.type && items.isNotEmpty()) return
        this.type = type
        rebuild()
    }

    /** Forgets every row so the next load builds a fresh carousel. */
    fun reset() {
        handler.removeCallbacks(tick)
        rows.clear()
        items = emptyList()
        index = 0
        view.visibility = View.GONE
        backdrops.forEach { it.animate().cancel(); it.setImageDrawable(null) }
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
        dots.removeAllViews()
    }

    /** Stops auto-advance (e.g. when the home screen is covered). */
    fun pause() { paused = true; handler.removeCallbacks(tick) }

    fun resume() { paused = false; schedule() }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (!paused && items.size > 1 && view.isAttachedToWindow) handler.postDelayed(tick, INTERVAL_MS)
    }

    private fun isPromo(name: String) =
        !name.trimStart().first().isLetterOrDigit() || PROMO.containsMatchIn(name)

    private fun score(t: Catalog.Title): Float {
        val rating = t.rating?.trim()?.toFloatOrNull()?.takeIf { it in 1f..10f } ?: 5f
        val year = t.year?.take(4)?.toIntOrNull() ?: 2000
        return rating * 10 + (if (t.description.isNullOrBlank()) 0 else 8) + (if (t.logo != null) 6 else 0) +
            (year - 2000).coerceIn(0, 30) * 0.5f
    }

    /** Best two per row, interleaved across rows (row1 #1, row2 #1, …, row1 #2, …), capped at [MAX]. */
    private fun rebuild() {
        val perRow = rows.map { row -> row.filter { type == null || it.type == type }.sortedByDescending(::score).take(2) }
        val picked = mutableListOf<Catalog.Title>()
        for (rank in 0 until 2) for (row in perRow) {
            val t = row.getOrNull(rank) ?: continue
            if (picked.size < MAX && picked.none { it.id == t.id }) picked += t
        }
        resume?.takeIf { type == null || it.type == type }?.let { r -> picked.removeAll { it.id == r.id }; picked.add(0, r) }
        val current = featured
        items = picked
        if (picked.isEmpty()) { view.visibility = View.GONE; handler.removeCallbacks(tick); return }
        val keep = current?.takeIf { it !== picked.firstOrNull() || it === resume }?.let { c -> picked.indexOfFirst { it.id == c.id } } ?: -1
        if (keep >= 0) { index = keep; drawDots() } else { index = 0; show(animate = false) }
        view.visibility = View.VISIBLE
        schedule()
    }

    private fun step(by: Int) {
        if (items.size < 2) return
        index = (index + by).mod(items.size)
        show(animate = true)
    }

    private fun show(animate: Boolean) {
        val pick = featured ?: return
        bind(pick)
        drawDots()
        if (animate) info.apply { alpha = 0f; animate().alpha(1f).setDuration(FADE_MS).start() }

        val back = backdrops[front]
        front = 1 - front
        val img = backdrops[front]
        img.animate().cancel()
        img.setImageDrawable(null)
        img.alpha = 0f
        img.bringToFront()
        // Decoded at the billboard's own width (it's narrower than the screen), saving GPU memory.
        val width = img.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        Images.get((pick.backdrop ?: pick.poster)!!, width) { bmp ->
            if (featured !== pick || bmp == null) return@get
            img.setImageBitmap(bmp)
            img.animate().alpha(1f).setDuration(if (animate) FADE_MS * 2 else 0)
                .withEndAction { if (featured === pick) back.setImageDrawable(null) }.start()
        }
        // Warm the cache for the next title only.
        items.getOrNull((index + 1) % items.size)?.takeIf { it !== pick }?.backdrop?.let { Images.get(it, width) { } }
    }

    private fun bind(pick: Catalog.Title) {
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
        val resuming = pick === resume
        kind.text = when {
            resuming && pick.progress == 0f -> "UP NEXT"
            resuming -> "CONTINUE WATCHING"
            pick.type == "series" -> "SERIES"
            else -> "FILM"
        }
        play.text = if (resuming) resumeLabel(pick) else "▶   Play"
        name.text = pick.name
        name.visibility = View.VISIBLE
        meta.text = android.text.SpannableStringBuilder().apply {
            Rating.text(pick.rating)?.let { append(it).append("    ") }
            append(listOfNotNull(pick.year, runtimeText(pick.runtime), pick.genres.take(3).joinToString(" · ").ifBlank { null }).joinToString("    "))
        }
        about.text = pick.description
        pick.logo?.let { url ->
            Images.get(url, px(300)) { bmp ->
                if (bmp == null || featured !== pick) return@get
                logo.setImageBitmap(bmp); logo.visibility = View.VISIBLE; name.visibility = View.GONE
            }
        }
    }

    private fun resumeLabel(t: Catalog.Title): String {
        val verb = if ((t.progress ?: 0f) > 0f) "Resume" else "Play"
        val left = t.minutesLeft?.takeIf { it > 0 && (t.progress ?: 0f) > 0f }?.let { if (it >= 60) "${it / 60} h ${it % 60} min left" else "$it min left" }
        return "▶   " + listOfNotNull(verb + (t.episodeLabel?.let { " $it" } ?: ""), left).joinToString("  ·  ")
    }

    private fun drawDots() {
        dots.removeAllViews()
        if (items.size < 2) return
        items.indices.forEach { i ->
            val on = i == index
            dots.addView(View(activity).apply {
                background = GradientDrawable().apply { cornerRadius = 2 * dp; setColor(if (on) Color.WHITE else 0x66FFFFFF) }
            }, LinearLayout.LayoutParams(px(if (on) 18 else 6), px(4)).apply { marginStart = px(5) })
        }
    }

    private companion object {
        private val PROMO = Regex("catalog|addon|update|now available", RegexOption.IGNORE_CASE)
        const val MAX = 6
        const val INTERVAL_MS = 8000L
        const val FADE_MS = 300L
    }
}
