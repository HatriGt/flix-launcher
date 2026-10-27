package com.flix.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * An endless supply of nature (or city) wallpapers from the Bing photo-of-the-day archive
 * (over 6,000 professional photos since 2010, a new one each day), filtered to
 * landscapes. Only a small list of addresses is kept on disk; each photo is downloaded
 * when it's needed, decoded straight to the GPU and dropped once the next one shows,
 * so nothing piles up on the TV. One photo is always fetched ahead, so changes are instant.
 */
object WallpaperFeed {
    private const val ARCHIVE = "https://bing.npanuhin.me/US/en.json"
    private const val WEEK = 7 * 24 * 3600_000L

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val NATURE = Regex("national park|mountain|lake|falls|waterfall|forest|valley|glacier|fjord|river|canyon|desert|dune|beach|coast|island|cliff|meadow|aurora|northern lights|volcano|lagoon|bay|reef|alps|peak|wilderness|nature reserve|gorge|sunset|sunrise|hills", RegexOption.IGNORE_CASE)
    private val BUILT_UP = Regex("city|cathedral|church|library|museum|palace|castle|temple|street|bridge|tower|skyline|downtown|market|festival|station", RegexOption.IGNORE_CASE)

    private class Entry(val url: String, val title: String)

    private val entries = HashMap<String, List<Entry>>()
    private var ready: Pair<Bitmap, String>? = null
    private var readyKind: String? = null

    /**
     * Hands back the next photo of [kind] ("nature" or "cities") and its place name, or null
     * when offline (the caller falls back to the built-in set).
     */
    fun next(context: Context, widthPx: Int, kind: String, onReady: (Bitmap?, String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val photo = ready?.takeIf { readyKind == kind }.also { ready = null } ?: fetchOne(app, widthPx, kind)
            main.post { onReady(photo?.first, photo?.second) }
            // Get the one after this ready while it's on screen.
            if (photo != null) { ready = fetchOne(app, widthPx, kind); readyKind = kind }
        }
    }

    private fun fetchOne(context: Context, widthPx: Int, kind: String): Pair<Bitmap, String>? {
        val list = catalog(context, kind).ifEmpty { return null }
        repeat(3) {
            val e = list.random()
            val bmp = runCatching { decode(download(e.url), widthPx) }.getOrNull()
            if (bmp != null) return bmp to e.title
        }
        return null
    }

    private fun fileOf(context: Context, kind: String) = File(context.filesDir, "${kind}_walls.tsv")

    /** The filtered list of photos of one kind, refreshed weekly so new days' photos join in. */
    private fun catalog(context: Context, kind: String): List<Entry> {
        val file = fileOf(context, kind)
        if (entries[kind].isNullOrEmpty() && file.exists()) entries[kind] = read(file)
        if (entries[kind].isNullOrEmpty() || System.currentTimeMillis() - file.lastModified() > WEEK) {
            runCatching { refresh(context) }.onFailure { android.util.Log.w("Flix", "Wallpaper list refresh failed", it) }
        }
        return entries[kind].orEmpty()
    }

    private fun read(file: File) = file.readLines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) null else Entry(line.substring(0, tab), line.substring(tab + 1))
    }

    /** Streams the 5 MB archive rather than loading it whole, sorting landscapes from cities. */
    private fun refresh(context: Context) {
        val conn = URL(ARCHIVE).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        val found = ArrayList<Entry>(2500)
        val cities = ArrayList<Entry>(1500)
        try {
            JsonReader(conn.inputStream.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var title: String? = null; var bing: String? = null; var mirror: String? = null
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        if (r.peek() == android.util.JsonToken.NULL) { r.skipValue(); continue }
                        when (name) {
                            "title" -> title = r.nextString()
                            "bing_url" -> bing = r.nextString()
                            "url" -> mirror = r.nextString()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    val t = title ?: continue
                    // Bing's own 1920 px copy (~300 KB) when it has one; the archive's copy otherwise.
                    val url = bing?.replace("_UHD.jpg", "_1920x1080.jpg") ?: mirror ?: continue
                    val entry = Entry(url, t.replace('\t', ' '))
                    if (BUILT_UP.containsMatchIn(t)) cities += entry
                    else if (NATURE.containsMatchIn(t)) found += entry
                }
                r.endArray()
            }
        } finally {
            conn.disconnect()
        }
        if (found.size < 50) return
        for ((kind, list) in listOf("nature" to found, "cities" to cities)) {
            SafeIo.write(fileOf(context, kind), list.joinToString("\n") { "${it.url}\t${it.title}" })
            entries[kind] = list
        }
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        // Capped: a broken mirror sending an endless reply would otherwise exhaust memory.
        return try { conn.inputStream.use { SafeIo.readBytes(it, SafeIo.MAX_IMAGE) } } finally { conn.disconnect() }
    }

    private fun decode(data: ByteArray, widthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth < bounds.outHeight) return null // portrait shots don't fill a TV
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
            with(Images) { exactWidth(bounds.outWidth, widthPx) }
            inPreferredConfig = Bitmap.Config.HARDWARE
        })
    }
}
