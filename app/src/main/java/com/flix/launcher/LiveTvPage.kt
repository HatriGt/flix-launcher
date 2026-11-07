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
 * The TV tab: Nuvio+IPTV's channels the way the Movies tab shows films. Rows of channel
 * cards (recently watched first, then each group the user keeps visible), each card the
 * channel's logo on its own colour with what's on now. Above the rows, the focused channel's
 * details: logo, LIVE, what's on, a time bar and what's next. Resting on a card plays that
 * channel full screen behind the page, muted, as a poster plays its trailer; OK watches it
 * in Nuvio+IPTV. Up and down step a whole row at a time.
 */
class LiveTvPage(private val activity: Activity, private val videoHost: FrameLayout) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun px(v: Float) = (v * dp).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(3)
    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())

    // ---- The live card ----

    private val video = TextureView(activity).apply { alpha = 0f }

    /** Called when the live picture starts or stops behind the page (the scenery pauses, the page dims). */
    var onPlaying: (Boolean) -> Unit = {}
    private val chipLogo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val chip = FrameLayout(activity).apply {
        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xF2FFFFFF.toInt()) }
        setPadding(px(8), px(4), px(8), px(4))
        addView(chipLogo, FrameLayout.LayoutParams(-1, -1))
    }
    private val live = text(" LIVE ", 11f, bold = true).apply {
        letterSpacing = 0.12f
        background = GradientDrawable().apply { cornerRadius = 5 * dp; setColor(0xFFE50914.toInt()) }
        setPadding(px(7), px(2), px(7), px(2))
    }
    private val channelLine = text("", 13f, alpha = 0.85f)
    private val title = text("", 24f, bold = true).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val times = text("", 13f, alpha = 0.85f)
    private val about = text("", 13f, alpha = 0.7f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
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

    /** The focused channel's details, over the live picture: no card, like the Movies tab's. */
    private val info = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(48), 0, px(48), px(4))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chip, LinearLayout.LayoutParams(px(66), px(32)).apply { marginEnd = px(12) })
            addView(live)
            addView(channelLine, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10) })
        })
        addView(title, LinearLayout.LayoutParams(px(760), -2).apply { topMargin = px(4) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(times)
            addView(track, LinearLayout.LayoutParams(px(220), px(4)).apply { marginStart = px(16) })
            addView(next, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(18) })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2) })
        addView(about, LinearLayout.LayoutParams(px(760), -2).apply { topMargin = px(4) })
        addView(message, LinearLayout.LayoutParams(px(760), -2).apply { topMargin = px(10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(watch)
            addView(guideButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(12) })
            visibility = View.GONE
            tag = "buttons"
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(14) })
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
        addView(info, LinearLayout.LayoutParams(-1, -2))
        addView(rowsWindow, LinearLayout.LayoutParams(-1, -2))
    }

    // ---- State ----

    private var current: LiveTv.Channel? = null
    private var builtRecent: List<String>? = null
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
        LiveTv.onUpdate = { if (shown) render() }
        LiveTv.warm(activity)
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
                else -> if (!LiveTv.failed) ""
                    else "Couldn't get your channels from Nuvio+IPTV.\nCheck the internet connection, and that Setup Sync is on in Nuvio+IPTV's Live TV settings."
            })
            return
        }
        message.visibility = View.GONE
        about.visibility = View.VISIBLE
        info.findViewWithTag<View>("buttons")?.visibility = View.GONE
        // Built once, and again when something new was watched (Recently watched changed).
        val recentKeys = LiveTv.recent(activity) + LiveTv.hidden(activity).sorted().map { "-$it" } + lineup.signature()
        if (rows.isNotEmpty() && recentKeys == builtRecent) return
        builtRecent = recentKeys
        val hidden = LiveTv.hidden(activity)
        // A background sync can rebuild the rows while someone is in them: keep them in the tab.
        val hadFocus = view.findFocus() != null
        rowsBox.removeAllViews(); rows.clear()
        if (hadFocus) view.post { firstFocus().requestFocus() }
        val byKey = lineup.all.associateBy { it.key }
        val recent = LiveTv.recent(activity).mapNotNull { byKey[it] }.filter { it.key !in LiveTv.hidden(activity) }
        val sections = buildList {
            // Recently watched first; until there is any, the favourites take its place.
            if (recent.isNotEmpty()) add("Recently watched" to recent)
            else if (lineup.favourites.isNotEmpty()) add("Your channels" to lineup.favourites)
            lineup.groups.forEach { add(pretty(it.name) to it.channels) }
        }.map { (n, l) -> n to l.filter { it.key !in hidden } }.filter { it.second.isNotEmpty() }
        sections.forEach { (name, list) -> addRow(name, list) }
        (recent.firstOrNull() ?: lineup.favourites.firstOrNull() ?: lineup.all.firstOrNull())?.let { select(it, play = false) }
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
        info.findViewWithTag<View>("buttons")?.visibility = View.VISIBLE
        stopVideo()
    }

    /** "TAMIL | NEWS" reads as "Tamil · News". */
    private fun pretty(group: String) = group.split('|').joinToString("  ·  ") { part ->
        part.trim().lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun addRow(name: String, channels: List<LiveTv.Channel>) {
        val label = text(name, 15f, bold = true).apply { setPadding(px(48), px(4), 0, px(2)) }
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            adapter = CardAdapter(channels)
            itemAnimator = null
            clipToPadding = false; clipChildren = false
            setPadding(px(40), px(6), px(40), px(4))
            setHasFixedSize(true)
        }
        rows += list
        rowsBox.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            addView(label)
            addView(list, LinearLayout.LayoutParams(-1, px(CARD_H + 36)))
        }, LinearLayout.LayoutParams(-1, -2))
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

    private fun select(channel: LiveTv.Channel, play: Boolean = true) {
        if (current?.key == channel.key && message.visibility == View.GONE) {
            // Already showing it: just play it if it isn't yet.
            if (play && playing?.key != channel.key) { main.removeCallbacks(startVideo); main.postDelayed(startVideo, 1200) }
            return
        }
        current = channel
        chip.visibility = if (channel.logo != null) View.VISIBLE else View.GONE
        track.visibility = View.VISIBLE
        watch.text = "▶   Watch"
        channelLine.text = "CH ${channel.number}  ·  ${pretty(channel.group)}"
        title.text = channel.name.substringAfter(": ").trim()
        times.text = ""; about.text = ""; next.text = ""
        setProgress(0f)
        loadLogo(chipLogo, channel.logos, px(120)) { chip.visibility = View.GONE }
        net.execute {
            val g = LiveTv.guide(channel)
            main.post { if (current?.key == channel.key) showGuide(channel, g) }
        }
        // The picture follows a moment later, so passing over cards doesn't start each one.
        main.removeCallbacks(startVideo)
        if (playing?.key != channel.key) {
            if (playing != null) { stopVideo() }
            // As with trailers: resting on a card for a moment plays it behind the page.
            if (play) main.postDelayed(startVideo, 1200)
        }
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
        bar.layoutParams = bar.layoutParams.apply { width = (px(220) * f).toInt() }
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
        if (video.parent == null) videoHost.addView(video, 0, FrameLayout.LayoutParams(-1, -1))
        val p = player ?: ExoPlayer.Builder(activity).build().also { pl ->
            player = pl
            pl.setVideoTextureView(video)
            pl.volume = 0f
            pl.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    video.animate().alpha(1f).setDuration(600).start()
                    onPlaying(true)
                }
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
        val was = playing != null && video.alpha > 0f
        playing = null
        video.animate().cancel()
        video.alpha = 0f
        if (was) onPlaying(false)
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
        /** Tells [blend] whenever a logo lands, so the tile can take on the logo's own colour. */
        private val logo = object : ImageView(activity) {
            var swapping = false
            override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
                super.setImageDrawable(drawable)
                if (drawable != null && !swapping) (tag as? String)?.let { blend(it, drawable) }
            }
            fun swap(bitmap: android.graphics.Bitmap) { swapping = true; setImageBitmap(bitmap); swapping = false }
        }.apply { scaleType = ImageView.ScaleType.FIT_CENTER }

        /**
         * Many channel logos come on a solid box (a black square, a blue panel). On a tile of a
         * different colour that box looks cut out. So the tile takes the colour of the logo's
         * border and the logo's edges fade softly into it: no hard line anywhere. Logos with
         * see-through edges already float on the tile and are left as they are.
         */
        private fun blend(url: String, drawable: android.graphics.drawable.Drawable) {
            softLogos[url]?.let { apply(it); return }
            if (url in floating) return
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
            val key = channel?.key
            net.execute {
                val soft = soften(bitmap)
                if (soft == null) floating += url else softLogos.put(url, soft)
                if (soft != null) main.post { if (channel?.key == key && logo.tag == url) apply(soft) }
            }
        }

        private fun apply(soft: SoftLogo) {
            logo.swap(soft.bitmap)
            // A touch lighter at the top, like the other tiles, in the colour of the logo's border.
            face.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mix(soft.colour, Color.WHITE, 0.06f), soft.colour))
        }
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
            root.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5); marginStart = px(2) })
            root.setOnFocusChangeListener { v, has ->
                ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
                name.alpha = if (has) 1f else 0.75f
                v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).translationY(if (has) -px(4f).toFloat() else 0f)
                    .setDuration(RowRecyclerView.DURATION).setInterpolator(RowRecyclerView.EASE).start()
                v.elevation = if (has) 10 * dp else 0f
                if (has) channel?.let { select(it) }
            }
            root.setOnClickListener { channel?.let { LiveTv.open(activity, it) } }
            root.setOnLongClickListener { channel?.let { menu(it) }; true }
            root.setOnKeyListener { _, code, e ->
                // Holding OK opens the menu (handled here as well as by long-click: remotes differ).
                if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.action == KeyEvent.ACTION_DOWN && (e.isLongPress || e.repeatCount == 1)) {
                    channel?.let { menu(it) }; return@setOnKeyListener true
                }
                if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 1) return@setOnKeyListener true
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val row = rows.indexOfFirst { it === root.parent }
                when (code) {
                    // Sideways stays in the row: the next card may not be laid out yet, and the
                    // focus search then jumped up to the menu.
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val list = root.parent as? RecyclerView ?: return@setOnKeyListener true
                        val pos = list.getChildAdapterPosition(root) + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        if (pos < 0 || pos >= (list.adapter?.itemCount ?: 0)) return@setOnKeyListener true
                        val lm = list.layoutManager as LinearLayoutManager
                        lm.findViewByPosition(pos)?.requestFocus() ?: run {
                            list.scrollToPosition(pos)
                            list.post { lm.findViewByPosition(pos)?.requestFocus() }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { focusRow(row + 1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> if (row > 0) focusRow(row - 1) else { stopVideo(); false }
                    else -> false
                }
            }
        }

        fun bind(c: LiveTv.Channel) {
            channel = c
            name.text = c.name.substringAfter(": ").trim()
            val letters = name.text.take(2).toString().uppercase()
            initials.text = if (c.logos.isEmpty()) letters else ""
            val base = CoverArt.accentOf(c.name)
            face.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mix(base, 0xFF1A1F2A.toInt(), 0.55f), 0xFF10141C.toInt()))
            loadLogo(logo, c.logos, px(CARD_W)) { if (channel?.key == c.key) initials.text = letters }
            if (c.logo != null) Images.accent(c.logo!!) { col ->
                if (channel?.key == c.key && col != null && softLogos[logo.tag as? String] == null)
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

    /** Hold OK on a card: watch, hide it, or bring back hidden ones. */
    private var menuAt = 0L

    private fun menu(c: LiveTv.Channel) {
        // A held key reaches both the long-click and the key listener: open one menu only.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - menuAt < 1500) return
        menuAt = now
        val count = LiveTv.hidden(activity).size
        OptionsMenu.show(activity, c.name.substringAfter(": ").trim(), buildList {
            add("Watch" to { LiveTv.open(activity, c) })
            add("Hide this channel" to {
                LiveTv.hide(activity, c)
                val focusRowIndex = rows.indexOfFirst { r -> (0 until r.childCount).any { r.getChildAt(it).hasFocus() } }.coerceAtLeast(0)
                render()
                focusRow(focusRowIndex.coerceAtMost(rows.lastIndex))
            })
            if (count > 0) add("Show $count hidden channel${if (count == 1) "" else "s"}" to { LiveTv.unhideAll(activity); render() })
        })
    }

    /** Shows the first of [urls] that loads; [onNone] when none does. Loaded logos stay in the image cache. */
    private fun loadLogo(view: ImageView, urls: List<String>, width: Int, from: Int = 0, onNone: () -> Unit) {
        val url = urls.getOrNull(from) ?: run { view.setImageDrawable(null); onNone(); return }
        Images.into(view, url, width) { if (view.tag == url) loadLogo(view, urls, width, from + 1, onNone) }
    }

    /** A logo with its box edges faded out, and the colour of that box for its tile. */
    private class SoftLogo(val bitmap: android.graphics.Bitmap, val colour: Int)

    /** Softened logos, worked out once per logo; [floating] ones needed nothing. */
    private val softLogos = android.util.LruCache<String, SoftLogo>(80)
    private val floating: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Null when the logo's border is see-through (it floats on the tile as it is). */
    private fun soften(source: android.graphics.Bitmap): SoftLogo? = runCatching {
        val src = if (source.config == android.graphics.Bitmap.Config.HARDWARE) source.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else source
        val small = android.graphics.Bitmap.createScaledBitmap(src, 48, 48, true)
        val edge = buildList {
            for (i in 0 until 48) { add(small.getPixel(i, 0)); add(small.getPixel(i, 47)); add(small.getPixel(0, i)); add(small.getPixel(47, i)) }
        }
        if (edge.count { Color.alpha(it) > 230 } < edge.size * 0.9) return@runCatching null
        fun median(f: (Int) -> Int) = edge.map(f).sorted()[edge.size / 2]
        val colour = Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
        // Fade the outer edge out on all four sides.
        val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val w = out.width.toFloat(); val h = out.height.toFloat()
        val canvas = android.graphics.Canvas(out)
        val mask = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
        val f = 0.16f
        val ramp = intArrayOf(0, Color.BLACK, Color.BLACK, 0)
        val stops = floatArrayOf(0f, f, 1 - f, 1f)
        mask.shader = android.graphics.LinearGradient(0f, 0f, w, 0f, ramp, stops, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, mask)
        mask.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, ramp, stops, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, mask)
        SoftLogo(out, colour)
    }.getOrNull()

    // ---- Bits ----

    private fun text(s: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(activity).apply {
        setShadowLayer(10f, 0f, 1f, 0xCC000000.toInt())
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
