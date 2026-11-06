package com.flix.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The TV tab: Nuvio+IPTV's channels the way the Movies tab shows films. A big live card at
 * the top plays the selected channel (muted, like a trailer) under its logo, what's on and
 * a time bar; under it, rows of channel cards (favourites first, then each group the user
 * keeps visible), each card the channel's logo on its own colour with what's on now.
 * Moving between cards changes the live card a moment later; OK watches full screen in
 * Nuvio+IPTV. Up and down step a whole row at a time, the focused row sliding up under the
 * live card, so the picture is always in view.
 */
class LiveTvPage(private val activity: Activity) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun px(v: Float) = (v * dp).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(3)
    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())

    // ---- The live card ----

    private val video = TextureView(activity).apply { alpha = 0f }
    private val art = View(activity)
    private val bigLogo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0.18f }
    private val chipLogo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val chip = FrameLayout(activity).apply {
        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xF2FFFFFF.toInt()) }
        setPadding(px(10), px(6), px(10), px(6))
        addView(chipLogo, FrameLayout.LayoutParams(-1, -1))
    }
    private val live = text(" LIVE ", 11f, bold = true).apply {
        letterSpacing = 0.12f
        background = GradientDrawable().apply { cornerRadius = 5 * dp; setColor(0xFFE50914.toInt()) }
        setPadding(px(7), px(2), px(7), px(2))
    }
    private val channelLine = text("", 13f, alpha = 0.85f)
    private val title = text("", 30f, bold = true).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val times = text("", 13f, alpha = 0.85f)
    private val about = text("", 13f, alpha = 0.7f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
    private val bar = View(activity).apply { setBackgroundColor(0xFFE50914.toInt()) }
    private val track = FrameLayout(activity).apply {
        background = GradientDrawable().apply { cornerRadius = 2 * dp; setColor(0x40FFFFFF) }
        clipToOutline = true
        addView(bar, FrameLayout.LayoutParams(0, -1))
    }
    private val next = text("", 12.5f, alpha = 0.7f)
    val watch = pill("▶   Watch", primary = true)
    private val guideButton = pill("Guide", primary = false)
    private val message = text("", 15f, alpha = 0.85f).apply { visibility = View.GONE; setLineSpacing(0f, 1.25f) }

    private val hero = FrameLayout(activity).apply {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, Look.of(activity).billboardRadiusDp * dp)
        }
        setBackgroundColor(0xFF0B0E14.toInt())
        addView(art, FrameLayout.LayoutParams(-1, -1))
        addView(bigLogo, FrameLayout.LayoutParams(px(360), px(200), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = px(60) })
        addView(video, FrameLayout.LayoutParams(-1, -1))
        // Darkest on the left, where the words are, and along the bottom.
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFA0A0D13.toInt(), 0xE00A0D13.toInt(), 0x800A0D13.toInt(), 0x000A0D13))
        }, FrameLayout.LayoutParams(px(720), -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE60A0D13.toInt(), 0))
        }, FrameLayout.LayoutParams(-1, px(130), Gravity.BOTTOM))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(36), px(24), px(36), px(22))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chip, LinearLayout.LayoutParams(px(92), px(46)))
                addView(live, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(14) })
                (chip.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
                addView(channelLine, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10) })
            })
            addView(title, LinearLayout.LayoutParams(px(560), -2).apply { topMargin = px(14) })
            addView(times, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(4) })
            addView(track, LinearLayout.LayoutParams(px(300), px(4)).apply { topMargin = px(10) })
            addView(about, LinearLayout.LayoutParams(px(520), -2).apply { topMargin = px(10) })
            addView(message, LinearLayout.LayoutParams(px(560), -2).apply { topMargin = px(10) })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false; clipToPadding = false
                addView(watch)
                addView(guideButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(12) })
                addView(next, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(22) })
            })
        }, FrameLayout.LayoutParams(-1, -1))
    }

    // ---- The rows ----

    private val rowsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val rowsWindow = FrameLayout(activity).apply {
        clipChildren = true
        addView(rowsBox, FrameLayout.LayoutParams(-1, -2))
    }
    private val rows = mutableListOf<RecyclerView>()

    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        visibility = View.GONE
        addView(hero, LinearLayout.LayoutParams(-1, px(280)).apply { marginStart = px(44); marginEnd = px(44); topMargin = px(14) })
        addView(rowsWindow, LinearLayout.LayoutParams(-1, px(190)).apply { topMargin = px(8) })
    }

    // ---- State ----

    private var current: LiveTv.Channel? = null
    private var player: ExoPlayer? = null
    private var playing: LiveTv.Channel? = null
    private var shown = false
    private val startVideo = Runnable { current?.let { play(it) } }

    init {
        watch.setOnClickListener { if (current != null) LiveTv.open(activity, current) else LiveTv.openApp(activity) }
        guideButton.setOnClickListener { LiveTv.open(activity, null) }
        watch.nextFocusRightId = View.NO_ID
        watch.setOnKeyListener { _, code, e -> if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) focusRow(0) else false }
        guideButton.setOnKeyListener { _, code, e -> if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) focusRow(0) else false }
        tick()
    }

    /** The tab became visible (or hidden). Loads the lineup the first time and when it's stale. */
    fun show(visible: Boolean) {
        shown = visible
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) { stopVideo(); return }
        render()
        net.execute {
            val lineup = runCatching { LiveTv.load(activity, force = LiveTv.lineup == null) }.getOrNull()
            main.post { if (shown) render(lineup) }
        }
    }

    fun pause() = stopVideo()

    fun release() { stopVideo(); player?.release(); player = null }

    fun firstFocus(): View = if (rows.isNotEmpty()) rows[0].getChildAt(0) ?: watch else watch

    private fun render(lineup: LiveTv.Lineup? = LiveTv.lineup) {
        val state = LiveTv.state(activity)
        if (lineup == null || state != LiveTv.State.READY) {
            rowsBox.removeAllViews(); rows.clear()
            android.util.Log.i("Flix", "Live TV: $state, lineup ${lineup != null}")
            showMessage(when (state) {
                LiveTv.State.NOT_SIGNED_IN -> "Sign in to Nuvio in Flix settings to see your Nuvio+IPTV channels here."
                LiveTv.State.NOT_INSTALLED -> "Install Nuvio+IPTV to watch live TV from here."
                else -> "Getting your channels from Nuvio+IPTV…\nIf they don't appear, turn on Setup Sync in Nuvio+IPTV's Live TV settings."
            })
            return
        }
        message.visibility = View.GONE
        about.visibility = View.VISIBLE
        if (rows.isNotEmpty()) return
        val sections = buildList {
            if (lineup.favourites.isNotEmpty()) add("Your channels" to lineup.favourites)
            lineup.groups.forEach { add(pretty(it.name) to it.channels) }
        }
        sections.forEach { (name, list) -> addRow(name, list) }
        (lineup.favourites.firstOrNull() ?: lineup.all.firstOrNull())?.let { select(it) }
    }

    private fun showMessage(text: String) {
        current = null
        message.text = text
        message.visibility = View.VISIBLE
        about.visibility = View.GONE
        title.text = "Live TV"
        times.text = ""; about.text = ""; next.text = ""; channelLine.text = "Nuvio+IPTV"
        track.visibility = View.INVISIBLE
        chip.visibility = View.GONE
        watch.text = "Open Nuvio+IPTV"
        stopVideo()
    }

    /** "TAMIL | NEWS" reads as "Tamil · News". */
    private fun pretty(group: String) = group.split('|').joinToString("  ·  ") { part ->
        part.trim().lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun addRow(name: String, channels: List<LiveTv.Channel>) {
        val label = text(name, 15f, bold = true).apply { setPadding(px(48), 0, 0, px(6)) }
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            adapter = CardAdapter(channels)
            itemAnimator = null
            clipToPadding = false; clipChildren = false
            setPadding(px(40), px(8), px(40), px(10))
            setHasFixedSize(true)
        }
        rows += list
        rowsBox.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            addView(label)
            addView(list, LinearLayout.LayoutParams(-1, px(CARD_H + 48)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
    }

    /** Moves focus to row [i] (its first visible card) and slides it up to the top of the window. */
    private fun focusRow(i: Int): Boolean {
        val list = rows.getOrNull(i) ?: return false
        // Rows above the focused one step out of the window; the focused one rises into it.
        rows.forEachIndexed { j, r -> (r.parent as View).visibility = if (j < i) View.GONE else View.VISIBLE }
        val section = list.parent as View
        section.alpha = 0.4f; section.translationY = 24 * dp
        section.animate().alpha(1f).translationY(0f).setDuration(RowRecyclerView.DURATION).setInterpolator(RowRecyclerView.EASE).start()
        list.post {
            val lm = list.layoutManager as LinearLayoutManager
            val pos = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
            (lm.findViewByPosition(pos) ?: list.getChildAt(0))?.requestFocus()
        }
        return true
    }

    // ---- Selecting a channel ----

    private fun select(channel: LiveTv.Channel) {
        if (current?.key == channel.key && message.visibility == View.GONE) return
        current = channel
        chip.visibility = if (channel.logo != null) View.VISIBLE else View.GONE
        track.visibility = View.VISIBLE
        watch.text = "▶   Watch"
        channelLine.text = "CH ${channel.number}  ·  ${pretty(channel.group)}"
        title.text = channel.name.substringAfter(": ").trim()
        times.text = ""; about.text = ""; next.text = ""
        setProgress(0f)
        Images.into(chipLogo, channel.logo, px(120))
        Images.into(bigLogo, channel.logo, px(360))
        Images.accent(channel.logo ?: "") { c -> tint(c ?: CoverArt.accentOf(channel.name)) }
        if (channel.logo == null) tint(CoverArt.accentOf(channel.name))
        net.execute {
            val g = LiveTv.guide(channel)
            main.post { if (current?.key == channel.key) showGuide(channel, g) }
        }
        // The picture follows a moment later, so passing over cards doesn't start each one.
        main.removeCallbacks(startVideo)
        if (playing?.key != channel.key) {
            video.animate().alpha(0f).setDuration(200).start()
            main.postDelayed(startVideo, 800)
        }
    }

    private fun tint(color: Int) {
        art.background = GradientDrawable(GradientDrawable.Orientation.TR_BL,
            intArrayOf(color, mix(color, 0xFF0A0D13.toInt(), 0.75f), 0xFF0A0D13.toInt()))
    }

    private fun showGuide(channel: LiveTv.Channel, g: LiveTv.Guide?) {
        val now = g?.now
        if (now != null) {
            title.text = now.title
            val left = ((now.end - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            times.text = "${clock.format(Date(now.start))} – ${clock.format(Date(now.end))}   ·   ${left}m left"
            about.text = now.about.orEmpty()
            setProgress(now.progress())
        } else {
            title.text = channel.name.substringAfter(": ").trim()
            times.text = "Live now"
        }
        next.text = g?.next?.let { "Next  ${clock.format(Date(it.start))}   ${it.title}" }.orEmpty()
    }

    private fun setProgress(f: Float) {
        bar.layoutParams = bar.layoutParams.apply { width = (px(300) * f).toInt() }
    }

    /** Keeps the time bar moving. */
    private fun tick() {
        main.postDelayed({
            if (shown) current?.let { c -> net.execute { val g = LiveTv.guide(c); main.post { if (current?.key == c.key) showGuide(c, g) } } }
            tick()
        }, 60_000)
    }

    // ---- Live picture ----

    private fun play(channel: LiveTv.Channel) {
        if (!shown || !FlixSettings.isOn(activity, FlixSettings.LIVE_TV)) return
        val url = LiveTv.streamUrl(channel) ?: return
        val p = player ?: ExoPlayer.Builder(activity).build().also { pl ->
            player = pl
            pl.setVideoTextureView(video)
            pl.volume = 0f
            pl.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { video.animate().alpha(1f).setDuration(500).start() }
                override fun onPlayerError(error: PlaybackException) {
                    video.animate().alpha(0f).setDuration(200).start()
                    android.util.Log.w("Flix", "Live preview: ${error.errorCodeName}")
                }
            })
        }
        playing = channel
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    private fun stopVideo() {
        main.removeCallbacks(startVideo)
        playing = null
        video.alpha = 0f
        player?.let { it.stop(); it.clearMediaItems() }
    }

    // ---- Channel cards ----

    private inner class CardAdapter(val channels: List<LiveTv.Channel>) : RecyclerView.Adapter<CardHolder>() {
        override fun getItemCount() = channels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder()
        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(channels[position])
    }

    private inner class CardHolder : RecyclerView.ViewHolder(LinearLayout(activity)) {
        private val face = FrameLayout(activity).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 12 * dp)
            }
        }
        private val logo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val initials = text("", 26f, bold = true).apply { gravity = Gravity.CENTER; alpha = 0.9f }
        private val now = text("", 11f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(px(10), px(14), px(10), px(8))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0))
        }
        private val progress = View(activity).apply { setBackgroundColor(0xFFE50914.toInt()) }
        private val ring = View(activity).apply {
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setStroke(px(3), Color.WHITE) }
            visibility = View.INVISIBLE
        }
        private val name = text("", 12.5f, alpha = 0.75f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private var channel: LiveTv.Channel? = null

        init {
            val root = itemView as LinearLayout
            root.orientation = LinearLayout.VERTICAL
            root.isFocusable = true
            root.clipChildren = false; root.clipToPadding = false
            root.layoutParams = RecyclerView.LayoutParams(px(CARD_W), -2).apply { marginStart = px(8); marginEnd = px(8) }
            face.addView(initials, FrameLayout.LayoutParams(-1, -1))
            face.addView(logo, FrameLayout.LayoutParams(-1, -1).apply { setMargins(px(30), px(18), px(30), px(30)) })
            face.addView(now, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            face.addView(progress, FrameLayout.LayoutParams(0, px(3), Gravity.BOTTOM))
            face.addView(ring, FrameLayout.LayoutParams(-1, -1))
            root.addView(face, LinearLayout.LayoutParams(-1, px(CARD_H)))
            root.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8); marginStart = px(2) })
            root.setOnFocusChangeListener { v, has ->
                ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
                name.alpha = if (has) 1f else 0.75f
                v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).translationY(if (has) -px(4f).toFloat() else 0f)
                    .setDuration(RowRecyclerView.DURATION).setInterpolator(RowRecyclerView.EASE).start()
                v.elevation = if (has) 10 * dp else 0f
                if (has) channel?.let { select(it) }
            }
            root.setOnClickListener { channel?.let { LiveTv.open(activity, it) } }
            root.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val row = rows.indexOfFirst { it === root.parent }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { focusRow(row + 1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> if (row > 0) focusRow(row - 1) else watch.requestFocus()
                    else -> false
                }
            }
        }

        fun bind(c: LiveTv.Channel) {
            channel = c
            name.text = c.name.substringAfter(": ").trim()
            initials.text = if (c.logo == null) name.text.take(2).toString().uppercase() else ""
            val base = CoverArt.accentOf(c.name)
            face.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mix(base, 0xFF1A1F2A.toInt(), 0.55f), 0xFF10141C.toInt()))
            Images.into(logo, c.logo, px(CARD_W))
            if (c.logo != null) Images.accent(c.logo) { col ->
                if (channel?.key == c.key && col != null)
                    face.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mix(col, 0xFF1A1F2A.toInt(), 0.45f), 0xFF0E1219.toInt()))
            }
            now.text = ""
            progress.layoutParams = progress.layoutParams.apply { width = 0 }
            net.execute {
                val g = LiveTv.guide(c)
                main.post {
                    if (channel?.key != c.key) return@post
                    now.text = g?.now?.title.orEmpty()
                    progress.layoutParams = progress.layoutParams.apply { width = ((g?.now?.progress() ?: 0f) * px(CARD_W)).toInt() }
                }
            }
        }
    }

    // ---- Bits ----

    private fun text(s: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(activity).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun pill(label: String, primary: Boolean) = TextView(activity).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isFocusable = true
        setPadding(px(22), px(9), px(22), px(9))
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(0xFF0B0E14.toInt(), if (primary) 0xFF0B0E14.toInt() else Color.WHITE)))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = 8 * dp; setColor(Color.WHITE); setStroke(px(2), Look.focus(activity)) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = 8 * dp; setColor(if (primary) 0xE6FFFFFF.toInt() else 0x40FFFFFF) })
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    companion object {
        private const val CARD_W = 184
        private const val CARD_H = 104
    }
}
