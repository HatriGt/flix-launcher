package com.flix.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Live TV from Nuvio+IPTV (com.nuvio.livetv): the channels, groups and favourites the user set
 * up there, read through its "Setup Sync" with the same Nuvio sign-in Flix already has.
 *
 * The sync carries the playlist login (Xtream Codes), favourites and hidden groups; the channel
 * list itself, logos and what's on come from the provider's own API, as Nuvio+IPTV reads them.
 * The login is only held in memory; the channel list cached on disk has no credentials in it.
 * Watching full screen hands over to Nuvio+IPTV with its own link, so its player, guide and
 * settings stay the place to manage everything.
 */
object LiveTv {
    const val PACKAGE = "com.nuvio.livetv"
    private const val SYNC_URL = "https://livetv-cloud.nuvioplusiptv.workers.dev/v1/setup"
    private const val DAY = 24 * 3600_000L

    class Channel(
        val key: String, val name: String, val number: Int, val logo: String?, val group: String,
        val streamId: String, val epgId: String?,
    )

    class Group(val name: String, val channels: List<Channel>)

    class Lineup(val favourites: List<Channel>, val groups: List<Group>) {
        val all get() = groups.flatMap { it.channels }
    }

    class Programme(val title: String, val start: Long, val end: Long, val about: String?) {
        fun progress(now: Long = System.currentTimeMillis()) = ((now - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    class Guide(val now: Programme?, val next: Programme?)

    /** One Xtream login from the synced setup. Memory only. */
    private class Source(val id: String, val name: String, val server: String, val user: String, val pass: String, val ext: String)

    enum class State { NOT_INSTALLED, NOT_SIGNED_IN, NO_SYNC, READY }

    @Volatile private var source: Source? = null
    @Volatile var lineup: Lineup? = null
        private set

    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    fun state(context: Context): State = when {
        !installed(context) -> State.NOT_INSTALLED
        !NuvioAccount.isSignedIn(context) -> State.NOT_SIGNED_IN
        lineup == null -> State.NO_SYNC
        else -> State.READY
    }

    /** Whether the TV tab should appear at all: Nuvio+IPTV is on this TV. */
    fun available(context: Context) = installed(context) && FlixSettings.isOn(context, FlixSettings.LIVE_TV)

    /**
     * Reads the synced setup and the provider's channel list (cached for a day), and builds
     * the favourites and visible groups. Blocking; call off the main thread.
     */
    fun load(context: Context, force: Boolean = false): Lineup? {
        if (!force) lineup?.let { return it }
        val setup = NuvioAccount.liveTvSetup(context) ?: return null
        fun value(key: String): String? = setup.optJSONObject(key)?.opt("v")?.toString()
        val profile = NuvioAccount.profile(context)?.index
        fun perProfile(base: String): String? = value("${base}_p$profile")
            ?: setup.keys().asSequence().firstOrNull { it.startsWith("${base}_p") }?.let { value(it) }

        val playlists = runCatching { JSONArray(value("playlists")) }.getOrNull() ?: return null
        val src = (0 until playlists.length()).map { playlists.getJSONObject(it) }
            .firstOrNull { it.optBoolean("enabled", true) && it.optBoolean("importLive", true) && it.optString("xtreamServer").isNotBlank() }
            ?.let {
                Source(it.getString("id"), it.optString("name"), it.getString("xtreamServer").trimEnd('/'),
                    it.getString("xtreamUsername"), it.getString("xtreamPassword"),
                    if (it.optString("streamFormat") == "m3u8") "m3u8" else "ts")
            } ?: return null
        source = src

        val favouriteKeys = runCatching { JSONArray(perProfile("favorites")) }.getOrNull()
            ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val hidden = runCatching { JSONArray(perProfile("hidden_groups")) }.getOrNull()
            ?.let { a -> (0 until a.length()).map { a.getString(it) }.toHashSet() }.orEmpty()

        val channels = runCatching { channels(context, src, force) }
            .onFailure { android.util.Log.w("Flix", "Live TV channels: ${it.javaClass.simpleName} ${it.message?.take(60)}") }
            .getOrNull() ?: return null
        val byKey = channels.associateBy { it.key }
        val groups = channels.groupBy { it.group }.filterKeys { it !in hidden }
            .map { (name, list) -> Group(name, list.sortedBy { it.number }) }
        val built = Lineup(favouriteKeys.mapNotNull { byKey[it] }, groups)
        lineup = built
        return built
    }

    /** The provider's live channels, keyed exactly as Nuvio+IPTV keys them, cached for a day. */
    private fun channels(context: Context, src: Source, force: Boolean): List<Channel> {
        val cache = File(context.cacheDir, "livetv2_${src.id}.json")
        val fresh = !force && cache.exists() && System.currentTimeMillis() - cache.lastModified() < DAY
        val streams = (if (fresh) runCatching { JSONArray(cache.readText()) }.getOrNull() else null) ?: run {
            val cats = JSONArray(api(src, "get_live_categories"))
            val catNames = (0 until cats.length()).map { cats.getJSONObject(it) }
                .associate { it.optString("category_id") to it.optString("category_name") }
            val raw = JSONArray(api(src, "get_live_streams"))
            val library = logoLibrary(context)
            // Only what Flix needs, and nothing secret, goes to disk.
            JSONArray().also { out ->
                for (i in 0 until raw.length()) {
                    val s = raw.getJSONObject(i)
                    out.put(JSONObject()
                        .put("id", s.optString("stream_id"))
                        .put("n", s.optString("name").replace("\n", " ").trim())
                        .put("num", s.optInt("num"))
                        .put("l", s.optString("stream_icon").takeIf { it.startsWith("http") }
                            ?: library[s.optString("epg_channel_id").lowercase()]
                            ?: library[nameKey(s.optString("name"))] ?: "")
                        .put("e", s.optString("epg_channel_id"))
                        .put("g", catNames[s.optString("category_id")] ?: "Uncategorized"))
                }
                SafeIo.write(cache, out.toString())
            }
        }
        val used = HashSet<String>()
        return (0 until streams.length()).map { streams.getJSONObject(it) }.filter { it.optString("id").isNotBlank() }.map { s ->
            val name = s.optString("n")
            val epg = s.optString("e").takeIf { it.isNotBlank() && it != "null" }
            // Nuvio+IPTV's key: playlist|tvg-id|name, with #2, #3… for repeats.
            val base = "${src.id}|${epg ?: ""}|$name"
            var key = base; var n = 2
            while (!used.add(key)) key = "$base#${n++}"
            Channel(key, name, s.optInt("num"), s.optString("l").takeIf { it.startsWith("http") }, s.optString("g"),
                s.optString("id"), epg)
        }
    }

    /**
     * Logos for channels the provider sends none for, from iptv-org's public list (the one
     * Nuvio+IPTV uses too), keyed by channel id ("sunnews.in") and by a squashed name
     * ("sunnews"). The list (6 MB) is fetched at most weekly and only the map is kept.
     */
    private fun logoLibrary(context: Context): Map<String, String> = runCatching {
        val file = File(context.cacheDir, "livetv_logos.json")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 7 * DAY) {
            val conn = URL("https://iptv-org.github.io/api/logos.json").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 30000
            val list = JSONArray(try { SafeIo.readText(conn, 16 * 1024 * 1024) } finally { conn.disconnect() })
            val map = JSONObject()
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val id = o.optString("channel").lowercase()
                val url = o.optString("url")
                if (id.isEmpty() || !url.startsWith("http") || map.has(id)) continue
                map.put(id, url)
                val short = id.substringBefore('.').filter { it.isLetterOrDigit() }
                if (short.length >= 3 && !map.has(short)) map.put(short, url)
            }
            SafeIo.write(file, map.toString())
        }
        val map = JSONObject(file.readText())
        map.keys().asSequence().associateWith { map.getString(it) }
    }.getOrDefault(emptyMap())

