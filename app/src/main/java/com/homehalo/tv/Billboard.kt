package com.homehalo.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Netflix's billboard: a large rounded card at the top of the rows featuring one title,
 * with its artwork, logo, details and Play / More info buttons. The featured title is
 * picked at random from the first catalogue that loads, so it changes from visit to visit.
 */
class Billboard(private val activity: Activity, parent: ViewGroup, private val onOpen: (Catalog.Title) -> Unit) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val backdrop = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val logo = ImageView(activity).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
    private val kind = TextView(activity).apply {
        textSize = 11f
        letterSpacing = 0.3f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            setColor(0xFFE50914.toInt()); setSize(px(4), px(14))
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
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 16 * dp)
        }
        setBackgroundColor(0xFF10141C.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(250)).apply {
            marginStart = px(44); marginEnd = px(44); topMargin = px(4); bottomMargin = px(10)
        }
        visibility = View.GONE
        addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF20A0D13.toInt(), 0xB30A0D13.toInt(), 0x000A0D13))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(LinearLayout(activity).apply {
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
    }

    private var featured: Catalog.Title? = null

    init {
        play.setOnClickListener { featured?.let(onOpen) }
        more.setOnClickListener { featured?.let(onOpen) }
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
                setStroke(px(3), 0xFF5AA9FF.toInt())
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(if (primary) 0xF2FFFFFF.toInt() else 0x40FFFFFF)
            })
        }
    }

    /** Offered every catalogue as it loads; the first one with artwork supplies the featured title. */
    fun offer(titles: List<Catalog.Title>) {
        if (featured != null) return
        val pick = titles.take(12).filter { it.backdrop != null && !it.description.isNullOrBlank() }.randomOrNull() ?: return
        featured = pick
        kind.text = if (pick.type == "series") "SERIES" else "FILM"
        name.text = pick.name
        name.visibility = View.VISIBLE
        meta.text = android.text.SpannableStringBuilder().apply {
            Rating.text(pick.rating)?.let { append(it).append("    ") }
            append(listOfNotNull(pick.year, runtimeText(pick.runtime), pick.genres.take(3).joinToString(" · ").ifBlank { null }).joinToString("    "))
        }
        about.text = pick.description
        view.visibility = View.VISIBLE
        Images.get(pick.backdrop!!, activity.resources.displayMetrics.widthPixels) { bmp ->
            if (bmp != null && featured === pick) backdrop.setImageBitmap(bmp)
        }
        pick.logo?.let { url ->
            Images.get(url, px(300)) { bmp ->
                if (bmp == null || featured !== pick) return@get
                logo.setImageBitmap(bmp); logo.visibility = View.VISIBLE; name.visibility = View.GONE
            }
        }
    }

    /** Forgets the featured title so the next load picks a new one. */
    fun reset() {
        featured = null
        view.visibility = View.GONE
        backdrop.setImageDrawable(null)
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
    }
}
