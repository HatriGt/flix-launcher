package com.flix.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Movie and series suggestions from Cinemeta, the free catalogue Stremio and
 * Nuvio use themselves; ids are IMDb ids, which is what Nuvio's deep links take.
 * Responses are cached on disk for six hours, so the home screen fills instantly
 * and still works offline.
 */
object Catalog {
    data class Title(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val year: String?,
        val rating: String?,
        val runtime: String?,
        val genres: List<String>,
        val description: String?,
        /** Base URL of the addon the title came from; Nuvio needs it for ids that aren't IMDb ids. */
        val addonBase: String? = null,
        /** For Continue watching: how far through it the user is (0–1), and which episode. */
        val progress: Float? = null,
        val episodeLabel: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        /** For Continue watching: minutes left to watch, when Nuvio knows the length. */
        val minutesLeft: Int? = null,
    ) {
        /**
         * Opens Nuvio straight on the title's list of streams, the way Nuvio's own Google TV
         * Continue watching row does (launchMode "stream"). A film or a known episode goes to
         * its streams; a series with no episode chosen gets its page, to pick one. Older Nuvio
         * versions without that screen are opened with [nuvioUri] instead.
         */
        fun nuvioStreamIntent(): android.content.Intent {
            val videoId = when {
                type == "movie" -> id
                season != null && episode != null -> "$id:$season:$episode"
                else -> null
            }
            return android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setClassName(MainActivity.NUVIO, "com.nuvio.tv.MainActivity")
                .putExtra("launchMode", "stream")
                .putExtra("contentId", id)
                .putExtra("contentType", type)
                .putExtra("name", name)
                .apply {
                    videoId?.let { putExtra("videoId", it) }
                    poster?.let { putExtra("poster", it) }
                    backdrop?.let { putExtra("backdrop", it) }
                    logo?.let { putExtra("logo", it) }
                    val s = season
                    val e = episode
                    if (type != "movie" && s != null && e != null) { putExtra("season", s); putExtra("episode", e) }
                }
        }

        /** Opens the title's page in Nuvio, ready to pick a stream and play. */
        val nuvioUri: String
            get() {
                val base = "nuvio://detail/$type/${java.net.URLEncoder.encode(id, "UTF-8")}"
                return if (id.startsWith("tt") || addonBase == null) base
                else "$base?addonBaseUrl=${java.net.URLEncoder.encode(addonBase, "UTF-8")}"
            }
    }

    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** Cached titles if any (even stale), for an instant first paint. */
    fun cached(context: Context, source: Addons.Row, genre: String?): List<Title>? =
        cacheFile(context, source, genre).takeIf { it.exists() }?.let { runCatching { parse(it.readText(), source.base) }.getOrNull() }

    fun isFresh(context: Context, source: Addons.Row, genre: String?) =
        cacheFile(context, source, genre).let { it.exists() && System.currentTimeMillis() - it.lastModified() < MAX_AGE_MS }

    /** Downloads the catalogue, caches it and returns it; null when offline. */
    fun fetch(context: Context, source: Addons.Row, genre: String?): List<Title>? = runCatching {
        val extras = listOfNotNull(
            genre?.takeIf { source.supportsGenre }?.let { "genre=${URLEncoder.encode(it, "UTF-8").replace("+", "%20")}" },
            source.extra.ifBlank { null },
        ).joinToString("&")
        val path = "${source.base}/catalog/${source.type}/${URLEncoder.encode(source.catalogId, "UTF-8")}"
        val conn = URL(if (extras.isEmpty()) "$path.json" else "$path/$extras.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        val body = try {
            SafeIo.readText(conn)
        } finally {
            conn.disconnect()
        }
        val titles = parse(body, source.base)
        SafeIo.write(cacheFile(context, source, genre), body)
        pruneCache(context)
        titles
    }.getOrNull()

    /** JSON nulls come back from optString as the text "null"; treat those, and blanks, as missing. */
    private fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun parse(body: String, addonBase: String): List<Title> {
        val metas = JSONObject(body).getJSONArray("metas")
        return (0 until metas.length()).mapNotNull { i ->
            val m = metas.getJSONObject(i)
            val id = m.optString("imdb_id").ifBlank { m.optString("id") }.ifBlank { return@mapNotNull null }
            // Addons that skip artwork still have IMDb ids; metahub serves art for those.
            val art = if (id.startsWith("tt")) "https://images.metahub.space" else null
            val genres = m.optJSONArray("genres") ?: m.optJSONArray("genre")
            Title(
                id = id,
                type = m.optString("type", "movie"),
                name = m.optString("name"),
                poster = m.text("poster") ?: art?.let { "$it/poster/small/$id/img" },
                backdrop = m.text("background") ?: art?.let { "$it/background/medium/$id/img" },
                logo = m.text("logo") ?: art?.let { "$it/logo/medium/$id/img" },
                year = m.text("releaseInfo") ?: m.text("year"),
                rating = m.text("imdbRating"),
                runtime = m.text("runtime"),
                genres = genres?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty(),
                description = m.text("description"),
                addonBase = addonBase,
            )
        }
    }

