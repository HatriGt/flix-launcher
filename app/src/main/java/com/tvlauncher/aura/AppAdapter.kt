package com.tvlauncher.aura

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val widthPx: Int,
    private val heightPx: Int,
    private val onFocus: (AppEntry) -> Unit,
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    var items: List<AppEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = items[position].packageName.hashCode().toLong()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        view.layoutParams = view.layoutParams.apply {
            width = widthPx
            height = heightPx
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val iconGroup: View = view.findViewById(R.id.icon_group)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.label)

        fun bind(app: AppEntry) {
            val hasBanner = app.banner != null
            banner.setImageDrawable(app.banner)
            banner.visibility = if (hasBanner) View.VISIBLE else View.GONE
            iconGroup.visibility = if (hasBanner) View.GONE else View.VISIBLE
            icon.setImageDrawable(app.icon)
            label.text = app.label
            itemView.contentDescription = app.label
            itemView.setOnClickListener { onClick(app) }
            itemView.setOnLongClickListener { onLongClick(app); true }
            itemView.outlineSpotShadowColor = app.accent
            itemView.outlineAmbientShadowColor = app.accent
            itemView.setOnFocusChangeListener { v, focused ->
                val scale = if (focused) FOCUS_SCALE else 1f
                val lift = if (focused) GLOW_ELEVATION * v.resources.displayMetrics.density else 0f
                v.animate().scaleX(scale).scaleY(scale).translationZ(lift)
                    .setDuration(if (focused) 260 else 180)
                    .setInterpolator(if (focused) SPRING else EASE_OUT).start()
                if (focused) onFocus(app)
            }
        }
    }

    private companion object {
        const val FOCUS_SCALE = 1.12f
        const val GLOW_ELEVATION = 18f
        val SPRING = OvershootInterpolator(1.6f)
        val EASE_OUT = DecelerateInterpolator(1.6f)
    }
}
