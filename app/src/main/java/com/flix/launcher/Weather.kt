package com.flix.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Current conditions and a 3-day outlook from wttr.in; no API key needed. Without a [city]
 * wttr.in locates the TV by its public IP, which can land on the internet provider's hub.
 */
object Weather {
    data class Day(val name: String, val icon: String, val high: Int, val low: Int)

    data class Report(
        val icon: String,
        val temperature: Int,
        val feelsLike: Int,
        val condition: String,
        val humidity: Int,
        val windKmh: Int,
        val place: String,
        val days: List<Day>,
        val sunrise: java.util.Date?,
        val sunset: java.util.Date?,
    )

    fun fetch(city: String = ""): Report? = runCatching {
        val place = java.net.URLEncoder.encode(city.trim(), "UTF-8").replace("+", "%20")
        val conn = URL("https://wttr.in/$place?format=j1").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "curl/8")
        val json = try {
            JSONObject(SafeIo.readText(conn))
        } finally {
            conn.disconnect()
        }
        val now = json.getJSONArray("current_condition").getJSONObject(0)
        val area = json.optJSONArray("nearest_area")?.optJSONObject(0)
            ?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value").orEmpty()
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        val forecast = json.getJSONArray("weather")
        val days = (0 until forecast.length()).map { i ->
            val day = forecast.getJSONObject(i)
            val midday = day.getJSONArray("hourly").let { it.getJSONObject(minOf(4, it.length() - 1)) }
            Day(
                name = if (i == 0) "Today" else dayName.format(parse.parse(day.getString("date"))!!),
                icon = icon(midday.getInt("weatherCode")),
                high = day.getInt("maxtempC"),
                low = day.getInt("mintempC"),
            )
        }
        val today = forecast.getJSONObject(0)
        val astronomy = today.optJSONArray("astronomy")?.optJSONObject(0)
        // wttr.in gives bare clock times ("06:42 AM"); parsed alone they'd land on 1 Jan 1970,
        // so they're joined to the forecast's own date to compare correctly with now.
        val clock = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US)
        val date = today.optString("date").ifBlank { parse.format(java.util.Date()) }
        fun sunTime(key: String) = astronomy?.optString(key)?.let { runCatching { clock.parse("$date $it") }.getOrNull() }
        Report(
            icon = icon(now.getInt("weatherCode")),
            temperature = now.getInt("temp_C"),
            feelsLike = now.getInt("FeelsLikeC"),
            condition = now.getJSONArray("weatherDesc").getJSONObject(0).getString("value").trim(),
            humidity = now.getInt("humidity"),
            windKmh = now.getInt("windspeedKmph"),
            place = area,
            days = days,
            sunrise = sunTime("sunrise"),
            sunset = sunTime("sunset"),
        )
    }.getOrNull()

    /** Maps WorldWeatherOnline condition codes (used by wttr.in) to an emoji. */
    private fun icon(code: Int): String = when (code) {
        113 -> "☀️"
        116 -> "⛅"
        119, 122 -> "☁️"
        143, 248, 260 -> "🌫️"
        149 -> "🌫️"
        176, 263, 266, 293, 296, 353 -> "🌦️"
        299, 302, 305, 308, 356, 359 -> "🌧️"
        200, 386, 389, 392, 395 -> "⛈️"
        else -> if (code in 179..377) "🌨️" else "🌤️"
    }
}