    /** "TM: NEWS 7 TAMIL HD" → "news7tamil": tags, quality and symbols removed. */
    private fun nameKey(name: String): String = name
        .replace(Regex("""^\s*(?:[A-Za-z]{2,6}\s*[:|]\s*)+"""), "")
        .replace(Regex("""[\[(][^\])]*[\])]"""), "")
        .replace(Regex("""(?i)\b(FHD|UHD|HD|SD|HQ|4K|HEVC|VIP|BACKUP|1080p?|720p)\b"""), "")
        .lowercase().filter { it.isLetterOrDigit() }

    /** Where to play [channel] from, for the preview. Null until the setup is loaded. */
    fun streamUrl(channel: Channel): String? = source?.let { "${it.server}/live/${enc(it.user)}/${enc(it.pass)}/${channel.streamId}.${it.ext}" }

    private val guides = android.util.LruCache<String, Pair<Long, Guide>>(400)

    /** What's on now and next, from the provider's short guide; cached for ten minutes. Blocking. */
    fun guide(channel: Channel): Guide? {
        val now = System.currentTimeMillis()
        guides.get(channel.streamId)?.let { (at, g) -> if (now - at < 600_000 && (g.now?.end ?: Long.MAX_VALUE) > now) return g }
        val src = source ?: return null
        val list = runCatching {
            JSONObject(api(src, "get_short_epg", "&stream_id=${channel.streamId}&limit=3")).optJSONArray("epg_listings")
        }.getOrNull() ?: return null
        val items = (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { e ->
            val start = e.optLong("start_timestamp") * 1000
            val end = e.optLong("stop_timestamp") * 1000
            if (start <= 0 || end <= start) return@mapNotNull null
            Programme(b64(e.optString("title")), start, end, b64(e.optString("description")).ifBlank { null })
        }.sortedBy { it.start }
        val current = items.firstOrNull { it.start <= now && now < it.end }
        val g = Guide(current, items.firstOrNull { it.start >= (current?.end ?: now) })
        guides.put(channel.streamId, now to g)
        return g
    }

    /** Plays [channel] full screen in Nuvio+IPTV. */
    fun open(context: Context, channel: Channel?) {
        val uri = if (channel == null) "nuvio://livetv" else "nuvio://livetv/channel/" + Uri.encode(channel.key)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { context.startActivity(it) } }
    }

    fun openApp(context: Context) {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun b64(s: String): String = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)).trim() }.getOrDefault(s)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(src: Source, action: String, extra: String = ""): String {
        val conn = URL("${src.server}/player_api.php?username=${enc(src.user)}&password=${enc(src.pass)}&action=$action$extra")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "FlixLauncher/1.0 (Android TV)")
        // A big provider lists 15,000+ channels: well past the usual 4 MB cap.
        return try { SafeIo.readText(conn, 32 * 1024 * 1024) } finally { conn.disconnect() }
    }
}
