package com.flix.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The catalogue rows on the home screen, in order. Each row is one catalogue of
 * a Stremio-compatible addon (the same addons Nuvio uses), identified by the
 * addon's manifest URL. Cinemeta's popular lists are there by default.
 */
object Addons {
    data class Row(
        val manifest: String,
        val addonName: String,
        val type: String,
        val catalogId: String,
        val title: String,
        val enabled: Boolean = true,
        val supportsGenre: Boolean = false,
        /** Extra query the catalogue needs, e.g. "skip=0"; blank when none. */
        val extra: String = "",
    ) {
        val key get() = "$manifest|$type|$catalogId"
        val base get() = manifest.removeSuffix("/manifest.json").removeSuffix("/")
    }

    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    private val DEFAULT_ROWS = listOf(
        Row(CINEMETA, "Cinemeta", "movie", "top", "Popular movies", supportsGenre = true),
        Row(CINEMETA, "Cinemeta", "series", "top", "Popular series", supportsGenre = true),
        Row(CINEMETA, "Cinemeta", "movie", "imdbRating", "Featured movies", supportsGenre = true),
    )

    /**
     * Rows are edited from the TV screen and from the phone page at the same time;
     * every read-modify-save holds this lock so one edit never overwrites another.
     */
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences("flix", Context.MODE_PRIVATE)

