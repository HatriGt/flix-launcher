package com.homehalo.tv

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
    ) {
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
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val titles = parse(body, source.base)
        cacheFile(context, source, genre).writeText(body)
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
    fun search(query: String): List<Title> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return listOf("movie", "series").flatMap { type ->
            runCatching {
                val conn = URL("https://v3-cinemeta.strem.io/catalog/$type/top/search=$q.json").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                parse(body, "https://v3-cinemeta.strem.io").take(15)
            }.getOrDefault(emptyList())
        }
    }

    private fun cacheFile(context: Context, source: Addons.Row, genre: String?) =
        File(context.cacheDir, "catalog_${(source.key + "|" + (genre ?: "all")).hashCode().toUInt()}.json")

    // ---- Titles the user chose to hide ----

    private fun prefs(context: Context) = context.getSharedPreferences("halo", Context.MODE_PRIVATE)

    fun hiddenTitles(context: Context): Set<String> = prefs(context).getStringSet("hidden_titles", emptySet()).orEmpty()

    fun hideTitle(context: Context, id: String) =
        prefs(context).edit().putStringSet("hidden_titles", hiddenTitles(context) + id).apply()

    fun clearHiddenTitles(context: Context) = prefs(context).edit().remove("hidden_titles").apply()
}
