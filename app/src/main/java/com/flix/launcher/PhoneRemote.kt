package com.flix.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * A tiny web page, served on the home network only while the Addons & rows
 * screen is open, so addon URLs can be pasted from a phone instead of typed
 * with the remote.
 *
 * Anyone on the Wi-Fi can reach the port, so every request must carry a random
 * token that is only in the QR code shown on the TV, and changes are POST-only
 * so a link on some other web page can't edit the rows.
 */
class PhoneRemote(private val context: Context, private val onChanged: () -> Unit) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var token = ""
    /** Bumped by start() and stop(), so work from an old session never calls [onChanged]. */
    @Volatile private var session = 0
    private val main = Handler(Looper.getMainLooper())
    /** A TV has little memory; a handful of phones at once is plenty. */
    private val slots = Semaphore(MAX_CLIENTS)
    private val clients = Collections.synchronizedSet(HashSet<Socket>())

    val address: String?
        get() = server?.let { s -> lanAddress()?.let { "http://$it:${s.localPort}/?t=$token" } }

    fun start() {
        if (server != null) return
        token = newToken()
        val id = ++session
        server = runCatching { ServerSocket(PORT) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() }
        val socket = server ?: return
        thread(name = "phone-remote", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (!slots.tryAcquire()) { runCatching { client.close() }; continue }
                clients += client
                thread(isDaemon = true) {
                    try { runCatching { handle(client, id) } } finally {
                        clients -= client
                        runCatching { client.close() }
                        slots.release()
                    }
                }
            }
        }
    }

    fun stop() {
        session++
        runCatching { server?.close() }
        server = null
        // Close phones still mid-request so their threads end now.
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
    }

    private fun handle(client: Socket, id: Int) {
        client.soTimeout = 5000
        val deadline = SystemClock.uptimeMillis() + DEADLINE_MS
        val input = client.getInputStream()
        val head = readHead(input, deadline) ?: return
        val lines = head.split("\r\n")
        val parts = lines[0].split(" ")
        val method = parts.getOrNull(0) ?: return
        val target = parts.getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val length = lines.drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return respond(client, 413, "Too large")
        val body = if (method == "POST" && length > 0) readBody(input, length, deadline) ?: return else ""
        val query = params(target.substringAfter('?', "")) + params(body)
        if (token.isEmpty() || query["t"] != token) return respond(client, 403, "Scan the QR code on the TV again.")
        if (path != "/" && method != "POST") return respond(client, 405, "Use the buttons on the page.")
        var message = query["m"].orEmpty()
        when (path) {
            "/" -> {}
            "/add" -> message = query["url"]?.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { "Added ${Addons.add(context, url)} rows" }.getOrElse { it.message ?: "Couldn't add that" }
            } ?: ""
            "/move" -> Addons.move(context, query["i"]?.toIntOrNull() ?: -1, query["d"]?.toIntOrNull() ?: 0)
            "/toggle" -> Addons.toggle(context, query["i"]?.toIntOrNull() ?: -1)
            "/remove" -> Addons.remove(context, query["i"]?.toIntOrNull() ?: -1)
            else -> return respond(client, 404, "Not found")
        }
        if (path != "/") {
            main.post { if (session == id) onChanged() }
            // Redirect after a POST so reloading the page doesn't repeat the change.
            val to = "/?t=$token" + if (message.isNotBlank()) "&m=" + URLEncoder.encode(message, "UTF-8") else ""
            val out = client.getOutputStream()
            out.write("HTTP/1.1 303 See Other\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        respond(client, 200, page(message), html = true)
    }

    private fun respond(client: Socket, code: Int, text: String, html: Boolean = false) {
        val body = text.toByteArray()
        val type = if (html) "text/html" else "text/plain"
        val out = client.getOutputStream()
        out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    /** Reads up to the blank line ending the headers; null when it's too big or too slow. */
    private fun readHead(input: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream()
        var tail = 0
        while (buf.size() < MAX_HEAD && SystemClock.uptimeMillis() < deadline) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) return buf.toString("ISO-8859-1").trimEnd()
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int, deadline: Long): String? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            if (SystemClock.uptimeMillis() > deadline) return null
            val n = input.read(bytes, read, length - read)
            if (n < 0) return null
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun params(raw: String): Map<String, String> = raw.split('&').filter { '=' in it }.associate {
        it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
    }

    private fun newToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..12).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun page(message: String): String {
        val rows = Addons.rows(context)
        val t = "<input type=\"hidden\" name=\"t\" value=\"$token\">"
        val items = rows.mapIndexed { i, r ->
            """
            <li class="${if (r.enabled) "" else "off"}">
              <div><b>${esc(r.title)}</b><small>${esc(r.addonName)} · ${esc(r.type)}</small></div>
              <nav>
                <form method="post" action="/move">$t<input type="hidden" name="i" value="$i"><button name="d" value="-1">▲</button><button name="d" value="1">▼</button></form>
                <form method="post" action="/toggle">$t<input type="hidden" name="i" value="$i"><button>${if (r.enabled) "Hide" else "Show"}</button></form>
                <form method="post" action="/remove" onsubmit="return confirm('Remove this row?')">$t<input type="hidden" name="i" value="$i"><button class="del">✕</button></form>
              </nav>
            </li>""".trimIndent()
        }.joinToString("\n")
        return """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Flix Launcher rows</title>
<style>
:root{color-scheme:dark}body{font:15px system-ui,sans-serif;background:#0b0f14;color:#eef2f6;margin:0;padding:16px;max-width:640px;margin:auto}
h1{font-size:22px;margin:8px 0 4px}p{color:#9aa7b4;margin:0 0 14px}
form{display:flex;gap:6px;margin:0}form.add{gap:8px;margin:12px 0 18px}input{flex:1;min-width:0;padding:12px;border-radius:10px;border:1px solid #2a3441;background:#141b24;color:#fff;font-size:15px}
button{padding:12px 16px;border:0;border-radius:10px;background:#fff;color:#0b0f14;font-weight:600}
ul{list-style:none;padding:0;margin:0}li{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px;border-radius:12px;background:#141b24;margin-bottom:8px}
li.off{opacity:.45}li small{display:block;color:#9aa7b4;margin-top:2px}nav{display:flex;gap:6px;flex-shrink:0}
nav button{padding:8px 10px;border-radius:8px;background:#222c38;color:#fff;font-weight:400}nav button.del{background:#3a1f24}
.msg{padding:10px 12px;border-radius:10px;background:#1d2a1f;color:#bfe8c4;margin-bottom:12px}
</style></head><body>
<h1>Flix Launcher · Addons &amp; rows</h1>
<p>Paste a Stremio/Nuvio addon manifest URL. Each of its catalogues becomes a row on your TV.</p>
${if (message.isNotBlank()) "<div class=\"msg\">${esc(message)}</div>" else ""}
<form class="add" method="post" action="/add">$t<input name="url" placeholder="https://…/manifest.json" autocomplete="off" autocapitalize="off"><button>Add</button></form>
<ul>$items</ul>
</body></html>
""".trimIndent()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private companion object {
        const val PORT = 8787
        const val MAX_CLIENTS = 4
        /** Request line plus headers; a phone browser sends well under this. */
        const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 4 * 1024
        /** A whole request must arrive within this, so a slow sender can't hold a slot. */
        const val DEADLINE_MS = 10_000L
    }
}
