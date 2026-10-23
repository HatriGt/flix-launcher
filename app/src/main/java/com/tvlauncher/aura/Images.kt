package com.tvlauncher.aura

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
    private var dir: File? = null

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
        memory.get(key(url, widthPx))?.let {
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
        memory.get(key)?.let { return onReady(it) }
        pool.execute {
            val bitmap = runCatching { decode(bytes(url), widthPx) }.getOrNull()
            if (bitmap != null) memory.put(key, bitmap)
            main.post { onReady(bitmap) }
        }
    }

    private fun bytes(url: String): ByteArray {
        val file = dir?.let { File(it, sha1(url)) }
        if (file != null && file.exists()) return file.readBytes()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        val data = try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        file?.writeBytes(data)
        return data
    }

    private fun decode(data: ByteArray, widthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun key(url: String, width: Int) = "$width|$url"

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
