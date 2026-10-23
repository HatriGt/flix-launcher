package com.tvlauncher.aura

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
    ) {
        /** Opens the title's page in Nuvio, ready to pick a stream and play. */
        val nuvioUri get() = "nuvio://detail/$type/$id"
    }

    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** Cached titles if any (even stale), for an instant first paint. */
    fun cached(context: Context, source: Addons.Row, genre: String?): List<Title>? =
        cacheFile(context, source, genre).takeIf { it.exists() }?.let { runCatching { parse(it.readText()) }.getOrNull() }

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
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val titles = parse(body)
        cacheFile(context, source, genre).writeText(body)
        titles
    }.getOrNull()

    private fun parse(body: String): List<Title> {
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
                poster = m.optString("poster").ifBlank { null } ?: art?.let { "$it/poster/small/$id/img" },
                backdrop = m.optString("background").ifBlank { null } ?: art?.let { "$it/background/medium/$id/img" },
                logo = m.optString("logo").ifBlank { null } ?: art?.let { "$it/logo/medium/$id/img" },
                year = m.optString("releaseInfo").ifBlank { m.optString("year") }.ifBlank { null },
                rating = m.optString("imdbRating").ifBlank { null },
                runtime = m.optString("runtime").ifBlank { null },
                genres = genres?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty(),
                description = m.optString("description").ifBlank { null },
            )
        }
    }

    private fun cacheFile(context: Context, source: Addons.Row, genre: String?) =
        File(context.cacheDir, "catalog_${(source.key + "|" + (genre ?: "all")).hashCode().toUInt()}.json")

    // ---- Titles the user chose to hide ----

    private fun prefs(context: Context) = context.getSharedPreferences("aura", Context.MODE_PRIVATE)

    fun hiddenTitles(context: Context): Set<String> = prefs(context).getStringSet("hidden_titles", emptySet()).orEmpty()

    fun hideTitle(context: Context, id: String) =
        prefs(context).edit().putStringSet("hidden_titles", hiddenTitles(context) + id).apply()

    fun clearHiddenTitles(context: Context) = prefs(context).edit().remove("hidden_titles").apply()
}
