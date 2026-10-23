package com.homehalo.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny web page, served on the home network only while the Addons & rows
 * screen is open, so addon URLs can be pasted from a phone instead of typed
 * with the remote.
 */
class PhoneRemote(private val context: Context, private val onChanged: () -> Unit) {
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    val address: String?
        get() = server?.let { s -> lanAddress()?.let { "http://$it:${s.localPort}" } }

    fun start() {
        if (server != null) return
        server = runCatching { ServerSocket(PORT) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() }
        val socket = server ?: return
        thread(name = "phone-remote", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) } ; runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 8000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val request = reader.readLine() ?: return
        val target = request.split(" ").getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        var message = ""
        when (path) {
            "/add" -> message = query["url"]?.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { "Added ${Addons.add(context, url)} rows" }.getOrElse { it.message ?: "Couldn't add that" }
            } ?: ""
            "/move" -> Addons.move(context, query["i"]?.toIntOrNull() ?: -1, query["d"]?.toIntOrNull() ?: 0)
            "/toggle" -> Addons.toggle(context, query["i"]?.toIntOrNull() ?: -1)
            "/remove" -> Addons.remove(context, query["i"]?.toIntOrNull() ?: -1)
        }
        if (path != "/") main.post(onChanged)
        val body = page(message).toByteArray()
        val out = client.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun page(message: String): String {
        val rows = Addons.rows(context)
        val items = rows.mapIndexed { i, r ->
            """
            <li class="${if (r.enabled) "" else "off"}">
              <div><b>${esc(r.title)}</b><small>${esc(r.addonName)} · ${esc(r.type)}</small></div>
              <nav>
                <a href="/move?i=$i&d=-1">▲</a><a href="/move?i=$i&d=1">▼</a>
                <a href="/toggle?i=$i">${if (r.enabled) "Hide" else "Show"}</a>
                <a class="del" href="/remove?i=$i" onclick="return confirm('Remove this row?')">✕</a>
              </nav>
            </li>""".trimIndent()
        }.joinToString("\n")
        return """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>HomeHalo rows</title>
<style>
:root{color-scheme:dark}body{font:15px system-ui,sans-serif;background:#0b0f14;color:#eef2f6;margin:0;padding:16px;max-width:640px;margin:auto}
h1{font-size:22px;margin:8px 0 4px}p{color:#9aa7b4;margin:0 0 14px}
form{display:flex;gap:8px;margin:12px 0 18px}input{flex:1;min-width:0;padding:12px;border-radius:10px;border:1px solid #2a3441;background:#141b24;color:#fff;font-size:15px}
button{padding:12px 16px;border:0;border-radius:10px;background:#fff;color:#0b0f14;font-weight:600}
ul{list-style:none;padding:0;margin:0}li{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px;border-radius:12px;background:#141b24;margin-bottom:8px}
li.off{opacity:.45}li small{display:block;color:#9aa7b4;margin-top:2px}nav{display:flex;gap:6px;flex-shrink:0}
nav a{display:inline-block;padding:8px 10px;border-radius:8px;background:#222c38;color:#fff;text-decoration:none}nav a.del{background:#3a1f24}
.msg{padding:10px 12px;border-radius:10px;background:#1d2a1f;color:#bfe8c4;margin-bottom:12px}
</style></head><body>
<h1>HomeHalo · Addons &amp; rows</h1>
<p>Paste a Stremio/Nuvio addon manifest URL. Each of its catalogues becomes a row on your TV.</p>
${if (message.isNotBlank()) "<div class=\"msg\">${esc(message)}</div>" else ""}
<form action="/add"><input name="url" placeholder="https://…/manifest.json" autocomplete="off" autocapitalize="off"><button>Add</button></form>
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
    }
}
