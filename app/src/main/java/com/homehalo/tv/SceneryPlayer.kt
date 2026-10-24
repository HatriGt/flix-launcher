package com.homehalo.tv

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

/**
 * Plays the scenery clips with Android's own MediaPlayer. The decoding and network
 * buffering happen in the system media service rather than in HomeHalo, which keeps
 * the launcher small (no ExoPlayer in the APK) and its memory low. Behaves like the
 * old player from the outside: a shuffled playlist that loops, skips broken clips,
 * reports the first frame and each new clip.
 */
class SceneryPlayer(
    private val view: SurfaceView,
    private val clips: List<AerialSource.Media>,
    var shuffle: Boolean,
    private val onFirstFrame: () -> Unit,
    private val onClip: (title: String?, auto: Boolean) -> Unit,
) : SurfaceHolder.Callback {
    private var mp: MediaPlayer? = null
    private var order: List<Int> = emptyList()
    private var index = -1
    private var wantPlay = false
    private var prepared = false
    private var failuresInARow = 0

    init {
        view.holder.addCallback(this)
        if (view.holder.surface?.isValid == true) start()
    }

    fun play() {
        wantPlay = true
        if (prepared) mp?.start()
    }

    fun pause() {
        wantPlay = false
        if (prepared) runCatching { mp?.pause() }
    }

    fun release() {
        view.holder.removeCallback(this)
        mp?.release()
        mp = null
        prepared = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (mp == null) start() else mp?.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = fitCrop()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching { mp?.setDisplay(null) }
    }

    private fun start() {
        order = clips.indices.toList().let { if (shuffle) it.shuffled() else it }
        next(auto = false)
    }

    private fun next(auto: Boolean) {
        if (clips.isEmpty() || failuresInARow > clips.size) return
        index = (index + 1) % order.size
        if (index == 0 && shuffle && auto) order = order.shuffled()
        val clip = clips[order[index]]
        prepared = false
        val player = mp ?: MediaPlayer().also { mp = it }
        runCatching {
            player.reset()
            player.setDataSource(clip.uri)
            player.setDisplay(view.holder)
            player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                prepared = true
                failuresInARow = 0
                fitCrop()
                if (wantPlay) it.start()
            }
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame()
                false
            }
            player.setOnCompletionListener { next(auto = true) }
            player.setOnErrorListener { _, _, _ ->
                // Skip a clip that won't load rather than leaving the background frozen.
                failuresInARow++
                view.post { next(auto = false) }
                true
            }
            player.prepareAsync()
            onClip(clip.title, auto)
        }.onFailure {
            failuresInARow++
            view.post { next(auto = false) }
        }
    }

    /** Zooms the video to fill the screen without letterboxing, like the old "zoom" mode. */
    private fun fitCrop() {
        val p = mp ?: return
        val vw = runCatching { p.videoWidth }.getOrDefault(0)
        val vh = runCatching { p.videoHeight }.getOrDefault(0)
        val parent = view.parent as? View ?: return
        if (vw == 0 || vh == 0 || parent.width == 0) return
        val scale = maxOf(parent.width / vw.toFloat(), parent.height / vh.toFloat())
        val w = (vw * scale).toInt()
        val h = (vh * scale).toInt()
        val lp = view.layoutParams as android.widget.FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        lp.gravity = android.view.Gravity.CENTER
        view.layoutParams = lp
    }
}
