package com.flix.launcher

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Runs the trailer's WebView in its own process (":trailer") and hands the home
 * screen a surface to show. When the trailer stops, the home screen unbinds and this
 * process exits, taking Chromium and its renderer with it, so their ~150 MB is
 * returned to the TV straight away rather than lingering for reuse.
 */
class TrailerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var host: SurfaceControlViewHost? = null
    private var web: WebView? = null
    private var client: Messenger? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_PLAY -> play(msg)
            MSG_RESIZE -> host?.relayout(msg.arg1, msg.arg2)
            MSG_SOUND -> web?.evaluateJavascript("if(window.player){player.unMute();player.setVolume(100)}", null)
        }
        true
    })

    override fun onBind(intent: Intent): IBinder {
        // Bound while the home screen still counts down: start Chromium and fetch YouTube's
        // player script now, so by play time both are warm.
        main.post { warmUp() }
        return messenger.binder
    }

    private fun webView(): WebView = web ?: WebView(this).also { w ->
        web = w
        w.setBackgroundColor(Color.TRANSPARENT)
        w.settings.javaScriptEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.domStorageEnabled = true
        w.webChromeClient = WebChromeClient()
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun state(playing: Boolean) {
                runCatching { client?.send(Message.obtain(null, MSG_STATE, if (playing) 1 else 0, 0)) }
            }
        }, "Flix")
    }

    private fun warmUp() {
        if (web != null) return
        webView().loadDataWithBaseURL("https://www.youtube-nocookie.com",
            "<html><body><script src=\"https://www.youtube.com/iframe_api\"></script></body></html>", "text/html", "utf-8", null)
    }

    override fun onUnbind(intent: Intent): Boolean {
        shutDown()
        return false
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    /** Releases everything and ends this process, so Chromium's memory goes back to the TV. */
    private fun shutDown() {
        web?.destroy()
        web = null
        host?.release()
        host = null
        main.post { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun play(msg: Message) {
        val data: Bundle = msg.data
        client = msg.replyTo
        val ytId = data.getString("ytId") ?: return
        val display = getSystemService(DisplayManager::class.java).getDisplay(data.getInt("displayId"))
        val view = webView()
        if (host == null) {
            host = SurfaceControlViewHost(this, display, data.getBinder("hostToken")).also {
                it.setView(view, data.getInt("w"), data.getInt("h"))
                val reply = Message.obtain(null, MSG_SURFACE)
                reply.data = Bundle().apply { putParcelable("surface", it.surfacePackage) }
                runCatching { client?.send(reply) }
            }
        }
        view.loadDataWithBaseURL("https://www.youtube-nocookie.com", page(ytId, data.getBoolean("sound")), "text/html", "utf-8", null)
    }

    private fun page(ytId: String, sound: Boolean) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
        #p{position:absolute;top:50%;left:50%;width:177.78vh;height:100vh;min-width:100vw;min-height:56.25vw;transform:translate(-50%,-50%) scale(1.32)}</style></head>
        <body><div id="p"></div>
        <script>
        // Defined before YouTube's script loads: from cache it can be ready at once, and a
        // callback defined after that point is never called (the trailer then never played).
        var made = false;
        function make(){
          if (made || !(window.YT && YT.Player)) return; made = true;
          window.player = new YT.Player('p',{videoId:'$ytId',host:'https://www.youtube-nocookie.com',
            playerVars:{autoplay:1,mute:${if (sound) 0 else 1},controls:0,disablekb:1,fs:0,modestbranding:1,rel:0,iv_load_policy:3,playsinline:1},
            events:{onReady:function(e){e.target.playVideo()},
              onStateChange:function(e){ if(e.data===1) Flix.state(true); if(e.data===0) Flix.state(false) },
              onError:function(){ Flix.state(false) }}});
        }
        window.onYouTubeIframeAPIReady = make;
        </script>
        <script src="https://www.youtube.com/iframe_api" onload="setTimeout(make, 300)"></script>
        </script></body></html>
    """.trimIndent()

    companion object {
        const val MSG_PLAY = 1
        const val MSG_RESIZE = 2
        const val MSG_SOUND = 3
        const val MSG_SURFACE = 10
        const val MSG_STATE = 11
    }
}
