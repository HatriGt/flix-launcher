package com.tvlauncher.aura

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Signs in to the user's Nuvio account the way Nuvio's own TV app does (a code
 * approved on nuvio.tv/tv-login, no password on the TV) and reads the chosen
 * profile's watch progress for the Continue watching row.
 *
 * This uses Nuvio's sync service, which has no public API: it may change.
 * Everything here runs on background threads.
 */
object NuvioAccount {
    data class Login(val code: String, val webUrl: String, val nonce: String, val pollSeconds: Int, val expiresAtMs: Long)
    data class Profile(val index: Int, val name: String, val color: Int = 0xFF4F7CFF.toInt())

    private const val API = "https://api.nuvio.tv"
    private const val CONFIG_URL = "https://nuvio.tv/config.js"
    private const val CINEMETA = "https://v3-cinemeta.strem.io"

    private fun prefs(context: Context) = context.getSharedPreferences("nuvio_account", Context.MODE_PRIVATE)

    fun isSignedIn(context: Context) = prefs(context).getString("refresh_token", null) != null

    fun profile(context: Context): Profile? = prefs(context).let { p ->
        if (!p.contains("profile_index")) null else Profile(p.getInt("profile_index", 1), p.getString("profile_name", "").orEmpty())
    }

    fun setProfile(context: Context, profile: Profile) =
        prefs(context).edit().putInt("profile_index", profile.index).putString("profile_name", profile.name).apply()

    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
        File(context.cacheDir, "continue.json").delete()
    }

    // ---- Sign-in by code ----

    fun startLogin(context: Context): Login {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val rows = JSONArray(rpc(context, "start_tv_login_session", JSONObject()
            .put("p_device_nonce", nonce)
            .put("p_redirect_base_url", "https://nuvio.tv/tv-login")
            .put("p_device_name", "Lumo on ${android.os.Build.MODEL}"), anonymous = true))
        val row = rows.getJSONObject(0)
        val expires = runCatching {
            java.time.OffsetDateTime.parse(row.getString("expires_at")).toInstant().toEpochMilli()
        }.getOrDefault(System.currentTimeMillis() + 5 * 60_000)
        return Login(row.getString("code"), row.getString("web_url"), nonce, row.optInt("poll_interval_seconds", 3), expires)
    }

    /** Signs in with the email and password the user typed on the TV; they go straight to Nuvio and aren't kept. */
    fun passwordLogin(context: Context, email: String, password: String) {
        val body = request(context, "$API/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password).toString(), bearer = anonKey(context))
        saveSession(context, JSONObject(body))
    }

    /** "pending", "approved", "expired"… */
    fun poll(context: Context, login: Login): String {
        val rows = JSONArray(rpc(context, "poll_tv_login_session",
            JSONObject().put("p_code", login.code).put("p_device_nonce", login.nonce), anonymous = true))
        return rows.optJSONObject(0)?.optString("status") ?: "pending"
    }

    /** Swaps an approved code for a session and keeps only its tokens. */
    fun finishLogin(context: Context, login: Login) {
        val body = request(context, "$API/functions/v1/tv-logins-exchange",
            JSONObject().put("code", login.code).put("device_nonce", login.nonce).toString(), bearer = anonKey(context))
        saveSession(context, JSONObject(body).let { it.optJSONObject("session") ?: it })
    }

    fun profiles(context: Context): List<Profile> {
        val rows = JSONArray(rpc(context, "sync_pull_profiles", JSONObject()))
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .map {
                val color = runCatching { android.graphics.Color.parseColor(it.optString("avatar_color_hex")) }.getOrDefault(0xFF4F7CFF.toInt())
                Profile(it.getInt("profile_index"), it.optString("name"), color)
            }
            .sortedBy { it.index }
    }

    // ---- Continue watching ----

    /** Unfinished titles for the chosen profile, newest first, with artwork from Cinemeta. */
    fun continueWatching(context: Context): List<Catalog.Title> {
        val profile = profile(context) ?: return emptyList()
        val rows = JSONArray(rpc(context, "sync_pull_watch_progress", JSONObject().put("p_profile_id", profile.index)))
        val latest = LinkedHashMap<String, JSONObject>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val id = r.optString("content_id")
            if (id.isBlank()) continue
            val previous = latest[id]
            if (previous == null || r.optLong("last_watched") > previous.optLong("last_watched")) latest[id] = r
        }
        return latest.values
            .filter { r ->
                val duration = r.optLong("duration")
                duration > 0 && r.optLong("position").toFloat() / duration < 0.92f
            }
            .sortedByDescending { it.optLong("last_watched") }
            .take(20)
            .let { picked ->
                // Look titles up in parallel; each may hit a different addon.
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                val metas = picked.map { r -> pool.submit<Pair<JSONObject, String?>?> { meta(context, r.optString("content_type", "movie"), r.getString("content_id")) } }
                    .map { runCatching { it.get() }.getOrNull() }
                pool.shutdown()
                picked.zip(metas)
            }
            .mapNotNull { (r, found) ->
                val id = r.getString("content_id")
                val type = r.optString("content_type", "movie")
                val (meta, base) = found ?: (JSONObject() to null)
                val season = r.optInt("season", 0)
                val episode = r.optInt("episode", 0)
                Catalog.Title(
                    id = id,
                    type = type,
                    name = meta.optString("name").ifBlank { id },
                    poster = meta.optString("poster").takeIf { it.isNotBlank() && it != "null" },
                    backdrop = meta.optString("background").takeIf { it.isNotBlank() && it != "null" },
                    logo = meta.optString("logo").takeIf { it.isNotBlank() && it != "null" },
                    year = meta.optString("releaseInfo").takeIf { it.isNotBlank() && it != "null" },
                    rating = meta.optString("imdbRating").takeIf { it.isNotBlank() && it != "null" },
                    runtime = meta.optString("runtime").takeIf { it.isNotBlank() && it != "null" },
                    genres = meta.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty(),
                    description = meta.optString("description").takeIf { it.isNotBlank() && it != "null" },
                    progress = r.optLong("position").toFloat() / r.optLong("duration"),
                    episodeLabel = if (type == "series" && season > 0) "S$season E$episode" else null,
                    addonBase = base,
                )
            }
    }

    /**
     * Title details and the addon they came from, cached on disk for a week. IMDb ids
     * come from Cinemeta; other ids (e.g. IndiaStreamz's) are asked of the user's own
     * addons in turn, and that addon is remembered so Nuvio can open the title.
     */
    private fun meta(context: Context, type: String, id: String): Pair<JSONObject, String?>? {
        val file = File(context.cacheDir, "meta_${type}_${id.hashCode().toUInt()}.json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L) {
            runCatching {
                val cached = JSONObject(file.readText())
                return cached.getJSONObject("meta") to cached.optString("aura_base").ifBlank { null }
            }
        }
        val bases = if (id.startsWith("tt")) listOf(CINEMETA)
        else Addons.rows(context).map { it.base }.distinct().filter { it != CINEMETA && !it.startsWith("nuvio:") }
        for (base in bases) {
            val found = runCatching {
                val body = request(context, "$base/meta/$type/${java.net.URLEncoder.encode(id, "UTF-8")}.json", null, bearer = null, apikey = false)
                JSONObject(body).optJSONObject("meta")?.takeIf { it.optString("name").isNotBlank() }
            }.getOrNull() ?: continue
            val addon = if (base == CINEMETA) null else base
            file.writeText(JSONObject().put("meta", found).put("aura_base", addon ?: "").toString())
            return found to addon
        }
        return null
    }

    // ---- Plumbing ----

    private fun rpc(context: Context, name: String, body: JSONObject, anonymous: Boolean = false): String =
        request(context, "$API/rest/v1/rpc/$name", body.toString(), bearer = if (anonymous) anonKey(context) else accessToken(context))

    private fun accessToken(context: Context): String {
        val p = prefs(context)
        val token = p.getString("access_token", null)
        if (token != null && System.currentTimeMillis() / 1000 < p.getLong("expires_at", 0) - 60) return token
        val refresh = p.getString("refresh_token", null) ?: throw IllegalStateException("Not signed in to Nuvio")
        val body = request(context, "$API/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh).toString(), bearer = anonKey(context))
        saveSession(context, JSONObject(body))
        return prefs(context).getString("access_token", null)!!
    }

    private fun saveSession(context: Context, session: JSONObject) {
        val access = session.getString("access_token")
        val expiresAt = session.optLong("expires_at").takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600))
        prefs(context).edit()
            .putString("access_token", access)
            .putString("refresh_token", session.getString("refresh_token"))
            .putLong("expires_at", expiresAt)
            .apply()
    }

    /** Nuvio's public client key, read from its website config so it stays current. */
    private fun anonKey(context: Context): String {
        val p = prefs(context)
        p.getString("anon_key", null)?.let { return it }
        val js = request(context, CONFIG_URL, null, bearer = null, apikey = false)
        val key = Regex("supabaseAnonKey[\"']?\\s*[:=]\\s*[\"']([^\"']+)").find(js)?.groupValues?.get(1)
            ?: throw IllegalStateException("Couldn't read Nuvio's sign-in settings")
        p.edit().putString("anon_key", key).apply()
        return key
    }

    private fun request(context: Context, url: String, body: String?, bearer: String?, apikey: Boolean = true): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        if (apikey) conn.setRequestProperty("apikey", anonKey(context))
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).let { it.optString("error").ifBlank { it.optString("message") } } }.getOrNull()
                throw IllegalStateException(message?.ifBlank { null } ?: "Nuvio returned $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
