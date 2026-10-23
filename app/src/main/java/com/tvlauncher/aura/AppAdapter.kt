package com.tvlauncher.aura

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/** How tiles look; derived from [AuraSettings] by the home screen. */
data class TileStyle(
    val shape: String,      // circle | squircle | rounded | banner
    val widthPx: Int,
    val heightPx: Int,
    val labels: String,     // always | focus | never
    val glow: Boolean,
    val motion: String,     // spring | smooth | none
    val density: Float,
) {
    val isBanner get() = shape == "banner"

    fun cornerRadius(): Float = when (shape) {
        "circle" -> heightPx / 2f
        "squircle" -> heightPx * 0.32f
        "rounded" -> heightPx * 0.2f
        else -> 10 * density
    }
}

class AppAdapter(
    style: TileStyle,
    private val onFocus: (AppEntry) -> Unit,
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    var style: TileStyle = style
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged() // a style change redraws every tile anyway
        }

    /** Updates only the cards that changed, so focus and scroll position survive a refresh. */
    var items: List<AppEntry> = emptyList()
        set(value) {
            val old = field
            field = value
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = value.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].packageName == value[n].packageName
                override fun areContentsTheSame(o: Int, n: Int) =
                    old[o].label == value[n].label && old[o].launchIntent.component == value[n].launchIntent.component
            }).dispatchUpdatesTo(this)
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = items[position].packageName.hashCode().toLong()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tile: View = view.findViewById(R.id.tile)
        private val art: ImageView = view.findViewById(R.id.art)
        private val fallbackLabel: TextView = view.findViewById(R.id.fallback_label)
        private val label: TextView = view.findViewById(R.id.label)

        fun bind(app: AppEntry) {
            val s = style
            val d = s.density
            tile.layoutParams = tile.layoutParams.apply {
                width = s.widthPx
                height = s.heightPx
            }
            val spacing = if (s.isBanner) 6 * d else 10 * d
            (itemView.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = spacing.toInt()
                marginEnd = spacing.toInt()
            }

            val radius = s.cornerRadius()
            tile.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (s.shape == "circle") outline.setOval(0, 0, view.width, view.height)
                    else outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            tile.foreground = focusRing(s, radius)
            bindArt(app, s)

            label.text = app.label
            label.visibility = if (s.labels == "never") View.GONE else View.VISIBLE
            label.maxWidth = s.widthPx + (24 * d).toInt()
            itemView.contentDescription = app.label

            val glowColor = if (s.glow) app.accent else Color.BLACK
            tile.outlineSpotShadowColor = glowColor
            tile.outlineAmbientShadowColor = glowColor

            itemView.setOnClickListener {
                tile.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction { onClick(app) }.start()
            }
            itemView.setOnLongClickListener { onLongClick(app); true }
            itemView.setOnFocusChangeListener { _, focused -> animateFocus(focused, s) }
            animateFocus(itemView.isFocused, s, instant = true)
        }

        private fun bindArt(app: AppEntry, s: TileStyle) {
            art.scaleX = 1f
            art.scaleY = 1f
            art.setPadding(0, 0, 0, 0)
            fallbackLabel.visibility = View.GONE
            tile.background = null
            val banner = app.banner
            val icon = app.icon
            when {
                s.isBanner || (!app.hasIcon && banner != null) -> {
                    art.scaleType = if (s.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    art.setImageDrawable(banner ?: icon)
                    tile.background = tileBackground(app)
                    if (!s.isBanner) {
                        // A wide banner squeezed into a round tile: inset it so its edges don't get cut off.
                        val inset = (s.widthPx * 0.12f).toInt()
                        art.setPadding(inset, inset, inset, inset)
                    }
                }
                icon is AdaptiveIconDrawable -> {
                    // Draw the layers full-bleed and let Aura's own shape do the masking.
                    art.scaleType = ImageView.ScaleType.FIT_XY
                    art.setImageDrawable(LayerDrawable(arrayOf(icon.background ?: ColorDrawable(Color.WHITE), icon.foreground ?: ColorDrawable(0))))
                    art.scaleX = 1.42f
                    art.scaleY = 1.42f
                }
                icon is YouTubeBanner -> {
                    art.scaleType = ImageView.ScaleType.FIT_XY
                    art.setImageDrawable(icon)
                }
                app.iconFullBleed -> {
                    // Square icons: fill the shape; a slight zoom keeps their content clear of the curved edge.
                    art.scaleType = ImageView.ScaleType.FIT_XY
                    art.setImageDrawable(icon)
                    if (s.shape == "circle") {
                        art.scaleX = 1.12f
                        art.scaleY = 1.12f
                    }
                }
                else -> {
                    art.scaleType = ImageView.ScaleType.FIT_CENTER
                    art.setImageDrawable(icon)
                    tile.background = tileBackground(app)
                    val inset = (s.widthPx * 0.16f).toInt()
                    art.setPadding(inset, inset, inset, inset)
                }
            }
            if (s.isBanner && banner == null) fallbackLabel.apply { text = app.label; visibility = View.VISIBLE }
        }

        /** Soft gradient in the app's own colour behind icons that don't fill their tile. */
        private fun tileBackground(app: AppEntry): Drawable {
            val base = app.accent
            val dark = Color.rgb(Color.red(base) / 5 + 14, Color.green(base) / 5 + 18, Color.blue(base) / 5 + 26)
            val mid = Color.rgb(Color.red(base) / 3 + 20, Color.green(base) / 3 + 24, Color.blue(base) / 3 + 34)
            return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mid, dark))
        }

        private fun focusRing(s: TileStyle, radius: Float): Drawable {
            val ring = GradientDrawable().apply {
                if (s.shape == "circle") shape = GradientDrawable.OVAL else cornerRadius = radius
                setStroke((2.5f * s.density).toInt(), Color.WHITE)
            }
            return StateListDrawable().apply {
                setEnterFadeDuration(120)
                setExitFadeDuration(120)
                addState(intArrayOf(android.R.attr.state_focused), ring)
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
        }

        private fun animateFocus(focused: Boolean, s: TileStyle, instant: Boolean = false) {
            val scale = when {
                !focused -> 1f
                s.motion == "none" -> 1.04f
                s.isBanner -> 1.1f
                else -> 1.16f
            }
            val lift = if (focused) (if (s.glow) 18f else 8f) * s.density else 0f
            val labelAlpha = when (s.labels) {
                "focus" -> if (focused) 1f else 0f
                else -> if (focused) 1f else 0.75f
            }
            val duration = when {
                instant || s.motion == "none" -> 0L
                focused -> 280L
                else -> 180L
            }
            tile.animate().scaleX(scale).scaleY(scale).translationZ(lift).setDuration(duration)
                .setInterpolator(if (focused && s.motion == "spring") SPRING else EASE_OUT).start()
            label.animate().alpha(labelAlpha).translationY(if (focused && !s.isBanner) 5 * s.density else 0f)
                .setDuration(duration).setInterpolator(EASE_OUT).start()
            if (focused) items.getOrNull(bindingAdapterPosition)?.let(onFocus)
        }
    }

    private companion object {
        val SPRING = OvershootInterpolator(1.7f)
        val EASE_OUT = DecelerateInterpolator(1.6f)
    }
}
