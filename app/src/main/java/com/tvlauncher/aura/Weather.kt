package com.tvlauncher.aura

import java.net.HttpURLConnection
import java.net.URL

/** Current conditions from wttr.in, which locates the TV by its public IP; no API key needed. */
object Weather {
    data class Now(val icon: String, val temperature: String, val place: String)

    fun fetch(): Now? = runCatching {
        val conn = URL("https://wttr.in/?format=%c|%t|%l").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "curl/8")
        try {
            val parts = conn.inputStream.bufferedReader().readText().trim().split('|')
            if (parts.size < 3) return null
            Now(
                icon = parts[0].trim(),
                temperature = parts[1].trim().removePrefix("+"),
                place = parts[2].substringBefore(',').trim().replaceFirstChar { it.uppercase() },
            )
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
