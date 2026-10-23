package com.tvlauncher.aura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

/**
 * Plays a title's YouTube trailer behind the preview once a poster has held
 * focus for a few seconds, as Nuvio does. The player sits under the hero
 * shading and fades in only when the video is actually playing.
 */
class Trailer(private val context: Context, private val view: WebView, private val net: Executor) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Catalog.Title? = null
    private var playingId: String? = null
    var enabled = true
    val isPlaying get() = playingId != null
    var sound = false

    /** Called whenever the playing state changes, so the scenery video can pause. */
    var onPlaying: (Boolean) -> Unit = {}

    init {
        setUp()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp() {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.alpha = 0f
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.settings.javaScriptEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.domStorageEnabled = true
        view.webChromeClient = WebChromeClient()
        view.addJavascriptInterface(object {
            @JavascriptInterface
            fun state(playing: Boolean) = main.post {
                if (playing && playingId != null) {
                    view.animate().alpha(1f).setDuration(900).start()
                    onPlaying(true)
                } else if (!playing) {
                    stop()
                }
            }
        }, "Aura")
    }

    /** Starts the countdown for [title]; moving focus elsewhere calls this again or [stop]. */
    fun focus(title: Catalog.Title) {
        if (playingId == title.id) return
        stop()
        if (!enabled) return
        pending = title
        main.postDelayed({ if (pending?.id == title.id) start(title) }, DELAY_MS)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        pending = null
        if (playingId == null) return
        playingId = null
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(300).withEndAction { view.loadUrl("about:blank") }.start()
        onPlaying(false)
    }

    private fun start(title: Catalog.Title) {
        net.execute {
            val ytId = trailerId(title) ?: return@execute
            main.post {
                if (pending?.id != title.id) return@post
                playingId = title.id
                view.loadDataWithBaseURL("https://www.youtube-nocookie.com", page(ytId), "text/html", "utf-8", null)
            }
        }
    }

    private fun page(ytId: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}
        #p{position:absolute;top:50%;left:50%;width:177.78vh;height:100vh;min-width:100vw;min-height:56.25vw;transform:translate(-50%,-50%) scale(1.32)}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        function onYouTubeIframeAPIReady(){
          new YT.Player('p',{videoId:'$ytId',host:'https://www.youtube-nocookie.com',
            playerVars:{autoplay:1,mute:${if (sound) 0 else 1},controls:0,disablekb:1,fs:0,modestbranding:1,rel:0,iv_load_policy:3,playsinline:1,start:4},
            events:{onReady:function(e){e.target.playVideo()},
              onStateChange:function(e){ if(e.data===1) Aura.state(true); if(e.data===0) Aura.state(false) },
              onError:function(){ Aura.state(false) }}});
        }
        </script></body></html>
    """.trimIndent()

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

    fun release() {
        stop()
        view.destroy()
    }

    private companion object {
        const val DELAY_MS = 3000L
    }
}
