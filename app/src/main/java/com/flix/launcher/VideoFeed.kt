package com.flix.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Free aerial screensaver videos, no app needed: Apple TV's aerials (landscapes,
 * cities, oceans and Earth from the ISS), Amazon Fire TV's scenes and the Aerial
 * community's flyovers, about 270 clips in all. Their public lists come from the
 * Aerial Views project; only a small "scene, address, place" list is kept on disk,
 * refreshed weekly, and the clips themselves stream when played.
 */
object VideoFeed {
    private const val LISTS = "https://raw.githubusercontent.com/theothernt/AerialViews/master/app/src/main/res/raw/"
    private const val WEEK = 7 * 24 * 3600_000L

    /** Each list and the copy of its clips that every TV can decode (1080p H.264 where offered). */
    private val SOURCES = listOf("tvos26" to "url-1080-H264", "comm1" to "url-1080-H264", "fireos8" to "url-1080-SDR")

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Clip(val kind: String, val url: String, val title: String)

    private var clips: List<Clip> = emptyList()

    /**
     * The clips for one wallpaper choice ("all", "nature", "sea", "city", "space", "art"),
     * or an empty list when offline.
     */
    fun clips(context: Context, kind: String, onReady: (List<AerialSource.Media>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val found = catalog(app).filter { kind == "all" || it.kind == kind }
                .map { AerialSource.Media(it.url, isVideo = true, title = it.title) }
            main.post { onReady(found) }
        }
    }

    private fun catalog(context: Context): List<Clip> {
        val file = File(context.filesDir, "aerial_videos_v2.tsv")
        if (clips.isEmpty() && file.exists()) clips = read(file)
        if (clips.isEmpty() || System.currentTimeMillis() - file.lastModified() > WEEK) {
            runCatching { refresh(file) }.onFailure { android.util.Log.w("Flix", "Video list refresh failed", it) }
        }
        return clips
    }

    private fun read(file: File) = file.readLines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) null else Clip(parts[0], parts[1], parts[2])
    }

    private fun refresh(file: File) {
        val found = ArrayList<Clip>(300)
        for ((name, key) in SOURCES) {
            val assets = runCatching { JSONObject(download("$LISTS$name.json")).getJSONArray("assets") }.getOrNull() ?: continue
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                // Apple signs its aerial server with its own root certificate, which Android
                // doesn't trust; the same files are served over plain HTTP.
                val url = a.optString(key).replace("https://sylvan.apple.com/", "http://sylvan.apple.com/")
                if (url.isEmpty()) continue
                val kind = when (a.optString("scene")) {
                    "nature", "countryside" -> "nature"
                    "sea", "beach" -> "sea"
                    "city" -> "city"
                    "space" -> "space"
                    "patterns", "fire" -> "art"
                    else -> continue
                }
                val title = a.optString("accessibilityLabel").ifEmpty { a.optString("title") }.replace('\t', ' ')
                found += Clip(kind, url, title)
            }
        }
        if (found.size < 20) return
        file.writeText(found.joinToString("\n") { "${it.kind}\t${it.url}\t${it.title}" })
        clips = found
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }
}
