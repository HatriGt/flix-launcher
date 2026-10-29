package com.flix.launcher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    /** A reply from Nuvio with an HTTP error status, so callers can tell "rejected" from "offline". */
    class HttpError(val code: Int, message: String) : IllegalStateException(message)

    private fun prefs(context: Context) = context.getSharedPreferences("nuvio_account", Context.MODE_PRIVATE)

    // Cheap check (no decryption) so the UI thread can call it; a token that later fails
    // to decrypt signs the user out, after which this turns false.
    fun isSignedIn(context: Context) = prefs(context).let { it.contains("refresh_token_enc") || it.contains("refresh_token") }

    fun profile(context: Context): Profile? = prefs(context).let { p ->
        if (!p.contains("profile_index")) null else Profile(p.getInt("profile_index", 1), p.getString("profile_name", "").orEmpty())
    }

    fun setProfile(context: Context, profile: Profile) =
        prefs(context).edit().putInt("profile_index", profile.index).putString("profile_name", profile.name).apply()

    fun signOut(context: Context) {
        // Keeps the public anon key: it isn't tied to the account and saves a fetch next sign-in.
        val anon = prefs(context).getString("anon_key", null)
        prefs(context).edit().clear().apply { anon?.let { putString("anon_key", it) } }.apply()
        File(context.cacheDir, "continue.json").delete()
    }

    // ---- Sign-in by code ----

    fun startLogin(context: Context): Login {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val rows = JSONArray(rpc(context, "start_tv_login_session", JSONObject()
            .put("p_device_nonce", nonce)
            .put("p_redirect_base_url", "https://nuvio.tv/tv-login")
            .put("p_device_name", "Flix Launcher on ${android.os.Build.MODEL}"), anonymous = true))
        val row = rows.getJSONObject(0)
        val expires = runCatching {
            java.time.OffsetDateTime.parse(row.getString("expires_at")).toInstant().toEpochMilli()
        }.getOrDefault(System.currentTimeMillis() + 5 * 60_000)
        return Login(row.getString("code"), row.getString("web_url"), nonce, row.optInt("poll_interval_seconds", 3), expires)
    }

    /** Signs in with the email and password the user typed on the TV; they go straight to Nuvio and aren't kept. */
    fun passwordLogin(context: Context, email: String, password: String) {
        val body = request(context, "$API/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password).toString(), anonBearer = true)
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
            JSONObject().put("code", login.code).put("device_nonce", login.nonce).toString(), anonBearer = true)
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

    // ---- Addons ----

    /**
     * Manifest URLs of the addons installed in Nuvio for the chosen profile, in Nuvio's
     * order, switched-off ones left out. Nuvio keeps them in its "addons" table.
     */
    fun addonUrls(context: Context): List<String> {
        val profile = profile(context) ?: return emptyList()
        val token = accessToken(context)
        val user = userId(token)?.let { "&user_id=eq.$it" }.orEmpty()
        val body = request(context, "$API/rest/v1/addons?select=url,enabled,sort_order&profile_id=eq.${profile.index}$user&order=sort_order.asc",
            null, bearer = token)
        val rows = JSONArray(body)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optBoolean("enabled", true) }
            .mapNotNull { it.optString("url").ifBlank { null } }
            .distinct()
    }

    /** The account id inside the access token (a JWT's "sub"). */
    private fun userId(token: String): String? = runCatching {
        val payload = token.split('.')[1]
        JSONObject(String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)))
            .optString("sub").ifBlank { null }
    }.getOrNull()

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
                // Look titles up in parallel; each may hit a different addon. The whole lookup
                // is capped so one slow addon can't hold the row for minutes: titles not found
                // in time just show their id and get their artwork on the next refresh.
                val deadline = System.currentTimeMillis() + META_BUDGET_MS
                val futures = picked.map { r -> metaPool.submit<Pair<JSONObject, String?>?> { meta(context, r.optString("content_type", "movie"), r.getString("content_id")) } }
                val metas = futures.map { f ->
                    val left = deadline - System.currentTimeMillis()
                    runCatching { f.get(maxOf(left, 1), TimeUnit.MILLISECONDS) }
                        .onFailure { f.cancel(true) }
                        .getOrNull()
                }
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
                    season = season.takeIf { type == "series" && it > 0 },
                    episode = episode.takeIf { type == "series" && season > 0 },
                    addonBase = base,
                )
            }
    }

    private const val META_BUDGET_MS = 20_000L

    /** One small shared pool for title lookups; its threads exit when idle so they cost nothing between refreshes. */
    private val metaPool = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply { allowCoreThreadTimeOut(true) }

    /**
     * Title details and the addon they came from, cached on disk for a week. Titles no
     * addon knows are remembered for a day, so they aren't asked about on every refresh. IMDb ids
     * come from Cinemeta; other ids (e.g. IndiaStreamz's) are asked of the user's own
     * addons in turn, and that addon is remembered so Nuvio can open the title.
     */
    private fun meta(context: Context, type: String, id: String): Pair<JSONObject, String?>? {
        val file = File(context.cacheDir, "meta_${Catalog.hashName("$type|$id")}.json")
        val age = System.currentTimeMillis() - file.lastModified()
        if (file.exists() && age < 7 * 24 * 3600_000L) {
            runCatching {
                val cached = JSONObject(file.readText())
                if (cached.optBoolean("missing")) { if (age < 24 * 3600_000L) return null }
                else return cached.getJSONObject("meta") to cached.optString("flix_base").ifBlank { null }
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
            runCatching { SafeIo.write(file, JSONObject().put("meta", found).put("flix_base", addon ?: "").toString()) }
            return found to addon
        }
        // Only remember a miss when there was something to ask; a cancelled lookup isn't a miss.
        if (bases.isNotEmpty() && !Thread.currentThread().isInterrupted) {
            runCatching { SafeIo.write(file, JSONObject().put("missing", true).toString()) }
        }
        return null
    }

    // ---- Plumbing ----

    private fun rpc(context: Context, name: String, body: JSONObject, anonymous: Boolean = false): String =
        if (anonymous) request(context, "$API/rest/v1/rpc/$name", body.toString(), anonBearer = true)
        else request(context, "$API/rest/v1/rpc/$name", body.toString(), bearer = accessToken(context))

    /**
     * A current access token, refreshing it when it's about to expire. Synchronized because
     * Nuvio's refresh tokens are single-use: two threads refreshing at once would send the
     * same one twice and the second would be rejected, signing the user out.
     */
    @Synchronized
    private fun accessToken(context: Context): String {
        val p = prefs(context)
        val token = readToken(context, "access_token")
        if (token != null && System.currentTimeMillis() / 1000 < p.getLong("expires_at", 0) - 60) return token
        val refresh = readToken(context, "refresh_token") ?: throw IllegalStateException("Not signed in to Nuvio")
        val body = try {
            request(context, "$API/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", refresh).toString(), anonBearer = true)
        } catch (e: HttpError) {
            // Nuvio rejected the refresh token (revoked, expired, already used): the session is
            // gone, so show the user as signed out rather than an empty Continue watching row.
            // Network errors aren't HttpErrors and leave the session alone.
            if (e.code == 400 || e.code == 401) signOut(context)
            throw e
        }
        saveSession(context, JSONObject(body))
        return readToken(context, "access_token") ?: throw IllegalStateException("Not signed in to Nuvio")
    }

    private fun saveSession(context: Context, session: JSONObject) {
        val access = session.getString("access_token")
        val expiresAt = session.optLong("expires_at").takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600))
        prefs(context).edit()
            .putString("access_token_enc", TokenBox.seal(access))
            .putString("refresh_token_enc", TokenBox.seal(session.getString("refresh_token")))
            .remove("access_token")
            .remove("refresh_token")
            .putLong("expires_at", expiresAt)
            .commit()
    }

    /**
     * A token from prefs. Older builds kept them in plain text; those are encrypted on first
     * read. A token that can't be decrypted (key lost after a restore, say) signs the user out.
     */
    private fun readToken(context: Context, name: String): String? {
        val p = prefs(context)
        p.getString(name, null)?.let { plain ->
            p.edit().putString("${name}_enc", TokenBox.seal(plain)).remove(name).commit()
            return plain
        }
        val sealed = p.getString("${name}_enc", null) ?: return null
        return runCatching { TokenBox.open(sealed) }.getOrElse {
            signOut(context)
            null
        }
    }

    /** Encrypts tokens with an AES key that lives in the Android Keystore and never leaves it. */
    private object TokenBox {
        private const val ALIAS = "nuvio_tokens"

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
            }.generateKey()
        }

        fun seal(text: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val iv = cipher.iv
            return Base64.encodeToString(iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
        }

        fun open(sealed: String): String {
            val all = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            return String(cipher.doFinal(all, 12, all.size - 12))
        }
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

    /**
     * One HTTP call. [anonBearer] sends the anon key as the bearer. The cached anon key is
     * kept until Nuvio rejects it: a 401 on an anonymous call, or one blaming the API key,
     * drops it and retries once with a freshly read key.
     */
    private fun request(
        context: Context, url: String, body: String?, bearer: String? = null,
        apikey: Boolean = true, anonBearer: Boolean = false, retried: Boolean = false,
    ): String = try {
        send(url, body, if (anonBearer) anonKey(context) else bearer, if (apikey) anonKey(context) else null)
    } catch (e: HttpError) {
        val staleKey = e.code == 401 && apikey && !retried &&
            (anonBearer || e.message.orEmpty().contains("api key", ignoreCase = true))
        if (!staleKey) throw e
        prefs(context).edit().remove("anon_key").apply()
        request(context, url, body, bearer, apikey, anonBearer, retried = true)
    }

    private fun send(url: String, body: String?, bearer: String?, apikey: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        apikey?.let { conn.setRequestProperty("apikey", it) }
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(SafeIo.readBytes(it, SafeIo.MAX_JSON)) }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).let { it.optString("error_description").ifBlank { it.optString("msg") }.ifBlank { it.optString("error") }.ifBlank { it.optString("message") } } }.getOrNull()
                throw HttpError(code, message?.ifBlank { null } ?: "Nuvio returned $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
