package com.flix.launcher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Poster cards for one catalogue row, laid out like Netflix's 2025 TV home: tall posters,
 * and the focused one widens (at the same height) into a landscape card with the title's
 * backdrop, logo and badges. Its details are shown under the row (see [MovieRows]).
 * In a [ranked] (Top 10) row each card leaves room on its left for a big rank number.
 */
class PosterAdapter(
    private val widthPx: Int,
    private val heightPx: Int,
    private val density: Float,
    private val springy: Boolean,
    private val onFocus: (Catalog.Title) -> Unit,
    private val onClick: (Catalog.Title) -> Unit,
    private val onLongClick: (Catalog.Title) -> Unit,
    private val ranked: Boolean = false,
) : RecyclerView.Adapter<PosterAdapter.Holder>() {

    var items: List<Catalog.Title> = emptyList()
        set(value) {
            // Addons sometimes list a title twice; two cards with one stable id confuse the row.
            field = value.distinctBy { it.id }
            notifyDataSetChanged()
        }

    init {
        setHasStableIds(true)
    }

    /** Each title id gets its own number, so ids can't collide the way hash codes can. */
    private val ids = HashMap<String, Long>()

    override fun getItemId(position: Int) = ids.getOrPut(items[position].id) { ids.size.toLong() }

    /**
     * Cards are shared between rows through one pool, but only between rows whose cards are
     * built the same way (same size, ranked or not); see [typeFor].
     */
    val viewType = typeFor(widthPx, heightPx, ranked)

    override fun getItemViewType(position: Int) = viewType

    private val radius = 12 * density

    override fun getItemCount() = items.size

    /** The focused card grows in both directions, into a 16:9 landscape a third taller than a poster. */
    private val expandedH = (heightPx * GROW).toInt()
    private val expandedPx = expandedH * 16 / 9
    private val inset = (expandedH - heightPx) / 2

    /** Space left of each card in a Top 10 row for its rank number. */
    val rankRoom = (heightPx * 0.42f).toInt()

    /** The card in this row that's currently big, if any. */
    private var big: Holder? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = Card(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx).apply {
                marginStart = if (ranked) rankRoom else (7 * density).toInt()
                marginEnd = (7 * density).toInt()
                topMargin = inset
            }
            isFocusable = true
            foreground = StateListDrawable().apply {
                setEnterFadeDuration(RowRecyclerView.DURATION.toInt())
                setExitFadeDuration(120)
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = radius
                    setStroke((3 * density).toInt(), Color.WHITE)
                })
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
        }
        val fallback = TextView(context).apply {
            // Sits on the generated cover, bottom-left like a printed title.
            gravity = Gravity.BOTTOM or Gravity.START
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(0f, 1.05f)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(8f, 0f, 1f, 0x80000000.toInt())
            setPadding((11 * density).toInt(), 0, (11 * density).toInt(), (12 * density).toInt())
        }
        val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val badge = TextView(context).apply {
            textSize = 9.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding((4 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(0xD9090B10.toInt())
            }
        }
        // Continue watching: a progress bar and episode tag along the bottom.
        val shade = View(context).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0)
            )
        }
        val episode = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        // Shown only on the focused, widened card.
        val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val info = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2000000.toInt(), 0x8C000000.toInt(), 0, 0))
            gravity = Gravity.BOTTOM
            alpha = 0f
        }
        val logo = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
        val name = TextView(context).apply {
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val meta = TextView(context).apply {
            textSize = 11.5f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        info.addView(logo, android.widget.LinearLayout.LayoutParams(-2, (54 * density).toInt()))
        info.addView(name)
        // Netflix's badges sit bottom-right of the big card: "Recently added", "Top 10".
        meta.gravity = Gravity.END
        meta.textSize = 10.5f
        info.addView(meta, android.widget.LinearLayout.LayoutParams(-1, -2))
        // Continue watching on the big card: how far in, as a bar and a percentage.
        val watched = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val watchedTrack = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = 2 * density; setColor(0x4DFFFFFF) }
            clipToOutline = true
        }
        val watchedBar = View(context).apply { setBackgroundColor(Look.accent(context)) }
        watchedTrack.addView(watchedBar, FrameLayout.LayoutParams(0, -1))
        val watchedText = TextView(context).apply {
            textSize = 10.5f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((8 * density).toInt(), 0, 0, 0)
        }
        watched.addView(watchedTrack, android.widget.LinearLayout.LayoutParams(WATCHED_W.times(density).toInt(), (4 * density).toInt()))
        watched.addView(watchedText)
        info.addView(watched)
        val track = View(context).apply { setBackgroundColor(0x59FFFFFF) }
        val bar = View(context).apply { setBackgroundColor(Look.accent(context)) }
        card.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        // Poster width even when the card widens: stretched to 16:9 it showed a zoomed crop that
        // looked like another title's art until the backdrop faded in over it.
        card.addView(poster, FrameLayout.LayoutParams(widthPx, -1))
        card.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        card.addView(info, FrameLayout.LayoutParams(-1, -1))
        card.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (5 * density).toInt()
            marginEnd = (5 * density).toInt()
        })
        val barHeight = (4 * density).toInt()
        card.addView(shade, FrameLayout.LayoutParams(-1, (h(0.32f)), Gravity.BOTTOM))
        card.addView(episode, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = (7 * density).toInt()
            bottomMargin = (9 * density).toInt()
        })
        card.addView(track, FrameLayout.LayoutParams(-1, barHeight, Gravity.BOTTOM))
        card.addView(bar, FrameLayout.LayoutParams(0, barHeight, Gravity.BOTTOM))
        return Holder(card, poster, fallback, badge, shade, episode, track, bar, backdrop, info, logo, name, meta, watched, watchedBar, watchedText)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        // A card from the shared pool may have been made by another row: it must answer to this one.
        if (holder.owner !== this) holder.collapse(instant = true)
        holder.owner = this
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: Holder) {
        holder.poster.tag = null
        holder.collapse(instant = true)
        holder.itemView.scaleX = 1f; holder.itemView.scaleY = 1f
    }

    private fun h(fraction: Float) = (heightPx * fraction).toInt()

    inner class Holder(
        view: View,
        val poster: ImageView,
        private val fallback: TextView,
        private val badge: TextView,
        private val shade: View,
        private val episode: TextView,
        private val track: View,
        private val bar: View,
        private val backdrop: ImageView,
        private val info: View,
        private val logo: ImageView,
        private val name: TextView,
        private val meta: TextView,
        private val watched: View,
        private val watchedBar: View,
        private val watchedText: TextView,
    ) : RecyclerView.ViewHolder(view) {
        /** The row adapter that last bound this card; its callbacks and big card are the ones used. */
        var owner: PosterAdapter = this@PosterAdapter
        private var current: Catalog.Title? = null

        private fun sizeBar(cardWidth: Int) {
            val p = current?.progress ?: return
            bar.layoutParams = bar.layoutParams.apply { width = (cardWidth * p.coerceIn(0.03f, 1f)).toInt() }
        }

        private var reveal: android.animation.ValueAnimator? = null

        /** Sets the card's laid-out size; the row's next layout (see [RowRecyclerView.flip]) applies it. */
        fun size(expanded: Boolean) {
            val lp = itemView.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = if (expanded) expandedPx else widthPx
            lp.height = if (expanded) expandedH else heightPx
            lp.topMargin = if (expanded) 0 else inset
            itemView.forceLayout()
            sizeBar(lp.width)
            if (expanded) owner.big = this else if (owner.big === this) owner.big = null
        }

        /** Grows the visible part of the card from poster size to its full size, without any re-layout. */
        private fun revealFromPoster() {
            val card = itemView as Card
            reveal?.cancel()
            // Clip before the first frame, or the card is drawn at full width for a frame or two.
            card.clip(widthPx.toFloat(), heightPx.toFloat())
            reveal = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = RowRecyclerView.DURATION
                interpolator = RowRecyclerView.EASE
                addUpdateListener {
                    val f = it.animatedValue as Float
                    card.clip(widthPx + (expandedPx - widthPx) * f, heightPx + (expandedH - heightPx) * f)
                }
                start()
            }
        }

        /** Widen into the landscape card and fetch its artwork (only now, to keep memory low). */
        private fun expand(title: Catalog.Title) {
            val row = itemView.parent as? RowRecyclerView
            val others = owner.big?.takeIf { it !== this }
            if (row != null) row.flip(itemView) { others?.size(false); size(true) } else { others?.size(false); size(true) }
            revealFromPoster()
            if (title.poster != null) itemView.setBackgroundColor(EXPANDED_FILL)
            name.text = title.name
            meta.text = owner.badges(title, bindingAdapterPosition, Look.accent(itemView.context))
            meta.visibility = if (meta.text.isEmpty()) View.GONE else View.VISIBLE
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            name.visibility = View.VISIBLE
            title.logo?.let { url ->
                Images.get(url, (220 * density).toInt()) { bmp ->
                    if (bmp == null || current?.id != title.id || !itemView.isFocused) return@get
                    logo.setImageBitmap(bmp); logo.visibility = View.VISIBLE; name.visibility = View.GONE
                }
            }
            info.animate().alpha(1f).setStartDelay(90).setDuration(200).start()
            fallback.animate().alpha(0f).setDuration(120).start() // the info line names it now
            badge.animate().alpha(0f).setDuration(120).start()
            prefetchNeighbours(bindingAdapterPosition)
            // No wide artwork (short films, small addons): the poster, cropped to fill, beats a half-empty card.
            val url = title.backdrop ?: title.poster ?: return
            var waited = false
            Images.get(url, expandedPx) { bmp ->
                if (bmp == null || current?.id != title.id || !itemView.isFocused) return@get
                backdrop.setImageBitmap(bmp)
                // Already loaded (the usual case, see prefetchNeighbours): show it at once, so the
                // card never sits half-filled with the narrow poster.
                if (waited) backdrop.animate().alpha(1f).setDuration(180).start()
                else { backdrop.animate().cancel(); backdrop.alpha = 1f }
            }
            waited = true
        }

        /** Loads the backdrops of the cards either side, so moving along the row finds them ready. */
        private fun prefetchNeighbours(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            for (i in intArrayOf(position + 1, position - 1, position + 2)) {
                owner.items.getOrNull(i)?.let { it.backdrop ?: it.poster }?.let { Images.get(it, expandedPx) {} }
            }
        }

        fun collapse(instant: Boolean) {
            reveal?.cancel()
            if (current?.poster != null) itemView.background = null
            (itemView as Card).clip(-1f, -1f)
            if (instant) size(false)
            info.animate().cancel(); backdrop.animate().cancel()
            if (instant) { fallback.alpha = 1f; info.alpha = 0f; backdrop.alpha = 0f; badge.alpha = 1f; backdrop.setImageDrawable(null); return }
            info.animate().alpha(0f).setDuration(110).start()
            fallback.animate().alpha(1f).setDuration(160).start()
            badge.animate().alpha(1f).setDuration(160).start()
            backdrop.animate().alpha(0f).setDuration(160).withEndAction { if (!itemView.isFocused) backdrop.setImageDrawable(null) }.start()
        }

        fun bind(title: Catalog.Title) {
            current = title
            val progress = title.progress
            val inProgress = progress != null
            listOf(shade, track, bar).forEach { it.visibility = if (inProgress) View.VISIBLE else View.GONE }
            episode.visibility = if (title.episodeLabel != null) View.VISIBLE else View.GONE
            episode.text = title.episodeLabel
            if (progress != null) bar.layoutParams = bar.layoutParams.apply { width = (widthPx * progress.coerceIn(0.03f, 1f)).toInt() }
            watched.visibility = if (inProgress) View.VISIBLE else View.GONE
            if (progress != null) {
                watchedBar.layoutParams = watchedBar.layoutParams.apply { width = (WATCHED_W * density * progress.coerceIn(0.03f, 1f)).toInt() }
                watchedText.text = "${(progress * 100).toInt().coerceIn(1, 99)}% watched"
            }
            fallback.text = title.name
            // The name underneath is only for posters that don't exist; drawing it under a real
            // poster costs the GPU a second layer of pixels for every card.
            fallback.visibility = if (title.poster == null) View.VISIBLE else View.GONE
            Images.into(poster, title.poster, widthPx)
            // Generated cover art only for titles with no poster; drawing it under a real
            // poster would double the pixels the GPU fills.
            itemView.background = if (title.poster == null) CoverArt(title.name) else null
            badge.text = Rating.text(title.rating)
            badge.visibility = if (badge.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            itemView.contentDescription = title.name
            // A recycled card may still be pressed in from its last click. (Not cancelling the
            // animator: that would also freeze the row's slide mid-way, see RowRecyclerView.flip.)
            itemView.scaleX = 1f; itemView.scaleY = 1f
            val adapter = owner
            itemView.setOnClickListener {
                itemView.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction {
                    // Springs back, so the card isn't left pressed in when the user returns.
                    itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    adapter.onClick(title)
                }.start()
            }
            itemView.setOnLongClickListener { adapter.onLongClick(title); true }
            itemView.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    expand(title) // also pins the card to the start of the row, as Netflix does
                    adapter.onFocus(title)
                } else {
                    collapse(instant = false)
                    // Focus moving along the row resizes both cards in one go (see expand);
                    // only when it leaves the row does this card shrink on its own.
                    itemView.post {
                        val list = itemView.parent as? RowRecyclerView
                        if (adapter.big === this && !itemView.isFocused && list?.hasFocus() != true) {
                            if (list != null) list.flip(null) { size(false) } else size(false)
                        }
                    }
                }
            }
            if (itemView.isFocused) { size(true); expand(title) } else collapse(instant = true)
        }
    }

    private val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()

    /** Small pill labels for the focused card, each led by a coloured dot like Netflix's. */
    private fun badges(title: Catalog.Title, position: Int, accent: Int): CharSequence {
        val labels = buildList {
            if (ranked && position in 0..9) add("#${position + 1} in Top 10" to accent)
            if (title.year == thisYear) add("Recently added" to 0xFF46D369.toInt())
            if (title.progress != null) title.episodeLabel?.let { add(it to 0xFF5AA9FF.toInt()) }
        }
        return android.text.SpannableStringBuilder().apply {
            labels.forEachIndexed { i, (text, color) ->
                if (i > 0) append("   ")
                append("●", android.text.style.ForegroundColorSpan(color), 0)
                append(" ")
                append(text, android.text.style.StyleSpan(Typeface.BOLD), 0)
            }
        }
    }

    /** A card with rounded corners that can show just part of itself while it grows. */
    private inner class Card(context: android.content.Context) : FrameLayout(context) {
        private var clipW = -1f
        private var clipH = -1f

        init {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val w = if (clipW < 0) view.width else clipW.toInt().coerceAtMost(view.width)
                    val h = if (clipH < 0) view.height else clipH.toInt().coerceAtMost(view.height)
                    // Grows from the top-left; the row's translation moves it up to centre it.
                    outline.setRoundRect(0, 0, w, h, radius)
                }
            }
        }

        fun clip(w: Float, h: Float) {
            clipW = w; clipH = h
            invalidateOutline()
        }
    }

    companion object {
        /** How much taller the focused card is than a poster (Netflix keeps them level). */
        const val GROW = 1f

        /** Behind the wide card's right side until its backdrop arrives. */
        private const val EXPANDED_FILL = 0xFF16181C.toInt()

        /** Width in dp of the progress bar on the focused card. */
        private const val WATCHED_W = 120f

        private val types = HashMap<Triple<Int, Int, Boolean>, Int>()

        /** One view type per card build (size, ranked), so the shared pool never mixes them. */
        @Synchronized
        fun typeFor(w: Int, h: Int, ranked: Boolean) = types.getOrPut(Triple(w, h, ranked)) { types.size }
    }
}
