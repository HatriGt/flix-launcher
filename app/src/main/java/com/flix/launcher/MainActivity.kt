package com.flix.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    // Disk/package work and network work run apart, so a slow weather call never delays the app rows.
    private val io = Executors.newSingleThreadExecutor()
    // Several rows load at once; four threads keep the network busy without crowding the TV.
    private val net = Executors.newFixedThreadPool(4)
    private lateinit var repo: AppRepository

    private lateinit var clock: ClockView
    private lateinit var date: TextView
    private lateinit var greeting: TextView
    private lateinit var sunArc: SunArcView
    private lateinit var ui: View
    private lateinit var scrim: View
    private lateinit var veil: View
    private lateinit var focusedLabel: TextView
    private lateinit var sceneTitle: TextView
    private lateinit var videoView: android.view.SurfaceView
    private lateinit var imageView: ImageView
    private lateinit var favoritesAdapter: AppAdapter
    private lateinit var appsAdapter: AppAdapter
    private lateinit var favoritesList: RecyclerView
    private lateinit var appsList: RecyclerView

    private var player: SceneryPlayer? = null
    private var media: List<AerialSource.Media> = emptyList()
    private var imageIndex = 0
    private var allApps: List<AppEntry> = emptyList()
    private var lastWeatherAt = 0L
    private var initialFocusDone = false
    private var idle = false
    private var resumed = false
    private var labelTarget: CharSequence = ""
    private var sceneTarget: CharSequence = ""
    private var lastReport: Weather.Report? = null
    private var appliedRevision = -1
    private var settingsOpen = false
    private lateinit var movieRows: MovieRows
    private lateinit var hero: Hero
    private lateinit var rowsScroll: RowsScrollView
    private lateinit var header: View
    private lateinit var favoritesBlock: View
    private var browsing = false
    /**
     * Once the artwork covers everything, take the hidden full-screen layers (scrim,
     * scenery video, its veil) out of the frame: each costs the GPU and the compositor
     * a whole screen of pixels, which weaker TVs can't afford.
     */
    private val hideUnderHero = Runnable {
        if (!browsing) return@Runnable
        scrim.visibility = View.INVISIBLE
        if (hero.backdropEnabled) {
            videoView.visibility = View.INVISIBLE
            veil.visibility = View.INVISIBLE
            imageView.visibility = View.INVISIBLE
        }
    }
    private lateinit var trailer: Trailer
    private var scrollAnimator: android.animation.ObjectAnimator? = null
    private val pauseForBackdrop = Runnable { if (browsing && hero.backdropEnabled) player?.pause() }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applySettings()
            loadApps() // favourites or hidden apps may have changed too
        }
    }

    private val goIdle = Runnable { setIdle(true) }
    private val nextImage = Runnable { showImage(imageIndex + 1) }

    /** Kept paused for a while after leaving home so coming back is instant; then freed for the app in use. */
    private val releasePlayer = Runnable {
        player?.release()
        player = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            val is24 = when (FlixSettings.get(this@MainActivity, FlixSettings.CLOCK_FORMAT)) {
                "12" -> false
                "24" -> true
                else -> DateFormat.is24HourFormat(this@MainActivity)
            }
            clock.setTime(DateFormat.format(if (is24) "HH" else "h", now).toString(), DateFormat.format("mm", now).toString())
            date.text = DateFormat.format("EEEE  ·  d MMMM", now)
            greeting.text = greetingFor(DateFormat.format("H", now).toString().toInt())
            sunArc.tick()
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        repo = AppRepository(this)

        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        greeting = findViewById(R.id.greeting)
        sunArc = findViewById(R.id.sun_arc)
        ui = findViewById(R.id.ui)
        scrim = findViewById(R.id.scrim)
        veil = findViewById(R.id.video_veil)
        focusedLabel = findViewById(R.id.focused_label)
        sceneTitle = findViewById(R.id.scene_title)
        videoView = findViewById(R.id.wallpaper_video)
        imageView = findViewById(R.id.wallpaper_image)
        videoView.alpha = 1f
        Images.init(this)
        rowsScroll = findViewById(R.id.rows_scroll)
        header = findViewById(R.id.header)
        favoritesBlock = findViewById(R.id.favorites_block)
        hero = Hero(findViewById(R.id.ui))
        trailer = Trailer(this, findViewById(R.id.hero_trailer), net)
        trailer.onPlaying = { playing ->
            if (playing) {
                player?.pause()
                scheduleImmersive()
            } else endImmersive()
        }
        movieRows = MovieRows(this, findViewById(R.id.movie_rows), net, { title ->
            hero.present(title)
            trailer.focus(title)
        }, ::openInNuvio, ::showTitleMenu)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }
        rowsScroll.viewTreeObserver.addOnGlobalLayoutListener { sizeScrollArea() }

        favoritesAdapter = AppAdapter(tileStyle(favorites = true), ::onAppFocused, ::launch, ::showAppMenu)
        appsAdapter = AppAdapter(tileStyle(favorites = false), ::onAppFocused, ::launch, ::showAppMenu)
        favoritesList = setUpRow(R.id.favorites, favoritesAdapter)
        appsList = setUpRow(R.id.apps, appsAdapter)
        setUpAppsGrid()
        playEntrance()

        setUpTabs()
        // The rows carry each title's details under them now; the big top-left block isn't used.
        findViewById<View>(R.id.hero_info).visibility = View.GONE

        findViewById<View>(R.id.qs_inputs).setOnClickListener { openInputs() }
        findViewById<View>(R.id.qs_wifi).setOnClickListener { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        findViewById<View>(R.id.qs_settings).setOnClickListener { open(Intent(Settings.ACTION_SETTINGS)) }
        findViewById<View>(R.id.qs_flix).setOnClickListener { openFlixSettings() }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerReceiver(settingsReceiver, IntentFilter(SettingsActivity.ACTION_CHANGED), RECEIVER_NOT_EXPORTED_FLAG)
        loadApps()
        applySettings()
        // Scenery and its player wait until the first frame is on screen.
        window.decorView.post { loadWallpapers() }
    }

    private fun openFlixSettings() {
        settingsOpen = true
        startActivity(Intent(this, SettingsActivity::class.java))
        overridePendingTransition(0, 0)
    }

    private fun isOn(option: FlixSettings.Option) = FlixSettings.isOn(this, option)

    /** Tile geometry for the chosen shape and size; the favourites row is a step larger than All apps. */
    private fun tileStyle(favorites: Boolean): TileStyle {
        val dp = resources.displayMetrics.density
        val shape = FlixSettings.get(this, FlixSettings.ICON_SHAPE)
        val step = listOf("small", "medium", "large").indexOf(FlixSettings.get(this, FlixSettings.ICON_SIZE))
        val (w, h) = if (shape == "banner") {
            val width = (if (favorites) listOf(128, 160, 192) else listOf(96, 112, 136))[step]
            width to width * 9 / 16
        } else {
            // Favourites live in the dock along the top, so they stay compact.
            val size = (if (favorites) listOf(52, 62, 74) else listOf(50, 60, 70))[step]
            size to size
        }
        return TileStyle(
            shape = shape,
            widthPx = (w * dp).roundToInt(),
            heightPx = (h * dp).roundToInt(),
            labels = FlixSettings.get(this, FlixSettings.LABELS),
            glow = isOn(FlixSettings.GLOW),
            motion = FlixSettings.get(this, FlixSettings.MOTION),
            density = dp,
        )
    }

    /** Re-reads every option and updates the screen in place. */
    private fun applySettings() {
        appliedRevision = FlixSettings.revision(this)
        val dp = resources.displayMetrics.density
        for ((list, adapter, favorites) in listOf(Triple(favoritesList, favoritesAdapter, true), Triple(appsList, appsAdapter, false))) {
            val style = tileStyle(favorites)
            adapter.style = style
            val labelSpace = if (style.labels == "never") 0f else 24 * dp
            val growRoom = style.heightPx * (if (style.isBanner) 0.06f else 0.1f) + 8 * dp
            list.setPadding(list.paddingLeft, growRoom.roundToInt(), list.paddingRight, (growRoom * 0.6f).roundToInt())
            list.layoutParams = list.layoutParams.apply {
                height = (style.heightPx + labelSpace + growRoom * 1.6f).roundToInt()
            }
        }
        // With names under every tile, the big name above the row would just repeat them.
        focusedLabel.visibility = if (favoritesAdapter.style.labels == "always" && !favoritesAdapter.style.isBanner) View.GONE else View.VISIBLE
        findViewById<View>(R.id.hint).visibility = focusedLabel.visibility

        val allApps = isOn(FlixSettings.ALL_APPS)
        appsList.visibility = if (allApps) View.VISIBLE else View.GONE
        findViewById<View>(R.id.all_apps_label).visibility = appsList.visibility

        clock.setStyle(FlixSettings.get(this, FlixSettings.CLOCK_STYLE))
        greeting.visibility = if (isOn(FlixSettings.GREETING)) View.VISIBLE else View.GONE
        date.visibility = if (isOn(FlixSettings.DATE)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.weather_card).visibility = if (isOn(FlixSettings.WEATHER)) View.VISIBLE else View.GONE
        lastReport?.let(::showWeather)
        main.removeCallbacks(tick)
        main.post(tick)

        paintScrim(dimLevel())
        sceneTitle.visibility = if (isOn(FlixSettings.SCENE_NAME)) View.VISIBLE else View.INVISIBLE
        player?.shuffle = isOn(FlixSettings.SHUFFLE)
        hero.backdropEnabled = isOn(FlixSettings.BACKDROP)
        trailer.enabled = isOn(FlixSettings.TRAILERS)
        trailer.sound = isOn(FlixSettings.TRAILER_SOUND)
        trailer.delayMs = FlixSettings.get(this, FlixSettings.TRAILER_DELAY).toLong() * 1000
        movieRows.rebuild()
        startWallpaper()
        if (!idle) scheduleIdle()
    }

    // ---- Browsing movie rows: the clock steps aside for a preview of the focused title ----

    /** Where a focused movie row's title sits on screen: just under the top menu, as on Netflix. */
    private fun heroLine() = header.height + (4 * resources.displayMetrics.density).toInt()

    // ---- Top menu: Search · Home · Movies · Shows · Apps ----

    private lateinit var tabs: List<View>

    private fun setUpTabs() {
        val home = findViewById<View>(R.id.nav_home)
        val movies = findViewById<View>(R.id.nav_movies)
        val shows = findViewById<View>(R.id.nav_shows)
        val apps = findViewById<View>(R.id.nav_apps)
        tabs = listOf(home, movies, shows, apps)
        fun select(tab: View, type: String?) {
            tabs.forEach { it.isSelected = it === tab }
            showAppsGrid(tab === apps)
            if (tab !== apps) movieRows.filter(type)
        }
        select(home, null)
        // Like Netflix, landing on a tab switches to it; no need to press OK. A short pause
        // first, so sliding across tabs doesn't rebuild the rows for each one passed.
        listOf(home to null, movies to "movie", shows to "series", apps to null).forEach { (tab, type) ->
            val pick = Runnable { if (tab.isFocused && !tab.isSelected) select(tab, type) }
            tab.setOnClickListener { select(tab, type) }
            tab.setOnFocusChangeListener { _, has -> main.removeCallbacks(pick); if (has) main.postDelayed(pick, 300) }
        }
        // OK on Apps goes straight into the grid.
        apps.setOnClickListener { select(apps, null); appsGrid.post { appsGrid.getChildAt(0)?.requestFocus() } }
        findViewById<View>(R.id.nav_search).setOnClickListener {
            // Nuvio has no link into its search, so search here: the library's search box finds
            // apps and every film and series (Cinemeta), and a result opens in Nuvio.
            open(Intent(this, AllAppsActivity::class.java).putExtra(AllAppsActivity.EXTRA_SEARCH, true))
        }
    }

    /**
     * Apps sit at the top; the clock and weather fill the middle; the first movie row
     * rests fully visible at the bottom, and the last one can still reach the hero line.
     */
    private fun sizeScrollArea() {
        val height = rowsScroll.height
        if (height == 0) return
        val dp = resources.displayMetrics.density
        // The page reads top to bottom: header bar, apps dock, then the rows straight after.
        val wantTop = header.height + (6 * dp).toInt()
        if (favoritesBlock.paddingTop != wantTop) favoritesBlock.setPadding(0, wantTop, 0, favoritesBlock.paddingBottom)
        val spacer = findViewById<View>(R.id.rows_spacer)
        if (spacer.layoutParams.height != 0) spacer.layoutParams = spacer.layoutParams.apply { this.height = 0 }
        val last = movieRows.rows.lastOrNull { it.view.visibility == View.VISIBLE }?.view
        val tail = findViewById<View>(R.id.rows_tail)
        val wantTail = (height - heroLine() - (last?.height ?: 0)).coerceAtLeast(0)
        if (tail.layoutParams.height != wantTail) tail.layoutParams = tail.layoutParams.apply { this.height = wantTail }
        // Tiles may grow past the dock's top and bottom when focused, but must not slide under the buttons.
        val room = (60 * dp).toInt()
        val clip = android.graphics.Rect(0, -room, favoritesList.width, favoritesList.height + room)
        if (favoritesList.clipBounds != clip) favoritesList.clipBounds = clip
    }

    private fun onFocusMoved(now: View?) {
        now ?: return
        val row = movieRows.rowOf(now)
        if (row != null) {
            enterBrowse()
            val index = movieRows.rows.indexOf(row)
            movieRows.rows.forEachIndexed { i, r -> r.view.animate().alpha(if (i < index) 0f else 1f).setDuration(250).start() }
            movieRows.billboard.view.animate().alpha(0f).setDuration(250).start()
            val container = findViewById<View>(R.id.movie_rows)
            // After the details line has moved to this row (that re-lays the rows out).
            rowsScroll.post { scrollRowsTo(container.top + row.view.top - heroLine()) }
        } else if (isInside(now, appsGrid)) {
            exitBrowse()
            // First line: the page as it is. Further down, the focused line rises to just under
            // the menu and the lines above it fade, as the movie rows do.
            val first = now.top <= (appsGrid.getChildAt(0)?.top ?: 0)
            val fade = !first
            favoritesBlock.animate().alpha(if (fade) 0f else 1f).setDuration(250).start()
            appsGridLabel.animate().alpha(if (fade) 0f else 1f).setDuration(250).start()
            for (i in 0 until appsGrid.childCount) appsGrid.getChildAt(i).let { c ->
                c.animate().alpha(if (c.top < now.top - 4) 0f else 1f).setDuration(250).start()
            }
            scrollRowsTo(if (first) 0 else appsGrid.top + now.top - heroLine() - (10 * resources.displayMetrics.density).toInt())
        } else if (isInside(now, rowsScroll) || isInside(now, header)) {
            exitBrowse()
            if (::appsGrid.isInitialized && appsGrid.visibility == View.VISIBLE) showAppsGrid(true)
            scrollRowsTo(0)
        }
        if (row == null) {
            // Nothing to preview off the rows: a trailer must not keep the page faded out
            // (it did after switching tabs, leaving the home screen see-through).
            trailer.stop()
            endImmersive(force = true)
        } else {
            // The wallpaper's caption is for the idle screen only, never over the rows.
            sceneTitle.animate().cancel()
            sceneTitle.alpha = 0f
        }
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private fun scrollRowsTo(y: Int) {
        if (rowsScroll.scrollY == y) return
        scrollAnimator?.cancel()
        scrollAnimator = android.animation.ObjectAnimator.ofInt(rowsScroll, "scrollY", y).apply {
            duration = 300
            interpolator = DecelerateInterpolator(2.2f)
            start()
        }
    }

    private fun enterBrowse() {
        if (browsing) return
        browsing = true
        val dp = resources.displayMetrics.density
        // The top menu stays, as on Netflix; the clock and weather step aside for the rows.
        findViewById<View>(R.id.clock_group).animate().alpha(0f).setDuration(250).start()
        findViewById<View>(R.id.weather_card).animate().alpha(0f).setDuration(250).start()
        favoritesBlock.animate().alpha(0f).setDuration(250).start()
        hero.show()
        // Once the artwork covers everything, stop drawing the layers hidden beneath it.
        main.postDelayed(hideUnderHero, 400)
        // Once the artwork covers the scenery, stop decoding video nobody can see.
        main.removeCallbacks(pauseForBackdrop)
        main.postDelayed(pauseForBackdrop, 1500)
    }

    private fun exitBrowse() {
        if (!browsing) return
        browsing = false
        findViewById<View>(R.id.clock_group).animate().alpha(1f).setDuration(300).start()
        if (isOn(FlixSettings.WEATHER)) findViewById<View>(R.id.weather_card).animate().alpha(1f).setDuration(300).start()
        favoritesBlock.animate().alpha(1f).setDuration(300).start()
        movieRows.billboard.view.animate().alpha(1f).setDuration(300).start()
        movieRows.clearDetails()
        movieRows.rows.forEach { it.view.animate().alpha(1f).setDuration(250).start() }
        main.removeCallbacks(hideUnderHero)
        scrim.visibility = if (player == null && scenery().none { it.isVideo } || !videoWallpaper()) View.GONE else View.VISIBLE
        // An idle SurfaceView punches a black hole through the window, hiding the photo under it.
        videoView.visibility = if (player != null) View.VISIBLE else View.GONE
        veil.visibility = View.VISIBLE
        imageView.visibility = View.VISIBLE
        hero.hide()
        trailer.stop()
        main.removeCallbacks(pauseForBackdrop)
        if (resumed) player?.play()
    }

    private fun openInNuvio(title: Catalog.Title) {
        if (title.id == MovieRows.SIGN_IN.id) {
            settingsOpen = true
            startActivity(Intent(this, NuvioLoginActivity::class.java))
            overridePendingTransition(0, 0)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(title.nuvioUri)).setPackage(NUVIO)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "Install Nuvio to watch this", Toast.LENGTH_SHORT).show()
            return
        }
        // Nuvio is a heavy app to start; give it the TV's full attention. Stop the trailer
        // (its process and memory) and skip the home screen's zoom-out, which competed with
        // Nuvio's launch for the GPU and made the hand-over judder. A plain crossfade instead.
        trailer.stop()
        main.removeCallbacks(pauseForBackdrop)
        player?.pause()
        runCatching {
            // Nuvio keeps one screen alive and ignores a new link while it's open (it would show the
            // last film again), so start its task afresh to make it read this one.
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                android.app.ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
        }.onFailure { Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show() }
    }

    private fun showTitleMenu(title: Catalog.Title) {
        if (title.id == MovieRows.SIGN_IN.id) return openInNuvio(title)
        OptionsMenu.show(this, title, listOf(
            "Open in Nuvio" to { openInNuvio(title) },
            "Hide this title" to {
                Catalog.hideTitle(this, title.id)
                movieRows.refreshHidden()
            },
        ))
    }

    /**
     * One full-screen gradient does the shading and the dimming together: each extra
     * full-screen layer costs the TV's GPU a whole frame's worth of pixels.
     */
    private fun paintScrim(dim: Float) {
        // With no scenery (no wallpaper source, or the plain background), the dark backdrop
        // needs no shading on top: one less full-screen layer for the GPU to fill.
        scrim.visibility = if (player == null && scenery().none { it.isVideo } || !videoWallpaper()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dim).visibility = View.GONE
        fun shade(a: Float) = (((a + dim * (1 - a)).coerceIn(0f, 1f) * 255).toInt() shl 24)
        scrim.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(shade(0.85f), shade(0.25f), shade(0.05f), shade(0.45f)),
        )
    }

    private fun dimLevel() = when (FlixSettings.get(this, FlixSettings.DIM)) {
        "low" -> 0f
        "high" -> 0.3f
        else -> 0.12f
    }

    // ---- Apps tab: every app in a grid right on the home screen, no separate page to load ----

    private lateinit var appsGrid: RecyclerView
    private lateinit var appsGridAdapter: AppAdapter
    private lateinit var appsGridLabel: android.widget.TextView

    private fun setUpAppsGrid() {
        val dp = resources.displayMetrics.density
        appsGridAdapter = AppAdapter(gridStyle(), ::onAppFocused, ::launch, ::showAppMenu)
        appsGridLabel = android.widget.TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding((44 * dp).toInt(), (8 * dp).toInt(), 0, (4 * dp).toInt())
            visibility = View.GONE
        }
        appsGrid = RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, GRID_COLUMNS)
            adapter = appsGridAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding((34 * dp).toInt(), (12 * dp).toInt(), (34 * dp).toInt(), (24 * dp).toInt())
            visibility = View.GONE
            // Each app fills its column so the grid lines up, whatever the name's length.
            addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    (view.layoutParams as ViewGroup.MarginLayoutParams).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() }
                }
                override fun onChildViewDetachedFromWindow(view: View) {}
            })
        }
        val content = findViewById<ViewGroup>(R.id.rows_content)
        val at = content.indexOfChild(findViewById(R.id.movie_rows)) + 1
        content.addView(appsGridLabel, at)
        content.addView(appsGrid, at + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Larger tiles than the dock, always named, since the grid is for finding an app. */
    private fun gridStyle(): TileStyle {
        val base = tileStyle(favorites = false)
        if (base.isBanner) return base.copy(labels = "always")
        val size = (68 * resources.displayMetrics.density).roundToInt()
        return base.copy(widthPx = size, heightPx = size, labels = "always")
    }

    private fun showAppsGrid(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        favoritesBlock.alpha = 1f
        appsGridLabel.alpha = 1f
        for (i in 0 until appsGrid.childCount) appsGrid.getChildAt(i).alpha = 1f
        if (appsGrid.visibility == v) return
        findViewById<View>(R.id.movie_rows).visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            appsGridAdapter.style = gridStyle()
            appsGridAdapter.items = allApps
            appsGridLabel.text = "All apps  ·  ${allApps.size}"
        }
        appsGridLabel.visibility = v
        appsGrid.visibility = v
        rowsScroll.scrollTo(0, 0)
    }

    private fun setUpRow(id: Int, adapter: AppAdapter): RecyclerView =
        findViewById<RecyclerView>(id).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            layoutAnimation = AnimationUtils.loadLayoutAnimation(this@MainActivity, R.anim.row_enter)
        }

    /** Clock slides in from the left, the buttons drop in; the rows rise in one card at a time. */
    private fun playEntrance() {
        val dp = resources.displayMetrics.density
        val ease = DecelerateInterpolator(2f)
        findViewById<View>(R.id.clock_group).apply {
            alpha = 0f
            translationX = -28 * dp
            animate().alpha(1f).translationX(0f).setDuration(650).setInterpolator(ease).start()
        }
        findViewById<View>(R.id.quick_settings).apply {
            alpha = 0f
            translationY = -16 * dp
            animate().alpha(1f).translationY(0f).setStartDelay(150).setDuration(550).setInterpolator(ease).start()
        }
    }

    private fun greetingFor(hour: Int) = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }

    override fun onStart() {
        super.onStart()
        if (launchedFromHome) playReturn()
        main.removeCallbacks(tick)
        main.post(tick)
        refreshWeather()
        main.removeCallbacks(releasePlayer)
        startWallpaper()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        settingsOpen = false
        // Pages that float over home (search, All apps) don't stop it, so onStart never undid
        // the launch fade and home stayed at 40%. Undo it whenever home is back in front.
        if (launchedFromHome || ui.alpha < 1f && !idle) playReturn()
        // The service connects asynchronously at process start; check once it has had a moment.
        main.postDelayed({ HomeGuard.ensure(this) }, 3000)
        if (appliedRevision != FlixSettings.revision(this)) applySettings()
        if (browsing) main.postDelayed(pauseForBackdrop, 1500)
        movieRows.refreshContinue()
        player?.play()
        if (idle) setIdle(false) else scheduleIdle()
    }

    override fun onPause() {
        resumed = false
        trailer.stop()
        // The settings panel is see-through, so keep the scenery moving behind it.
        if (!settingsOpen) player?.pause()
        main.removeCallbacks(goIdle)
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) Images.trim()
    }

    override fun onStop() {
        Images.trim()
        main.removeCallbacks(tick)
        main.removeCallbacks(nextImage)
        main.postDelayed(releasePlayer, KEEP_PLAYER_MS)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(settingsReceiver)
        trailer.release()
        main.removeCallbacksAndMessages(null)
        player?.release()
        io.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while already home: go back to the first favourite. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home always lands at the start of the dock, whether pressed on the home
        // screen or from inside another app.
        main.post { returnToStart() }
    }

    @Deprecated("Home screen has nowhere to go back to")
    override fun onBackPressed() {
        // While a trailer has the screen to itself, Back just brings the page back.
        if (immersed) return endImmersive()
        returnToStart()
    }

    // ---- Immersive trailer: like Netflix, the page steps aside so the trailer fills the screen ----

    private var immersed = false
    private val immersiveViews by lazy {
        listOf(rowsScroll, header, findViewById<View>(R.id.hero_shade))
    }
    private val dimPage = Runnable { if (trailer.isPlaying) { immersed = true; fadePage(0.3f, 600) } }
    private val clearPage = Runnable { if (trailer.isPlaying) fadePage(0f, 700) }

    /** 4 s into a trailer the page drops to 30%; a second later it's gone. */
    private fun scheduleImmersive() {
        main.removeCallbacks(dimPage); main.removeCallbacks(clearPage)
        main.postDelayed(dimPage, 4000)
        main.postDelayed(clearPage, 5000)
    }

    private fun endImmersive(force: Boolean = false) {
        main.removeCallbacks(dimPage); main.removeCallbacks(clearPage)
        if (!immersed && !(force && immersiveViews.any { it.alpha < 1f })) return
        immersed = false
        fadePage(1f, 250)
    }

    private fun fadePage(alpha: Float, ms: Long) = immersiveViews.forEach {
        it.animate().cancel()
        it.animate().alpha(alpha).setDuration(ms).start()
    }

    private fun returnToStart() {
        if (idle) setIdle(false)
        exitBrowse()
        rowsScroll.scrollTo(0, 0)
        appsList.smoothScrollToPosition(0)
        focusFavorite(0)
    }

    // ---- Idle mode: after a quiet minute the UI fades away and leaves just the scenery ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (immersed && event.keyCode != KeyEvent.KEYCODE_BACK) {
            // Any other key brings the page back first, without acting on what can't be seen.
            if (event.action == KeyEvent.ACTION_UP) { trailer.stop(); endImmersive() }
            return true
        }
        if (idle) {
            if (event.action == KeyEvent.ACTION_UP) setIdle(false)
            return true // the key that wakes the screen shouldn't also move focus
        }
        scheduleIdle()
        if (event.action == KeyEvent.ACTION_DOWN && steerMovieRows(event)) return true
        return super.dispatchKeyEvent(event)
    }


    /**
     * Movie-row navigation, Netflix style:
     *  - Left at the first card / Right at the last card stays put instead of jumping rows.
     *  - Up/Down lands on the card at the start of the next row (where its big card sits),
     *    rather than whichever card happens to be geometrically closest.
     */
    private fun steerMovieRows(event: KeyEvent): Boolean {
        val focused = currentFocus ?: return false
        // Up from the apps dock goes to the top menu's current tab (it isn't straight above most apps).
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && isInside(focused, favoritesList)) {
            tabs.firstOrNull { it.isSelected }?.let { it.requestFocus(); return true }
        }
        // Down from the billboard lands on the first row's first card, not wherever focus search guesses.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isInside(focused, movieRows.billboard.view)) {
            movieRows.rows.firstOrNull { it.view.visibility == View.VISIBLE }?.let { focusRowStart(it); return true }
        }
        val row = movieRows.rowOf(focused) ?: return false
        val pos = row.list.getChildAdapterPosition(focused)
        val last = row.adapter.itemCount - 1
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> return pos == 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> return pos == last
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                val visible = movieRows.rows.filter { it.view.visibility == View.VISIBLE }
                val i = visible.indexOf(row) + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (i < 0) {
                    // Up from the first row: to the billboard's Play button, else the dock as before.
                    val bb = movieRows.billboard
                    if (bb.view.visibility == View.VISIBLE) { bb.play.requestFocus(); return true }
                    return false
                }
                if (i >= visible.size) return true // nothing below: stay
                if (event.repeatCount > 0 && rowsScrolling()) return true // don't outrun the scroll
                focusRowStart(visible[i])
                return true
            }
        }
        return false
    }

    private fun rowsScrolling() = scrollAnimator?.isRunning == true

    private fun focusRowStart(target: MovieRows.Row) {
        val lm = target.list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val card = target.list.findViewHolderForAdapterPosition(pos)?.itemView
        if (card != null) { card.requestFocus(); return }
        target.list.scrollToPosition(pos)
        target.list.post { target.list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun scheduleIdle() {
        main.removeCallbacks(goIdle)
        val seconds = FlixSettings.get(this, FlixSettings.IDLE).toLongOrNull() ?: return
        main.postDelayed(goIdle, seconds * 1000)
    }

    private fun setIdle(value: Boolean) {
        if (value && (scenery().isEmpty() || !videoWallpaper())) return scheduleIdle() // nothing to look at without scenery
        if (value && trailer.isPlaying) return scheduleIdle() // let the trailer play out
        if (value && browsing) returnToStart() // fade to the scenery, not to a paused backdrop
        idle = value
        val duration = if (value) 1200L else 350L
        ui.animate().alpha(if (value) 0f else 1f).setDuration(duration).withLayer().start()
        scrim.animate().alpha(if (value) 0.25f else 1f).setDuration(duration).start()
        paintScrim(if (value) 0f else dimLevel())
        sceneTitle.animate().alpha(if (value) 0.8f else 0f).setDuration(duration).start()
        if (!value) scheduleIdle()
    }

    // ---- Apps ----

    private fun loadApps() {
        io.execute {
            // Favourites first: the row shows while every other app's icon is still being drawn.
            val apps = repo.loadApps(repo.favorites) { firstApps ->
                if (allApps.isNotEmpty()) return@loadApps
                main.post {
                    if (isDestroyed || allApps.isNotEmpty()) return@post
                    allApps = firstApps
                    bindRows()
                    if (!initialFocusDone) {
                        initialFocusDone = true
                        focusFavorite(0)
                    }
                }
            }
            main.post {
                if (isDestroyed) return@post
                allApps = apps
                bindRows()
                if (!initialFocusDone) {
                    initialFocusDone = true
                    focusFavorite(0)
                }
            }
        }
    }

    private fun bindRows() {
        val byPackage = allApps.associateBy { it.packageName }
        val favorites = repo.favorites
        val tile = if (isOn(FlixSettings.ALL_APPS_TILE)) listOf(repo.allAppsTile()) else emptyList()
        favoritesAdapter.items = favorites.mapNotNull { byPackage[it] } + tile
        appsAdapter.items = allApps.filter { it.packageName !in favorites }
        if (::appsGrid.isInitialized && appsGrid.visibility == View.VISIBLE) appsGridAdapter.items = allApps
    }

    private fun onAppFocused(app: AppEntry) {
        if (labelTarget == app.label) return
        labelTarget = app.label
        val dp = resources.displayMetrics.density
        focusedLabel.animate().cancel()
        focusedLabel.animate().alpha(0f).translationY(4 * dp).setDuration(90).withEndAction {
            focusedLabel.text = labelTarget
            focusedLabel.translationY = -4 * dp
            focusedLabel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun launch(app: AppEntry) {
        if (app.packageName == AppRepository.ALL_APPS_TILE) {
            // Like settings, the All apps page floats over the scenery, so keep it playing.
            settingsOpen = true
            startActivity(app.launchIntent)
            overridePendingTransition(0, 0)
            return
        }
        open(app.launchIntent)
    }

    private fun showAppMenu(app: AppEntry) {
        if (app.packageName == AppRepository.ALL_APPS_TILE) return
        val isFavorite = app.packageName in repo.favorites
        val actions = buildList<Pair<String, () -> Unit>> {
            add("Open" to { launch(app) })
            add((if (isFavorite) "Remove from favourites" else "Add to favourites") to {
                repo.toggleFavorite(app.packageName)
                bindRows()
            })
            if (isFavorite) {
                add("Move left" to { moveFavorite(app, -1) })
                add("Move right" to { moveFavorite(app, 1) })
            } else {
                add("Hide from All apps" to {
                    repo.hide(app.packageName)
                    loadApps()
                })
            }
            add("App info" to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) })
            add("Uninstall" to { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) })
        }
        OptionsMenu.show(this, app, actions)
    }

    /** Reorders a favourite and keeps focus on it as it moves. */
    private fun moveFavorite(app: AppEntry, delta: Int) {
        repo.moveFavorite(app.packageName, delta)
        bindRows()
        focusFavorite(favoritesAdapter.items.indexOfFirst { it.packageName == app.packageName })
    }

    /** Scrolls to a favourite and focuses it once its card has been laid out. */
    private fun focusFavorite(position: Int, attempts: Int = 10) {
        if (position < 0 || position >= favoritesAdapter.itemCount) return
        favoritesList.scrollToPosition(position)
        favoritesList.post {
            val card = favoritesList.findViewHolderForAdapterPosition(position)?.itemView
            if (card != null) card.requestFocus() else if (attempts > 0) main.postDelayed({ focusFavorite(position, attempts - 1) }, 50)
        }
    }

    /** The remote's Menu button opens the options for whichever app card is focused. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MENU) return super.onKeyUp(keyCode, event)
        val focused = currentFocus ?: return true
        movieRows.rowOf(focused)?.let { row ->
            row.adapter.items.getOrNull(row.list.getChildAdapterPosition(focused))?.let(::showTitleMenu)
            return true
        }
        val adapter = when (focused.parent) {
            favoritesList -> favoritesAdapter
            appsList -> appsAdapter
            else -> return true
        }
        val position = (focused.parent as RecyclerView).getChildAdapterPosition(focused)
        adapter.items.getOrNull(position)?.let(::showAppMenu)
        return true
    }

    private fun openInputs() {
        val inputs = Intent("com.android.tv.action.VIEW_INPUTS")
        if (inputs.resolveActivity(packageManager) != null) open(inputs) else open(Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * Opens an app growing out of the tile or poster that launched it, while the
     * home screen eases back; coming home reverses it (see [playReturn]).
     */
    private fun open(intent: Intent) {
        val source = currentFocus
        val options = source?.let {
            android.app.ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        launchedFromHome = true
        ui.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.4f).setDuration(260)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) }
            .onFailure {
                playReturn()
                Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show()
            }
    }

    private var launchedFromHome = false

    /** Home settles back into place when returning from an app. */
    private fun playReturn() {
        launchedFromHome = false
        ui.animate().cancel()
        ui.scaleX = 1.04f
        ui.scaleY = 1.04f
        ui.alpha = 0f
        ui.animate().scaleX(1f).scaleY(1f).alpha(if (idle) 0f else 1f).setDuration(420)
            .setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    // ---- Weather ----

    private fun refreshWeather() {
        if (System.currentTimeMillis() - lastWeatherAt < 30 * 60_000) return
        lastWeatherAt = System.currentTimeMillis() // one request in flight at a time
        net.execute {
            val report = Weather.fetch()
            main.post {
                if (isDestroyed) return@post
                if (report == null) lastWeatherAt = 0L else showWeather(report)
            }
        }
    }

    private fun showWeather(report: Weather.Report) {
        lastReport = report
        val f = FlixSettings.get(this, FlixSettings.UNITS) == "f"
        fun t(c: Int) = if (f) (c * 9f / 5f + 32f).roundToInt() else c
        findViewById<TextView>(R.id.weather_place).text = report.place
        findViewById<TextView>(R.id.weather_icon).text = report.icon
        findViewById<TextView>(R.id.weather_temp).text = "${t(report.temperature)}°"
        findViewById<TextView>(R.id.weather_condition).text = report.condition
        findViewById<TextView>(R.id.weather_details).text =
            "Feels ${t(report.feelsLike)}°"
        sunArc.visibility = View.GONE

        val forecast = findViewById<LinearLayout>(R.id.forecast)
        forecast.visibility = View.GONE
        findViewById<View>(R.id.forecast_divider).visibility = forecast.visibility
        forecast.removeAllViews()
        for (day in emptyList<Weather.Day>()) {
            forecast.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 10f
                setLineSpacing(0f, 1.25f)
                text = "${day.name}\n${day.icon}\n${t(day.high)}°  ${t(day.low)}°"
                alpha = 0.92f
            })
        }

        val card = findViewById<View>(R.id.weather_card)
        if (card.alpha < 1f) {
            card.translationY = -10 * resources.displayMetrics.density
            card.animate().alpha(1f).translationY(0f).setDuration(600)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    // ---- Wallpaper ----

    /** The wallpaper choice the current scenery was built for. */
    private var wallMode = ""

    private fun wallChoice() = FlixSettings.get(this, FlixSettings.WALLPAPER)

    private fun isVideoChoice(choice: String) = choice == "video" || choice.startsWith("v_")

    /**
     * Gathers the clips for a video choice: an installed wallpaper provider such as Aerial
     * Views for "All aerial videos", otherwise the free aerial feed. Photo choices need nothing.
     */
    private fun loadWallpapers() {
        val choice = wallChoice()
        wallMode = choice
        media = emptyList()
        if (!isVideoChoice(choice)) return startWallpaper()
        val got = { items: List<AerialSource.Media> ->
            if (!isDestroyed && wallMode == choice) { media = items; startWallpaper() }
        }
        val feed = { VideoFeed.clips(this, if (choice == "video") "all" else choice.removePrefix("v_"), got) }
        if (choice == "video") AerialSource.fetch(this) { items -> main.post { if (items.any { it.isVideo }) got(items) else feed() } }
        else feed()
    }

    /** Any scenery at all (photos or video), rather than the plain gradient. */
    private fun videoWallpaper() = wallChoice() != "gradient"

    /** What the background shows: the chosen videos once they're known, otherwise photos. */
    private fun scenery(): List<AerialSource.Media> =
        if (isVideoChoice(wallChoice()) && media.isNotEmpty()) media else NatureWalls.all

    private fun startWallpaper() {
        if (wallMode.isEmpty()) return // the first load waits for the first frame (see onCreate)
        if (wallChoice() != wallMode) {
            // A different wallpaper was picked: drop the old scenery and gather the new.
            main.removeCallbacks(nextImage)
            releasePlayer.run()
            videoView.visibility = View.GONE
            return loadWallpapers()
        }
        if (!videoWallpaper()) {
            main.removeCallbacks(nextImage)
            releasePlayer.run()
            veil.animate().alpha(1f).setDuration(500).start()
            showSceneTitle(null)
            return
        }
        val items = scenery()
        val videos = items.filter { it.isVideo }
        if (videos.isNotEmpty()) startVideos(videos) else {
            releasePlayer.run()
            videoView.visibility = View.GONE
            if (items.isNotEmpty()) showImage(imageIndex)
        }
    }

    private fun startVideos(videos: List<AerialSource.Media>) {
        player?.let {
            if (resumed) it.play()
            return
        }
        main.removeCallbacks(nextImage) // the photos shown while the clips were found stop here
        veil.alpha = 1f
        videoView.visibility = View.VISIBLE
        player = SceneryPlayer(videoView, videos, isOn(FlixSettings.SHUFFLE),
            onFirstFrame = {
                scrim.visibility = View.VISIBLE
                veil.animate().alpha(0f).setDuration(900).start()
            },
            onClip = { title, auto ->
                showSceneTitle(title)
                if (auto) {
                    // Soften the hard cut between clips with a quick dip.
                    veil.alpha = 0.55f
                    veil.animate().alpha(0f).setDuration(1100).start()
                }
            }).also { if (resumed) it.play() }
    }

    private fun showSceneTitle(title: String?) {
        val text = if (title.isNullOrBlank()) "" else "◉  $title"
        if (sceneTarget == text) return
        sceneTarget = text
        sceneTitle.animate().cancel()
        sceneTitle.animate().alpha(0f).setDuration(250).withEndAction {
            sceneTitle.text = sceneTarget
            sceneTitle.animate().alpha(if (idle && !browsing) 0.8f else 0f).setDuration(600).start()
        }.start()
    }

    private fun showImage(index: Int) {
        val choice = wallChoice()
        if (choice == "nature" || choice == "cities") {
            // A fresh photo from the endless feed; the built-in set only when it can't be reached.
            WallpaperFeed.next(this, resources.displayMetrics.widthPixels, choice) { bitmap, title ->
                if (bitmap != null) present(bitmap, title) else showBuiltIn(index)
            }
            return
        }
        showBuiltIn(index)
    }

    private fun present(bitmap: android.graphics.Bitmap, title: String?) {
        if (isDestroyed) return
        // Dip through the dark backdrop so one photo dissolves into the next.
        val first = imageView.drawable == null
        veil.animate().cancel()
        veil.animate().alpha(if (first) 1f else 0.7f).setDuration(if (first) 0 else 600).withEndAction {
            imageView.setImageBitmap(bitmap) // the previous photo is released here
            veil.animate().alpha(0f).setDuration(1200).start()
        }.start()
        scrim.visibility = View.VISIBLE
        showSceneTitle(title)
        main.removeCallbacks(nextImage)
        main.postDelayed(nextImage, ROTATE_MS)
    }

    private fun showBuiltIn(index: Int) {
        val images = scenery().filter { !it.isVideo }
        if (images.isEmpty()) return
        imageIndex = (if (isOn(FlixSettings.SHUFFLE) && index != imageIndex) (images.indices).random() else index) % images.size
        val item = images[imageIndex]
        val uri = Uri.parse(item.uri)
        val ready = { bitmap: android.graphics.Bitmap -> present(bitmap, item.title) }
        if (uri.scheme == "http" || uri.scheme == "https") {
            // Downscaled, cached on disk and kept on the GPU, like the posters.
            Images.get(item.uri, resources.displayMetrics.widthPixels) { it?.let(ready) ?: main.postDelayed(nextImage, 10_000) }
        } else net.execute {
            val bitmap = runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                ?: return@execute
            main.post { ready(bitmap) }
        }
    }

    companion object {
        const val NUVIO = "com.nuvio.tv"
        /** How long each wallpaper photo stays up. */
        const val ROTATE_MS = 60_000L
        private const val GRID_COLUMNS = 7
        const val RECEIVER_NOT_EXPORTED_FLAG = 0x4 // Context.RECEIVER_NOT_EXPORTED, named in API 33
        const val KEEP_PLAYER_MS = 90_000L
    }
}