    /** Searches Cinemeta's movies and series by name (no caching: results are per keystroke). */
    /**
     * Films and series matching [query]: first from the user's own addons that can search
     * (so Tamil, Telugu… addons find their own titles), then Cinemeta's worldwide list.
     * Every source is asked at once and the whole search is capped at a few seconds.
     */
    fun search(context: Context, query: String, onPartial: ((List<Title>) -> Unit)? = null): List<Title> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val cinemeta = "https://v3-cinemeta.strem.io"
        val sources = searchCatalogues(context) + listOf("movie", "series").map { Triple(cinemeta, it, "top") }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val futures = sources.map { (base, type, id) ->
                pool.submit<List<Title>> {
                    val conn = URL("$base/catalog/$type/$id/search=$q.json").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 7000
                    conn.instanceFollowRedirects = true
                    val body = try { SafeIo.readText(conn) } finally { conn.disconnect() }
                    parse(body, base).take(15)
                }
            }
            // The same film often comes from several addons: keep the first (the user's own addon).
            fun merged(): List<Title> {
                val seen = HashSet<String>()
                return futures.filter { it.isDone && !it.isCancelled }
                    .flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }
                    .filter { seen.add(it.id) && seen.add("${it.name.lowercase()}|${it.year?.take(4)}") }
            }
            // Hand over what has arrived every moment, so fast sources show while slow ones finish.
            val deadline = System.currentTimeMillis() + 9000
            var shown = -1
            while (futures.any { !it.isDone } && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                val done = futures.count { it.isDone }
                if (onPartial != null && done != shown) { shown = done; merged().takeIf { it.isNotEmpty() }?.let(onPartial) }
            }
            futures.forEach { if (!it.isDone) it.cancel(true) }
            return merged()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Fetches the addons' manifests ahead of the first search, so it isn't slowed by them. */
    fun warmSearch(context: Context) { runCatching { searchCatalogues(context) } }

    /** Searchable catalogues ("search" extra) of the addons behind the user's rows, as (base, type, id). */
    private fun searchCatalogues(context: Context): List<Triple<String, String, String>> =
        Addons.rows(context).map { it.manifest }.distinct()
            .filter { it.startsWith("https://") && !it.contains("v3-cinemeta.strem.io") }
            .parallelStream().map { manifest ->
                val file = File(context.cacheDir, "manifest_${hashName(manifest)}.json")
                val text = file.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < 24 * 3600_000L }
                    ?.let { runCatching { it.readText() }.getOrNull() }
                    ?: runCatching {
                        val conn = URL(manifest).openConnection() as HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 5000
                        val body = try { SafeIo.readText(conn) } finally { conn.disconnect() }
                        SafeIo.write(file, body); body
                    }.getOrNull() ?: return@map emptyList()
                val catalogs = runCatching { org.json.JSONObject(text).optJSONArray("catalogs") }.getOrNull() ?: return@map emptyList()
                val base = manifest.removeSuffix("/manifest.json").removeSuffix("/")
                (0 until catalogs.length()).mapNotNull { catalogs.optJSONObject(it) }
                    .filter { c ->
                        val extras = c.optJSONArray("extra")
                        (extras != null && (0 until extras.length()).any { extras.optJSONObject(it)?.optString("name") == "search" }) ||
                            c.optJSONArray("extraSupported")?.let { a -> (0 until a.length()).any { a.optString(it) == "search" } } == true
                    }
                    .map { Triple(base, it.optString("type", "movie"), it.getString("id")) }
            }
            .collect(java.util.stream.Collectors.toList()).flatten()
            .take(12)

    private fun cacheFile(context: Context, source: Addons.Row, genre: String?) =
        File(context.cacheDir, "catalog_${hashName(source.key + "|" + (genre ?: "all"))}.json")

    /**
     * A cache file name for [key]. SHA-1 rather than hashCode: 32-bit hashes collide often
     * enough across hundreds of titles that one title could be shown with another's details.
     */
    fun hashName(key: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Deletes title, trailer and catalogue cache files untouched for two weeks (and stray
     * half-written temp files), at most once a day, so the cache doesn't grow forever on a
     * TV with little storage. Files under old naming schemes simply age out this way.
     */
    private fun pruneCache(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong("cache_pruned_at", 0) < 24 * 3600_000L) return
        p.edit().putLong("cache_pruned_at", now).apply()
        context.cacheDir.listFiles()?.forEach { f ->
            val ours = f.name.startsWith("meta_") || f.name.startsWith("trailer_") || f.name.startsWith("catalog_")
            val old = now - f.lastModified() > 14 * 24 * 3600_000L
            val staleTmp = f.name.endsWith(".tmp") && now - f.lastModified() > 3600_000L
            if (f.isFile && ((ours && old) || staleTmp)) f.delete()
        }
    }

    // ---- Titles the user chose to hide ----

    private fun prefs(context: Context) = context.getSharedPreferences("flix", Context.MODE_PRIVATE)

    fun hiddenTitles(context: Context): Set<String> = prefs(context).getStringSet("hidden_titles", emptySet()).orEmpty()

    fun hideTitle(context: Context, id: String) =
        prefs(context).edit().putStringSet("hidden_titles", hiddenTitles(context) + id).apply()

    fun clearHiddenTitles(context: Context) = prefs(context).edit().remove("hidden_titles").apply()
}
