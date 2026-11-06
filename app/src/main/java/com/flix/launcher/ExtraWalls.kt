package com.flix.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wallpaper sources beyond Bing and the aerial films, all free and keyless:
 *  - Sci-fi and gaming photos: Wallhaven's community toplist (safe-for-work, general art only).
 *  - Sci-fi video: ESA's Hubble and Webb pan, zoom and fly-through clips, from their video feeds.
 *  - Gaming video: Steam's own store trailers for the current top sellers and new releases.
 *  - Movie photos: the backdrops of the films already loaded in your rows ([MovieWalls]).
 * Each list is fetched once and kept for a week; the images and clips themselves stream.
 */
object ExtraWalls {
    private const val WEEK = 7 * 24 * 3600_000L

    /** Photo kinds this file supplies, and video kinds (without the "v_" prefix). */
    val PHOTO_KINDS = setOf("scifi", "gaming", "movies")
    val VIDEO_KINDS = setOf("scifi", "gaming")

    class Item(val url: String, val title: String)

    // ---- Photos ----

    private val WALLHAVEN = mapOf(
        "scifi" to listOf("science fiction", "spaceship", "cyberpunk", "space station", "planet"),
        "gaming" to listOf("video games"),
    )

    fun photos(context: Context, kind: String): List<Item> {
        if (kind == "movies") return MovieWalls.items()
        val queries = WALLHAVEN[kind] ?: return emptyList()
        return cached(context, "extra_photos_$kind") {
            queries.flatMap { q -> (1..(if (queries.size == 1) 4 else 2)).flatMap { page -> wallhaven(q, page) } }.distinctBy { it.url }
        }
    }

    /** One page of Wallhaven's toplist for [query]: 16:9, at least 1080p, safe-for-work, general art. */
    private fun wallhaven(query: String, page: Int): List<Item> = runCatching {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(get("https://wallhaven.cc/api/v1/search?q=$q&categories=100&purity=100&sorting=toplist" +
            "&topRange=1y&atleast=1920x1080&ratios=16x9&page=$page"))
        val data = json.getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it) }.mapNotNull { w ->
            val path = w.optString("path").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            // Very large originals (4K+ PNGs) cost too much to fetch on a TV; skip the heaviest.
            if (w.optLong("file_size") > 6_000_000) return@mapNotNull null
            Item(path, query.replaceFirstChar { it.uppercase() })
        }
    }.getOrDefault(emptyList())

    // ---- Video ----

    fun videos(context: Context, kind: String): List<Item> = when (kind) {
        "scifi" -> cached(context, "extra_videos_scifi") { esa("https://esahubble.org/videos/feed/", "https://cdn.esahubble.org") +
            esa("https://esawebb.org/videos/feed/", "https://cdn.esawebb.org") }
        "gaming" -> cached(context, "extra_videos_gaming_v2") { steam() }
        else -> emptyList()
    }

    /**
     * ESA's video feeds list each new clip by id; the 1080p file lives at a fixed address.
     * Only the visual ones are kept: pans, zooms, fly-throughs and explorations, not the
     * narrated episodes.
     */
    private fun esa(feed: String, cdn: String): List<Item> = runCatching {
        val xml = get(feed)
        Regex("<item><title>([^<]+)</title><link>https://[^/]+/videos/([a-z0-9]+)/</link>").findAll(xml).mapNotNull { m ->
            val title = m.groupValues[1].replace("&#39;", "'").replace("&amp;", "&")
            val id = m.groupValues[2]
            if (!VISUAL.containsMatchIn(title) || NARRATED.containsMatchIn(title)) return@mapNotNull null
            Item("$cdn/archives/videos/hd_1080p25_screen/$id.mp4", title.substringAfter(": ").ifBlank { title })
        }.toList()
    }.getOrDefault(emptyList())

    private val VISUAL = Regex("^(pan|zoom|transition) video|fly|explore|journey|time-lapse|animation", RegexOption.IGNORE_CASE)
    private val NARRATED = Regex("episode|hubblecast|webbcast|sparks|interview", RegexOption.IGNORE_CASE)

    /** Steam's top sellers and new releases, each with its first store trailer (HLS, which Android plays). */
    private fun steam(): List<Item> = runCatching {
        val front = JSONObject(get("https://store.steampowered.com/api/featuredcategories?cc=us&l=en"))
        val ids = listOf("top_sellers", "specials", "new_releases").flatMap { key ->
            front.optJSONObject(key)?.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optInt("id") } }.orEmpty()
        }.filter { it > 0 }.distinct().take(30)
        ids.mapNotNull { id ->
            val app = runCatching { JSONObject(get("https://store.steampowered.com/api/appdetails?appids=$id&cc=us&l=en")).getJSONObject("$id") }.getOrNull()
            if (app?.optBoolean("success") != true) return@mapNotNull null
            val data = app.getJSONObject("data")
            // Leave out games Steam marks as having adult content.
            val flags = data.optJSONObject("content_descriptors")?.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            if (flags.any { it == 1 || it == 3 || it == 4 }) return@mapNotNull null
            // Android's player can't play Steam's HLS streams (fragmented MP4), but Steam still
            // serves a plain MP4 for most trailers made before 2025: use the first that has one.
            val movies = data.optJSONArray("movies") ?: return@mapNotNull null
            val mp4 = (0 until minOf(movies.length(), 4)).asSequence()
                .map { "https://cdn.akamai.steamstatic.com/steam/apps/${movies.getJSONObject(it).optLong("id")}/movie_max.mp4" }
                .firstOrNull { exists(it) } ?: return@mapNotNull null
            Item(mp4, data.optString("name"))
        }
    }.getOrDefault(emptyList())

    // ---- Plumbing ----

    /** A list kept in [name].tsv for a week; refreshed by [fetch] when stale or missing. */
    private fun cached(context: Context, name: String, fetch: () -> List<Item>): List<Item> {
        val file = File(context.filesDir, "$name.tsv")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < WEEK
        if (fresh) read(file).takeIf { it.isNotEmpty() }?.let { return it }
        val got = runCatching(fetch).getOrDefault(emptyList())
        if (got.isNotEmpty()) SafeIo.write(file, got.joinToString("\n") { "${it.url}\t${it.title.replace('\t', ' ')}" })
        return got.ifEmpty { if (file.exists()) read(file) else emptyList() }
    }

    private fun read(file: File) = file.readLines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) null else Item(line.substring(0, tab), line.substring(tab + 1))
    }

    /** Whether [url] answers with content, asking for a single byte of it. */
    private fun exists(url: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.setRequestProperty("Range", "bytes=0-0")
        try { conn.responseCode in 200..299 } finally { conn.disconnect() }
    }.getOrDefault(false)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "FlixLauncher/1.0 (Android TV)")
        return try { SafeIo.readText(conn) } finally { conn.disconnect() }
    }
}

/**
 * The backdrops of films already in the home screen's rows, kept as they load, so the
 * "Your films" wallpaper is made of what you actually watch, with no extra fetching.
 */
object MovieWalls {
    private val seen = LinkedHashMap<String, ExtraWalls.Item>()

    fun offer(titles: List<Catalog.Title>) {
        synchronized(seen) {
            for (t in titles) {
                val art = t.backdrop ?: continue
                if (seen.size > 300) break
                seen.putIfAbsent(art, ExtraWalls.Item(art, listOfNotNull(t.name, t.year).joinToString(" · ")))
            }
        }
    }

    fun items(): List<ExtraWalls.Item> = synchronized(seen) { seen.values.toList() }
}
