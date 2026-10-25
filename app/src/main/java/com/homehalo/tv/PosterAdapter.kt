package com.homehalo.tv

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
 * Poster cards for one catalogue row. Like Netflix, the focused card widens into a
 * landscape card showing the title's backdrop, logo and rating; the others stay posters.
 */
class PosterAdapter(
    private val widthPx: Int,
    private val heightPx: Int,
    private val density: Float,
    private val springy: Boolean,
    private val onFocus: (Catalog.Title) -> Unit,
    private val onClick: (Catalog.Title) -> Unit,
    private val onLongClick: (Catalog.Title) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.Holder>() {

    var items: List<Catalog.Title> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    private val radius = 12 * density

    override fun getItemCount() = items.size

    /** The focused card grows in both directions, into a 16:9 landscape a third taller than a poster. */
    private val expandedH = (heightPx * GROW).toInt()
    private val expandedPx = expandedH * 16 / 9
    private val inset = (expandedH - heightPx) / 2

    /** The card in this row that's currently big, if any. */
    private var big: Holder? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = Card(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx).apply {
                marginStart = (7 * density).toInt()
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
        info.addView(logo, android.widget.LinearLayout.LayoutParams(-2, (46 * density).toInt()))
        info.addView(name)
        info.addView(meta)
        val track = View(context).apply { setBackgroundColor(0x59FFFFFF) }
        val bar = View(context).apply { setBackgroundColor(0xFFE50914.toInt()) }
        card.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        card.addView(poster, FrameLayout.LayoutParams(-1, -1))
        card.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        card.addView(info, FrameLayout.LayoutParams(-1, -1))
        card.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (5 * density).toInt()
            marginEnd = (5 * density).toInt()
        })
        val barHeight = (3 * density).toInt()
        card.addView(shade, FrameLayout.LayoutParams(-1, (h(0.32f)), Gravity.BOTTOM))
        card.addView(episode, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = (7 * density).toInt()
            bottomMargin = (9 * density).toInt()
        })
        card.addView(track, FrameLayout.LayoutParams(-1, barHeight, Gravity.BOTTOM))
        card.addView(bar, FrameLayout.LayoutParams(0, barHeight, Gravity.BOTTOM))
        return Holder(card, poster, fallback, badge, shade, episode, track, bar, backdrop, info, logo, name, meta)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: Holder) {
        holder.poster.tag = null
        holder.collapse(instant = true)
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
    ) : RecyclerView.ViewHolder(view) {
        private var widthAnim: android.animation.ValueAnimator? = null
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
            if (expanded) big = this else if (big === this) big = null
        }

        /** Grows the visible part of the card from poster size to its full size, without any re-layout. */
        private fun revealFromPoster() {
            val card = itemView as Card
            reveal?.cancel()
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
            val others = big?.takeIf { it !== this }
            if (row != null) row.flip(itemView) { others?.size(false); size(true) } else { others?.size(false); size(true) }
            revealFromPoster()
            name.text = title.name
            meta.text = android.text.SpannableStringBuilder().apply {
                Rating.text(title.rating)?.let { append(it); append("    ") }
                append(listOfNotNull(title.year, title.genres.take(2).joinToString(" · ").ifBlank { null }, title.episodeLabel).joinToString("    "))
            }
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
            val url = title.backdrop ?: return
            Images.get(url, expandedPx) { bmp ->
                if (bmp == null || current?.id != title.id || !itemView.isFocused) return@get
                backdrop.setImageBitmap(bmp)
                backdrop.animate().alpha(1f).setDuration(220).start()
            }
        }

        fun collapse(instant: Boolean) {
            reveal?.cancel()
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
            itemView.setOnClickListener {
                itemView.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction { onClick(title) }.start()
            }
            itemView.setOnLongClickListener { onLongClick(title); true }
            itemView.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    expand(title) // also pins the card to the start of the row, as Netflix does
                    onFocus(title)
                } else {
                    collapse(instant = false)
                    // Focus moving along the row resizes both cards in one go (see expand);
                    // only when it leaves the row does this card shrink on its own.
                    itemView.post {
                        val row = itemView.parent as? RowRecyclerView
                        if (big === this && !itemView.isFocused && row?.hasFocus() != true) {
                            if (row != null) row.flip(null) { size(false) } else size(false)
                        }
                    }
                }
            }
            if (itemView.isFocused) { size(true); expand(title) } else collapse(instant = true)
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
        /** How much taller the focused card is than a poster. */
        const val GROW = 1.3f
    }
}
