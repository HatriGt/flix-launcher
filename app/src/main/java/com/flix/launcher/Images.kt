package com.flix.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Small image loader for posters and backdrops: memory cache, disk cache and
 * downscaled decoding, so a few rows of posters stay within a few MB.
 */
object Images {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val memory = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /**
     * Full-screen images (billboard, wallpaper) kept apart: one of them is several MB and
     * would push dozens of posters out of [memory]. Two fit the billboard's current and next.
     */
    private val large = object : LruCache<String, Bitmap>(2) {}
    private const val LARGE_PX = 1000

    private fun cached(key: String, widthPx: Int): Bitmap? = if (widthPx >= LARGE_PX) large.get(key) else memory.get(key)

    private var dir: File? = null
    /** Callbacks waiting on a decode already under way; main thread only. */
    private val inFlight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Drops decoded images; posters reload quickly from the disk cache. */
    fun trim() { memory.evictAll(); large.evictAll() }

    fun init(context: Context) {
        if (dir != null) return
        val folder = File(context.cacheDir, "img").apply { mkdirs() }
        dir = folder
        // Keep the disk cache to roughly the newest 80 MB.
        pool.execute {
            var total = 0L
            folder.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
                total += file.length()
                if (total > 80L * 1024 * 1024) file.delete()
            }
        }
    }

    /** Loads [url] into [view] at about [widthPx] wide, fading it in; a recycled view never shows a stale image. */
    fun into(view: ImageView, url: String?, widthPx: Int) {
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        cached(key(url, widthPx), widthPx)?.let {
            view.alpha = 1f
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        get(url, widthPx) { bitmap ->
            if (view.tag != url || bitmap == null) return@get
            view.setImageBitmap(bitmap)
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(220).start()
        }
    }

    /** Fetches a bitmap and hands it back on the main thread (null on failure). */
    fun get(url: String, widthPx: Int, onReady: (Bitmap?) -> Unit) {
        val key = key(url, widthPx)
        cached(key, widthPx)?.let { return onReady(it) }
        // A poster scrolled past and back (or asked for by two views) shares one decode.
        inFlight[key]?.let { it += onReady; return }
        inFlight[key] = mutableListOf(onReady)
        pool.execute {
            val bitmap = runCatching { decode(bytes(url), widthPx) }.getOrNull()
            // A cached file that won't decode is broken (or not an image): fetch it afresh next time.
            if (bitmap == null) cacheFile(url)?.delete()
            if (bitmap != null) { if (widthPx >= LARGE_PX) large.put(key, bitmap) else memory.put(key, bitmap) }
            main.post { inFlight.remove(key)?.forEach { it(bitmap) } }
        }
    }

    private val accents = LruCache<String, Int>(200)

    /**
     * The image's most vivid colour, for the ambient glow. Decoded separately at a tiny
     * size into ordinary memory, since the hardware bitmaps used for display can't be read.
     */
    fun accent(url: String, onReady: (Int?) -> Unit) {
        accents.get(url)?.let { return onReady(it) }
        pool.execute {
            val color = runCatching {
                val data = bytes(url)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 48) sample *= 2
                val small = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                val bmp = Bitmap.createScaledBitmap(small, 24, 14, true)
                // Average the pixels weighted by how colourful and bright they are, so a
                // poster's dominant hue wins over its blacks, whites and greys.
                var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
                val hsv = FloatArray(3)
                for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                    val c = bmp.getPixel(x, y)
                    android.graphics.Color.colorToHSV(c, hsv)
                    val w = (hsv[1] * hsv[1] * hsv[2]).toDouble() + 0.001
                    r += android.graphics.Color.red(c) * w; g += android.graphics.Color.green(c) * w; b += android.graphics.Color.blue(c) * w
                    total += w
                }
                if (bmp !== small) bmp.recycle()
                small.recycle()
                val avg = android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
                // Keep the hue, settle it at a rich, mid brightness so it glows without glaring.
                android.graphics.Color.colorToHSV(avg, hsv)
                android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.35f, 0.8f), 0.6f))
            }.getOrNull()
            if (color != null) accents.put(url, color)
            main.post { onReady(color) }
        }
    }

    private fun bytes(url: String): ByteArray {
        val file = cacheFile(url)
        if (file != null && file.exists()) return file.readBytes()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        val data = try {
            conn.inputStream.use { SafeIo.readBytes(it, SafeIo.MAX_IMAGE) }
        } finally {
            conn.disconnect()
        }
        // Whole or not at all; a failed save only costs a re-download.
        file?.let { runCatching { SafeIo.write(it, data) } }
        return data
    }

    private fun cacheFile(url: String) = dir?.let { File(it, sha1(url)) }

    private fun decode(data: ByteArray, widthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
            exactWidth(bounds.outWidth, widthPx)
            // Hardware bitmaps live only on the GPU: no copy in the app's memory and no
            // upload to the GPU on first draw, which is where the poster rows stuttered.
            inPreferredConfig = Bitmap.Config.HARDWARE
        })
    }

    /**
     * Decodes at the width actually drawn, not the next power of two above it: a 342 px
     * poster shown 200 px wide was kept whole, nearly 3x the graphics memory it needs.
     * Power-of-two sampling first (cheap), then the decoder's own density scaling for the rest.
     */
    internal fun BitmapFactory.Options.exactWidth(srcWidth: Int, widthPx: Int) {
        var sample = 1
        while (srcWidth / (sample * 2) >= widthPx) sample *= 2
        inSampleSize = sample
        val sampled = srcWidth / sample
        if (widthPx in 1 until sampled) {
            inScaled = true
            inDensity = sampled
            inTargetDensity = widthPx
        }
    }

    private fun key(url: String, width: Int) = "$width|$url"

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