    fun rows(context: Context): List<Row> {
        val raw = prefs(context).getString(KEY, null) ?: return DEFAULT_ROWS
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Row(
                    manifest = o.getString("manifest"),
                    addonName = o.optString("addonName"),
                    type = o.getString("type"),
                    catalogId = o.getString("catalogId"),
                    title = o.getString("title"),
                    enabled = o.optBoolean("enabled", true),
                    supportsGenre = o.optBoolean("supportsGenre"),
                    extra = o.optString("extra"),
                )
            }
        }.getOrDefault(DEFAULT_ROWS)
    }

    fun save(context: Context, rows: List<Row>) = synchronized(lock) {
        val array = JSONArray()
        rows.forEach { r ->
            array.put(JSONObject().apply {
                put("manifest", r.manifest)
                put("addonName", r.addonName)
                put("type", r.type)
                put("catalogId", r.catalogId)
                put("title", r.title)
                put("enabled", r.enabled)
                put("supportsGenre", r.supportsGenre)
                put("extra", r.extra)
            })
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    fun move(context: Context, index: Int, delta: Int) = synchronized(lock) {
        val list = rows(context).toMutableList()
        val to = index + delta
        if (index !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(index))
        save(context, list)
    }

    fun toggle(context: Context, index: Int) = synchronized(lock) {
        val list = rows(context).toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(enabled = !list[index].enabled)
        save(context, list)
    }

    /** Turns a row on or off; a row switched on joins the end of the rows already on the home screen. */
    fun setEnabled(context: Context, key: String, on: Boolean) = synchronized(lock) {
        val list = rows(context).toMutableList()
        val index = list.indexOfFirst { it.key == key }
        if (index < 0) return
        val row = list.removeAt(index).copy(enabled = on)
        val insertAt = if (on) list.indexOfLast { it.enabled } + 1 else list.size.coerceAtMost(index).let { i ->
            // Keep a switched-off row near where it was, but after every row still on.
            maxOf(i, list.indexOfLast { it.enabled } + 1)
        }
        list.add(insertAt, row)
        save(context, list)
    }

    /** Moves a row among the rows that are on, by key. */
    fun moveEnabled(context: Context, key: String, delta: Int) = synchronized(lock) {
        val list = rows(context)
        val index = list.indexOfFirst { it.key == key }
        val target = index + delta
        if (index < 0 || target !in list.indices || !list[target].enabled) return
        move(context, index, delta)
    }

    fun removeAddon(context: Context, manifest: String) = synchronized(lock) {
        save(context, rows(context).filter { it.manifest != manifest })
    }

    fun remove(context: Context, index: Int) = synchronized(lock) {
        val list = rows(context).toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        save(context, list)
    }

    fun reset(context: Context) = synchronized(lock) { prefs(context).edit().remove(KEY).apply() }

    /**
     * Reads an addon's manifest and appends one row per browsable catalogue
     * (catalogues that need a search term are skipped). Runs on a background thread.
     * Returns the number of rows added, or throws with a readable message.
     */
    fun add(context: Context, url: String): Int {
        val (name, found) = catalogues(normalise(url))
        // The download can take seconds, so the rows are read again under the lock just before saving.
        val added = synchronized(lock) {
            val existing = rows(context)
            val fresh = found.filter { f -> existing.none { it.key == f.key } }
            if (fresh.isEmpty()) throw IllegalArgumentException(
                if (found.isEmpty()) "$name has no catalogues Flix Launcher can show" else "$name's rows are already added"
            )
            // A big addon would flood the home screen; add its rows hidden so the user picks the few they want.
            save(context, existing + if (fresh.size > MAX_SHOWN_ON_ADD) fresh.map { it.copy(enabled = false) } else fresh)
            fresh.size
        }
        return added
    }

    /**
     * Adds every catalogue of the addons in the user's Nuvio profile, switched off, so the
     * Addons & rows screen lists them all and the user picks which appear at home. Rows
     * already here keep their place and state. Addons that can't be read are skipped.
     * Runs on a background thread; returns how many rows were added.
     */
    fun syncFromNuvio(context: Context): Int {
        val found = NuvioAccount.addonUrls(context).flatMap { url ->
            runCatching { catalogues(normalise(url.replaceFirst("http://", "https://"))).second }.getOrDefault(emptyList())
        }
        if (found.isEmpty()) return 0
        return synchronized(lock) {
            val existing = rows(context)
            val fresh = found.filter { f -> existing.none { it.key == f.key } }.distinctBy { it.key }
            if (fresh.isNotEmpty()) save(context, existing + fresh.map { it.copy(enabled = false) })
            fresh.size
        }
    }

    /** An addon's name and one row per browsable catalogue (ones that need a search term are skipped). */
    private fun catalogues(manifestUrl: String): Pair<String, List<Row>> {
        // Plain http would let anyone on the path swap the rows' content; the app only allows cleartext to sylvan.apple.com.
        if (!manifestUrl.startsWith("https://")) throw IllegalArgumentException("Addon URLs must start with https://")
        val conn = URL(manifestUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        val manifest = try {
            JSONObject(SafeIo.readText(conn))
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't read that addon (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
        val name = manifest.optString("name", "Addon")
        val catalogs = manifest.optJSONArray("catalogs") ?: JSONArray()
        val found = mutableListOf<Row>()
        for (i in 0 until catalogs.length()) {
            val c = catalogs.getJSONObject(i)
            val extras = c.optJSONArray("extra") ?: JSONArray()
            var needsInput = false
            var genre = false
            for (j in 0 until extras.length()) {
                val e = extras.getJSONObject(j)
                when (e.optString("name")) {
                    "genre" -> genre = true
                    "skip" -> {}
                    else -> if (e.optBoolean("isRequired")) needsInput = true
                }
            }
            val required = c.optJSONArray("extraRequired")
            if (required != null && (0 until required.length()).any { required.getString(it) != "genre" }) needsInput = true
            if (needsInput) continue
            val row = Row(
                manifest = manifestUrl,
                addonName = name,
                type = c.optString("type", "movie"),
                catalogId = c.getString("id"),
                title = c.optString("name").ifBlank { "$name ${c.optString("type")}" },
                supportsGenre = genre,
            )
            if (found.none { it.key == row.key }) found += row
        }
        return name to found
    }

    /** Accepts stremio:// links and URLs pasted without /manifest.json. */
    fun normalise(url: String): String {
        var u = url.trim().replace("stremio://", "https://")
        if (!u.startsWith("http")) u = "https://$u"
        if (!u.endsWith("manifest.json")) u = u.trimEnd('/') + "/manifest.json"
        return u
    }

    private const val KEY = "catalogue_rows"
    private const val MAX_SHOWN_ON_ADD = 6
}
