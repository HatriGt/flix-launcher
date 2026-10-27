package com.flix.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

/**
 * Plays a title's YouTube trailer behind the preview once a poster has held
 * focus for a few seconds, as Nuvio does. The player itself runs in a separate
 * process ([TrailerService]) and is shown here through a SurfaceView; stopping
 * the trailer unbinds it, which ends that process and frees Chromium's memory.
 */
class Trailer(private val context: Context, private val host: android.widget.FrameLayout, private val net: Executor) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Catalog.Title? = null
    private var playingId: String? = null
    private var surface: SurfaceView? = null
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    var enabled = true
    val isPlaying get() = playingId != null
    var sound = false
    /** How long a poster holds focus before its trailer plays (a setting). */
    var delayMs = 2000L
    /** When the trailer should start; the slow parts are got ready before then. */
    private var playAt = 0L
    private var focusedAt = 0L

    /** Called whenever the playing state changes, so the scenery video can pause. */
    var onPlaying: (Boolean) -> Unit = {}

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            TrailerService.MSG_SURFACE -> {
                @Suppress("DEPRECATION")
                val pkg = msg.data.getParcelable<SurfaceControlViewHost.SurfacePackage>("surface")
                if (pkg != null && playingId != null) surface?.setChildSurfacePackage(pkg)
            }
            TrailerService.MSG_STATE -> if (msg.arg1 == 1 && playingId != null && !revealed) {
                // It has been buffering muted and out of sight; show it once the poster has
                // held focus for the chosen time.
                revealed = true
                val wait = (playAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)
                main.postDelayed({ reveal() }, wait)
            } else if (msg.arg1 == 0) stop()
        }
        true
    })

    private var revealed = false

    private fun reveal() {
        if (playingId == null) return
        surface?.let { it.visibility = View.VISIBLE; it.alpha = 0f; it.animate().alpha(1f).setDuration(700).start() }
        if (sound) runCatching { service?.send(Message.obtain(null, TrailerService.MSG_SOUND)) }
        onPlaying(true)
    }

    /** Starts the countdown for [title]; moving focus elsewhere calls this again or [stop]. */
    fun focus(title: Catalog.Title) {
        if (playingId == title.id) return
        stop()
        // Embedding another process's surface needs Android 11+; older TVs keep the still backdrop.
        if (!enabled || android.os.Build.VERSION.SDK_INT < 30) return
        pending = title
        revealed = false
        focusedAt = android.os.SystemClock.uptimeMillis()
        playAt = android.os.SystemClock.uptimeMillis() + delayMs
        // Most of the wait used to come after the delay: looking up the trailer, starting the
        // player's process and Chromium, loading YouTube's player. Do all that during the
        // delay instead, once focus has settled on a poster (not for every poster flicked past).
        main.postDelayed({ if (pending?.id == title.id) start(title) }, minOf(PREPARE_AFTER_MS, delayMs))
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        pending = null
        if (playingId == null) return
        playingId = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        surface?.let { old ->
            surface = null
            old.animate().cancel()
            old.animate().alpha(0f).setDuration(250).withEndAction { host.removeView(old) }.start()
        }
        onPlaying(false)
    }

    private fun start(title: Catalog.Title) {
        net.execute {
            val ytId = trailerId(title)
            if (ytId == null) return@execute
            main.post {
                if (pending?.id != title.id) return@post
                playingId = title.id
                val sv = SurfaceView(context).apply {
                    visibility = View.INVISIBLE
                    // Above the backdrop but under the page, like the old WebView.
                    setZOrderMediaOverlay(false)
                }
                surface = sv
                host.addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1))
                sv.holder.setFixedSize(host.width / 2, host.height / 2)
                sv.post { bind(ytId, sv) }
            }
        }
    }

    private fun bind(ytId: String, sv: SurfaceView) {
        if (surface !== sv) return
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                val m = Messenger(binder)
                service = m
                val msg = Message.obtain(null, TrailerService.MSG_PLAY)
                msg.replyTo = replies
                msg.data = Bundle().apply {
                    putString("ytId", ytId)
                    putBoolean("sound", false) // unmuted when shown
                    putBinder("hostToken", sv.hostToken)
                    putInt("displayId", sv.display.displayId)
                    // Half size, scaled up by the display: YouTube then picks a ~540p stream,
                    // which starts much sooner on a small TV chip and still looks fine
                    // softened behind the page.
                    putInt("w", (sv.width / 2).coerceAtLeast(1))
                    putInt("h", (sv.height / 2).coerceAtLeast(1))
                }
                // Start buffering straight away, hidden and muted; [reveal] shows it on time.
                runCatching { m.send(msg) }
            }
            override fun onServiceDisconnected(name: ComponentName) { if (connection === this) stop() }
        }
        connection = conn
        context.bindService(Intent(context, TrailerService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    /** The title's YouTube trailer id from Cinemeta (or the addon it came from), cached for a week. */
    private fun trailerId(title: Catalog.Title): String? = runCatching {
        val file = File(context.cacheDir, "trailer_${title.id.hashCode().toUInt()}.txt")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L) {
            return file.readText().ifBlank { null }
        }
        val base = if (title.id.startsWith("tt")) "https://v3-cinemeta.strem.io" else title.addonBase ?: return null
        val conn = URL("$base/meta/${title.type}/${java.net.URLEncoder.encode(title.id, "UTF-8")}.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        val meta = try {
            JSONObject(conn.inputStream.bufferedReader().readText()).optJSONObject("meta")
        } finally {
            conn.disconnect()
        }
        val id = meta?.optJSONArray("trailerStreams")?.optJSONObject(0)?.optString("ytId")?.ifBlank { null }
            ?: meta?.optJSONArray("trailers")?.optJSONObject(0)?.optString("source")?.ifBlank { null }
        file.writeText(id.orEmpty())
        id
    }.getOrNull()

    fun release() = stop()

    private companion object {
        const val PREPARE_AFTER_MS = 500L
    }
}
