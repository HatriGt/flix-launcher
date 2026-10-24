package com.homehalo.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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

    private val placeholder = GradientDrawable().apply { cornerRadius = 10 * density; setColor(0x33FFFFFF) }

    override fun getItemCount() = items.size

    /** Width of the focused card: a 16:9 landscape at the poster's height. */
    private val expandedPx = heightPx * 16 / 9

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val radius = 10 * density
        val card = FrameLayout(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx).apply {
                marginStart = (7 * density).toInt()
                marginEnd = (7 * density).toInt()
            }
            isFocusable = true
            clipToOutline = true
            foreground = StateListDrawable().apply {
                setEnterFadeDuration(120)
                setExitFadeDuration(120)
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = radius
                    setStroke((2.5f * density).toInt(), Color.WHITE)
                })
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            outlineSpotShadowColor = Color.WHITE
            outlineAmbientShadowColor = Color.WHITE
        }
        val fallback = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
        }
        val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val badge = TextView(context).apply {
            textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((5 * density).toInt(), (1 * density).toInt(), (5 * density).toInt(), (1 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(0xCC0B0F14.toInt())
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
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), (11 * density).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF0000000.toInt(), 0x99000000.toInt(), 0))
            gravity = Gravity.BOTTOM
            alpha = 0f
        }
        val logo = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
        val name = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        }
        val meta = TextView(context).apply {
            textSize = 10.5f
            setTextColor(0xE6FFFFFF.toInt())
            maxLines = 1
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        info.addView(logo, android.widget.LinearLayout.LayoutParams(-2, (34 * density).toInt()))
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

        private fun animateWidth(to: Int, instant: Boolean) {
            widthAnim?.cancel()
            val lp = itemView.layoutParams
            if (instant || lp.width == to) {
                if (lp.width != to) { lp.width = to; itemView.layoutParams = lp }
                sizeBar(to)
                return
            }
            widthAnim = android.animation.ValueAnimator.ofInt(lp.width, to).apply {
                duration = 230
                interpolator = EASE
                addUpdateListener {
                    lp.width = it.animatedValue as Int
                    sizeBar(lp.width)
                    val row = itemView.parent as? RowRecyclerView
                    // Inside a fixed-height row, only the row re-lays out; elsewhere use the normal path.
                    if (row != null) row.relayoutFor(itemView) else itemView.layoutParams = lp
                }
                start()
            }
        }

        /** Widen into the landscape card and fetch its artwork (only now, to keep memory low). */
        private fun expand(title: Catalog.Title) {
            animateWidth(expandedPx, instant = false)
            name.text = title.name
            meta.text = listOfNotNull(title.rating?.let { "★ $it" }, title.year,
                title.genres.take(2).joinToString(" · ").ifBlank { null }, title.episodeLabel).joinToString("   ")
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
            badge.animate().alpha(0f).setDuration(120).start()
            val url = title.backdrop ?: return
            Images.get(url, expandedPx) { bmp ->
                if (bmp == null || current?.id != title.id || !itemView.isFocused) return@get
                backdrop.setImageBitmap(bmp)
                backdrop.animate().alpha(1f).setDuration(220).start()
            }
        }

        fun collapse(instant: Boolean) {
            animateWidth(widthPx, instant)
            info.animate().cancel(); backdrop.animate().cancel()
            if (instant) { info.alpha = 0f; backdrop.alpha = 0f; badge.alpha = 1f; backdrop.setImageDrawable(null); return }
            info.animate().alpha(0f).setDuration(110).start()
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
            Images.into(poster, title.poster, widthPx)
            // The grey placeholder only matters until the poster arrives; drawing it under a
            // full poster doubles the pixels the GPU fills.
            itemView.background = if (title.poster == null) placeholder else null
            badge.text = title.rating?.let { "★ $it" }
            badge.visibility = if (title.rating == null) View.GONE else View.VISIBLE
            itemView.contentDescription = title.name
            itemView.setOnClickListener {
                itemView.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction { onClick(title) }.start()
            }
            itemView.setOnLongClickListener { onLongClick(title); true }
            itemView.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    expand(title)
                    // Keep the widened card at the start of the row, as Netflix does.
                    (itemView.parent as? RecyclerView)?.let { rv -> rv.smoothScrollBy(itemView.left - rv.paddingStart - (7 * density).toInt(), 0) }
                    onFocus(title)
                } else collapse(instant = false)
            }
            if (itemView.isFocused) expand(title) else collapse(instant = true)
        }
    }

    private companion object {
        val SPRING = OvershootInterpolator(1.4f)
        val EASE = DecelerateInterpolator(1.6f)
    }
}
