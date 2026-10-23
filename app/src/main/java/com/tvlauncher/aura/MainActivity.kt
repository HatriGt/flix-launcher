package com.tvlauncher.aura

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
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.URL
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    // Disk/package work and network work run apart, so a slow weather call never delays the app rows.
    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
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
    private lateinit var videoView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var favoritesAdapter: AppAdapter
    private lateinit var appsAdapter: AppAdapter
    private lateinit var favoritesList: RecyclerView
    private lateinit var appsList: RecyclerView

    private var player: ExoPlayer? = null
    private var media: List<AerialSource.Media> = emptyList()
    private var imageIndex = 0
    private var allApps: List<AppEntry> = emptyList()
    private var lastWeatherAt = 0L
    private var initialFocusDone = false
    private var idle = false
    private var resumed = false
    private var labelTarget: CharSequence = ""
    private var sceneTarget: CharSequence = ""

    private val goIdle = Runnable { setIdle(true) }
    private val nextImage = Runnable { showImage(imageIndex + 1) }

    /** Kept paused for a while after leaving home so coming back is instant; then freed for the app in use. */
    private val releasePlayer = Runnable {
        player?.release()
        player = null
        videoView.player = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            val is24 = DateFormat.is24HourFormat(this@MainActivity)
            clock.setTime(DateFormat.format(if (is24) "HH" else "h", now).toString(), DateFormat.format("mm", now).toString())
            date.text = DateFormat.format("EEEE, d MMMM", now)
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

        val dp = resources.displayMetrics.density
        favoritesAdapter = AppAdapter((160 * dp).roundToInt(), (90 * dp).roundToInt(), ::onAppFocused, ::launch, ::showAppMenu)
        appsAdapter = AppAdapter((112 * dp).roundToInt(), (63 * dp).roundToInt(), ::onAppFocused, ::launch, ::showAppMenu)
        favoritesList = setUpRow(R.id.favorites, favoritesAdapter)
        appsList = setUpRow(R.id.apps, appsAdapter)
        playEntrance()

        findViewById<View>(R.id.qs_inputs).setOnClickListener { openInputs() }
        findViewById<View>(R.id.qs_wifi).setOnClickListener { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        findViewById<View>(R.id.qs_settings).setOnClickListener { open(Intent(Settings.ACTION_SETTINGS)) }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
        loadWallpapers()
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
        main.removeCallbacks(tick)
        main.post(tick)
        refreshWeather()
        main.removeCallbacks(releasePlayer)
        startWallpaper()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        player?.play()
        if (idle) setIdle(false) else scheduleIdle()
    }

    override fun onPause() {
        resumed = false
        player?.pause()
        main.removeCallbacks(goIdle)
        super.onPause()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        main.removeCallbacks(nextImage)
        main.postDelayed(releasePlayer, KEEP_PLAYER_MS)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        main.removeCallbacksAndMessages(null)
        player?.release()
        io.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while already home: go back to the first favourite. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (resumed) returnToStart()
    }

    @Deprecated("Home screen has nowhere to go back to")
    override fun onBackPressed() = returnToStart()

    private fun returnToStart() {
        if (idle) setIdle(false)
        appsList.smoothScrollToPosition(0)
        focusFavorite(0)
    }

    // ---- Idle mode: after a quiet minute the UI fades away and leaves just the scenery ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (idle) {
            if (event.action == KeyEvent.ACTION_UP) setIdle(false)
            return true // the key that wakes the screen shouldn't also move focus
        }
        scheduleIdle()
        return super.dispatchKeyEvent(event)
    }

    private fun scheduleIdle() {
        main.removeCallbacks(goIdle)
        main.postDelayed(goIdle, IDLE_AFTER_MS)
    }

    private fun setIdle(value: Boolean) {
        if (value && media.isEmpty()) return scheduleIdle() // nothing to look at without scenery
        idle = value
        val duration = if (value) 1200L else 350L
        ui.animate().alpha(if (value) 0f else 1f).setDuration(duration).withLayer().start()
        scrim.animate().alpha(if (value) 0.25f else 1f).setDuration(duration).start()
        if (!value) scheduleIdle()
    }

    // ---- Apps ----

    private fun loadApps() {
        io.execute {
            val apps = repo.loadApps()
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
        favoritesAdapter.items = favorites.mapNotNull { byPackage[it] }
        appsAdapter.items = allApps.filter { it.packageName !in favorites }
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
        open(app.launchIntent)
    }

    private fun showAppMenu(app: AppEntry) {
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

    private fun open(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show() }
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
        findViewById<TextView>(R.id.weather_place).text = report.place
        findViewById<TextView>(R.id.weather_icon).text = report.icon
        findViewById<TextView>(R.id.weather_temp).text = "${report.temperature}°"
        findViewById<TextView>(R.id.weather_condition).text = report.condition
        findViewById<TextView>(R.id.weather_details).text =
            "Feels ${report.feelsLike}° · ${report.humidity}% · ${report.windKmh} km/h"
        sunArc.setSun(report.sunrise, report.sunset)

        val forecast = findViewById<LinearLayout>(R.id.forecast)
        forecast.removeAllViews()
        for (day in report.days.take(3)) {
            forecast.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 10f
                setLineSpacing(0f, 1.25f)
                text = "${day.name}\n${day.icon}\n${day.high}°  ${day.low}°"
                alpha = 0.92f
            })
        }

        val card = findViewById<View>(R.id.weather_card)
        if (card.alpha < 1f) {
            card.translationX = 24 * resources.displayMetrics.density
            card.animate().alpha(1f).translationX(0f).setDuration(600)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    // ---- Wallpaper ----

    private fun loadWallpapers() {
        AerialSource.fetch(this) { items ->
            main.post {
                if (isDestroyed) return@post
                media = items
                startWallpaper()
            }
        }
    }

    private fun startWallpaper() {
        val videos = media.filter { it.isVideo }
        if (videos.isNotEmpty()) startVideos(videos) else if (media.isNotEmpty()) showImage(imageIndex)
    }

    private fun startVideos(videos: List<AerialSource.Media>) {
        player?.let {
            if (resumed) it.play()
            return
        }
        veil.alpha = 1f
        val exo = ExoPlayer.Builder(this).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            shuffleModeEnabled = true
            setMediaItems(videos.map { MediaItem.Builder().setUri(it.uri).setTag(it.title).build() })
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    veil.animate().alpha(0f).setDuration(900).start()
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Skip a clip that won't load rather than leaving the background frozen.
                    if (mediaItemCount > 1) {
                        seekToNextMediaItem()
                        prepare()
                    }
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    showSceneTitle(item?.localConfiguration?.tag as? String)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        // Soften the hard cut between clips with a quick dip.
                        veil.alpha = 0.55f
                        veil.animate().alpha(0f).setDuration(1100).start()
                    }
                }
            })
            prepare()
            playWhenReady = resumed
        }
        videoView.player = exo
        player = exo
    }

    private fun showSceneTitle(title: String?) {
        val text = if (title.isNullOrBlank()) "" else "◉  $title"
        if (sceneTarget == text) return
        sceneTarget = text
        sceneTitle.animate().cancel()
        sceneTitle.animate().alpha(0f).setDuration(250).withEndAction {
            sceneTitle.text = sceneTarget
            sceneTitle.animate().alpha(0.8f).setDuration(600).start()
        }.start()
    }

    private fun showImage(index: Int) {
        val images = media.filter { !it.isVideo }
        if (images.isEmpty()) return
        imageIndex = index % images.size
        val item = images[imageIndex]
        net.execute {
            val bitmap = runCatching {
                val uri = Uri.parse(item.uri)
                val stream = if (uri.scheme == "http" || uri.scheme == "https") URL(item.uri).openStream()
                else contentResolver.openInputStream(uri)
                stream?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@execute
            main.post {
                if (isDestroyed) return@post
                imageView.setImageBitmap(bitmap)
                veil.animate().alpha(0f).setDuration(900).start()
                showSceneTitle(item.title)
                main.removeCallbacks(nextImage)
                main.postDelayed(nextImage, 5 * 60_000)
            }
        }
    }

    private companion object {
        const val IDLE_AFTER_MS = 60_000L
        const val KEEP_PLAYER_MS = 90_000L
    }
}
