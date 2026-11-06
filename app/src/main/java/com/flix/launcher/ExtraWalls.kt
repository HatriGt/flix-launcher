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
 *  - Gaming video: Steam store trailers for a fixed list of well-known games.
 *  - Film photos: official backdrops of the most popular films by genre, from Cinemeta.
 *  - Film video: the Blender Foundation's open movies (Sintel, Tears of Steel, Elephants Dream).
 * Each list is fetched once and kept for a week; the images and clips themselves stream.
 */
object ExtraWalls {
    private const val WEEK = 7 * 24 * 3600_000L

    /** Photo kinds this file supplies, and video kinds (without the "v_" prefix). */
    val PHOTO_KINDS by lazy { setOf("scifi", "gaming") + MOVIE_GENRES.keys }
    val VIDEO_KINDS = setOf("scifi", "gaming", "films")

    class Item(val url: String, val title: String)

    // ---- Photos ----

    private val WALLHAVEN = mapOf(
        "scifi" to listOf("science fiction", "spaceship", "cyberpunk", "space station", "planet"),
        "gaming" to listOf("video games"),
    )

    /** Film wallpapers: Cinemeta's most popular films in each genre, as their official backdrops. */
    val MOVIE_GENRES = linkedMapOf(
        "films" to null, "films_scifi" to "Sci-Fi", "films_action" to "Action",
        "films_animation" to "Animation", "films_fantasy" to "Fantasy", "films_horror" to "Horror",
    )

    private fun cinemeta(genre: String?): List<Item> = runCatching {
        val base = "https://v3-cinemeta.strem.io/catalog/movie/" + if (genre == null) "top" else "imdbRating"
        val g = genre?.let { "genre=" + java.net.URLEncoder.encode(it, "UTF-8") }
        // Two pages of the catalogue, each film's large backdrop with its name and year.
        listOf(g, listOfNotNull(g, "skip=50").joinToString("&")).flatMap { extra ->
            val metas = JSONObject(get(base + (if (extra.isNullOrEmpty()) "" else "/$extra") + ".json")).getJSONArray("metas")
            (0 until metas.length()).map { metas.getJSONObject(it) }.mapNotNull { m ->
                val id = m.optString("imdb_id").ifBlank { m.optString("id") }.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                Item("https://images.metahub.space/background/large/$id/img",
                    listOfNotNull(m.optString("name").ifBlank { null }, m.optString("releaseInfo").ifBlank { null }).joinToString(" · "))
            }
        }.distinctBy { it.url }
    }.getOrDefault(emptyList())

    fun photos(context: Context, kind: String): List<Item> {
        if (kind in MOVIE_GENRES) return cached(context, "extra_photos_$kind") { cinemeta(MOVIE_GENRES[kind]) }
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
        "gaming" -> GAME_TRAILERS.shuffled()
        "films" -> OPEN_MOVIES.shuffled()
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

    /**
     * Trailers for well-known games, as Steam's plain MP4 files. A fixed list rather than
     * Steam's live charts: those mix in hardware and brand-new games whose trailers only
     * exist as HLS streams Android can't play, and building it took a minute on the TV.
     * Games Steam flags for adult content are left out.
     */
    private val GAME_TRAILERS = listOf(
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256889452/movie_max.mp4", "ELDEN RING"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256767979/movie_max.mp4", "Red Dead Redemption 2"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256930503/movie_max.mp4", "Hogwarts Legacy"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256864004/movie_max.mp4", "God of War"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256899640/movie_max.mp4", "Horizon Zero Dawn Complete Edition"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256900369/movie_max.mp4", "Marvel’s Spider-Man Remastered"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257048125/movie_max.mp4", "Black Myth: Wukong"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256875134/movie_max.mp4", "Forza Horizon 5"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257182101/movie_max.mp4", "DOOM Eternal"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256878921/movie_max.mp4", "DEATH STRANDING DIRECTOR'S CUT"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256806899/movie_max.mp4", "Sekiro: Shadows Die Twice - GOTY Edition"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256998128/movie_max.mp4", "Resident Evil 4"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256936971/movie_max.mp4", "The Last of Us Part I"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257109739/movie_max.mp4", "Grand Theft Auto V Legacy"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256815967/movie_max.mp4", "Stardew Valley"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256801252/movie_max.mp4", "Hades"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256679401/movie_max.mp4", "Hollow Knight"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256911304/movie_max.mp4", "The Sims 4"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257035339/movie_max.mp4", "EA SPORTS FC 24"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256941288/movie_max.mp4", "Far Cry 6"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257050591/movie_max.mp4", "HITMAN World of Assassination"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257007287/movie_max.mp4", "Horizon Forbidden West Complete Edition"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256878893/movie_max.mp4", "FINAL FANTASY VII REMAKE INTERGRADE"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256915117/movie_max.mp4", "Marvel’s Spider-Man: Miles Morales"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256806061/movie_max.mp4", "Battlefield V"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/256873105/movie_max.mp4", "MONSTER HUNTER RISE"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257205917/movie_max.mp4", "Monster Hunter Wilds"),
        Item("https://cdn.akamai.steamstatic.com/steam/apps/257096714/movie_max.mp4", "Sid Meier's Civilization VII"),
    )

    /** The Blender Foundation's open movies, free to show anywhere. */
    private val OPEN_MOVIES = listOf(
        Item("https://download.blender.org/durian/movies/Sintel.2010.1080p.mkv", "Sintel"),
        Item("https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov", "Tears of Steel"),
        Item("https://download.blender.org/ED/elephantsdream-720-h264-st-aac.mov", "Elephants Dream"),
    )

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

