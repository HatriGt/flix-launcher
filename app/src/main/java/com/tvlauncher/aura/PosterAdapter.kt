package com.tvlauncher.aura

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

/** Poster cards for one catalogue row. */
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

    override fun getItemCount() = items.size

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
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(0x33FFFFFF)
            }
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
        card.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        card.addView(poster, FrameLayout.LayoutParams(-1, -1))
        card.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (5 * density).toInt()
            marginEnd = (5 * density).toInt()
        })
        return Holder(card, poster, fallback, badge)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: Holder) {
        holder.poster.tag = null
    }

    inner class Holder(view: View, val poster: ImageView, private val fallback: TextView, private val badge: TextView) :
        RecyclerView.ViewHolder(view) {
        fun bind(title: Catalog.Title) {
            fallback.text = title.name
            Images.into(poster, title.poster, widthPx)
            badge.text = title.rating?.let { "★ $it" }
            badge.visibility = if (title.rating == null) View.GONE else View.VISIBLE
            itemView.contentDescription = title.name
            itemView.setOnClickListener {
                itemView.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction { onClick(title) }.start()
            }
            itemView.setOnLongClickListener { onLongClick(title); true }
            itemView.setOnFocusChangeListener { v, focused ->
                val scale = if (focused) 1.1f else 1f
                v.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 14 * density else 0f)
                    .setDuration(if (focused) 260 else 160)
                    .setInterpolator(if (focused && springy) SPRING else EASE).start()
                if (focused) onFocus(title)
            }
            val focused = itemView.isFocused
            itemView.scaleX = if (focused) 1.1f else 1f
            itemView.scaleY = itemView.scaleX
        }
    }

    private companion object {
        val SPRING = OvershootInterpolator(1.4f)
        val EASE = DecelerateInterpolator(1.6f)
    }
}
