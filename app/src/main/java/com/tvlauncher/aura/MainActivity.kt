package com.tvlauncher.aura

import android.app.Activity
import android.app.AlertDialog
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
import android.view.View
import android.widget.ImageView
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
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var repo: AppRepository

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weather: TextView
    private lateinit var focusedLabel: TextView
    private lateinit var sceneTitle: TextView
    private lateinit var videoView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var favoritesAdapter: AppAdapter
    private lateinit var appsAdapter: AppAdapter
    private lateinit var favoritesList: RecyclerView

    private var player: ExoPlayer? = null
    private var media: List<AerialSource.Media> = emptyList()
    private var imageIndex = 0
    private var allApps: List<AppEntry> = emptyList()
    private var lastWeatherAt = 0L
    private var initialFocusDone = false

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = DateFormat.format(if (DateFormat.is24HourFormat(this@MainActivity)) "HH:mm" else "h:mm", now)
            date.text = DateFormat.format("EEEE, d MMMM", now)
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    private val nextImage = Runnable { showImage(imageIndex + 1) }

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
        weather = findViewById(R.id.weather)
        focusedLabel = findViewById(R.id.focused_label)
        sceneTitle = findViewById(R.id.scene_title)
        videoView = findViewById(R.id.wallpaper_video)
        imageView = findViewById(R.id.wallpaper_image)

        val dp = resources.displayMetrics.density
        favoritesAdapter = AppAdapter((160 * dp).roundToInt(), (90 * dp).roundToInt(), ::onAppFocused, ::launch, ::showAppMenu)
        appsAdapter = AppAdapter((112 * dp).roundToInt(), (63 * dp).roundToInt(), ::onAppFocused, ::launch, ::showAppMenu)
        favoritesList = setUpRow(R.id.favorites, favoritesAdapter)
        setUpRow(R.id.apps, appsAdapter)

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
        }

    override fun onStart() {
        super.onStart()
        main.post(tick)
        refreshWeather()
        startWallpaper()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        main.removeCallbacks(nextImage)
        player?.release()
        player = null
        videoView.player = null
        videoView.alpha = 0f
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Home screen has nowhere to go back to")
    override fun onBackPressed() {
        favoritesList.getChildAt(0)?.requestFocus()
    }

    // ---- Apps ----

    private fun loadApps() {
        io.execute {
            val apps = repo.loadApps()
            main.post {
                allApps = apps
                bindRows()
                if (!initialFocusDone) {
                    initialFocusDone = true
                    favoritesList.post { favoritesList.getChildAt(0)?.requestFocus() }
                }
            }
        }
    }

    private fun bindRows() {
        val byPackage = allApps.associateBy { it.packageName }
        val favorites = repo.favorites.mapNotNull { byPackage[it] }
        favoritesAdapter.items = favorites
        appsAdapter.items = allApps.filter { it.packageName !in repo.favorites }
    }

    private fun onAppFocused(app: AppEntry) {
        focusedLabel.text = app.label
    }

    private fun launch(app: AppEntry) {
        open(app.launchIntent)
    }

    private fun showAppMenu(app: AppEntry) {
        val isFavorite = app.packageName in repo.favorites
        val actions = buildList {
            add((if (isFavorite) "Remove from favourites" else "Add to favourites") to { repo.toggleFavorite(app.packageName) })
            if (isFavorite) {
                add("Move left" to { repo.moveFavorite(app.packageName, -1) })
                add("Move right" to { repo.moveFavorite(app.packageName, 1) })
            }
            add("App info" to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) })
            add("Uninstall" to { open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) })
        }
        AlertDialog.Builder(this, R.style.Theme_Aura_Dialog)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                bindRows()
            }
            .show()
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
        io.execute {
            val now = Weather.fetch() ?: return@execute
            main.post {
                lastWeatherAt = System.currentTimeMillis()
                weather.text = "${now.icon}  ${now.temperature}  ·  ${now.place}"
            }
        }
    }

    // ---- Wallpaper ----

    private fun loadWallpapers() {
        AerialSource.fetch(this) { items ->
            main.post {
                media = items
                if (!isFinishing) startWallpaper()
            }
        }
    }

    private fun startWallpaper() {
        val videos = media.filter { it.isVideo }
        if (videos.isNotEmpty()) startVideos(videos) else if (media.isNotEmpty()) showImage(imageIndex)
    }

    private fun startVideos(videos: List<AerialSource.Media>) {
        if (player != null) return
        val exo = ExoPlayer.Builder(this).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItems(videos.map { MediaItem.Builder().setUri(it.uri).setTag(it.title).build() })
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    videoView.animate().alpha(1f).setDuration(700).start()
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Skip a clip that won't load rather than leaving the background frozen.
                    if (mediaItemCount > 1) {
                        seekToNextMediaItem()
                        prepare()
                    }
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    sceneTitle.text = item?.localConfiguration?.tag as? String ?: ""
                }
            })
            prepare()
            playWhenReady = true
        }
        sceneTitle.text = videos.first().title ?: ""
        videoView.player = exo
        player = exo
    }

    private fun showImage(index: Int) {
        val images = media.filter { !it.isVideo }
        if (images.isEmpty()) return
        imageIndex = index % images.size
        val item = images[imageIndex]
        io.execute {
            val bitmap = runCatching {
                val uri = Uri.parse(item.uri)
                val stream = if (uri.scheme == "http" || uri.scheme == "https") URL(item.uri).openStream()
                else contentResolver.openInputStream(uri)
                stream?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@execute
            main.post {
                imageView.setImageBitmap(bitmap)
                sceneTitle.text = item.title ?: ""
                main.removeCallbacks(nextImage)
                main.postDelayed(nextImage, 5 * 60_000)
            }
        }
    }
}
