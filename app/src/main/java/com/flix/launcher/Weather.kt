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

    /** wttr.in first; it is often down or slow, so Open-Meteo answers when it doesn't. */
    fun fetch(city: String = ""): Report? = wttr(city) ?: openMeteo(city)

    private fun getJson(url: String, connect: Int = 6000, read: Int = 8000): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connect
        conn.readTimeout = read
        conn.setRequestProperty("User-Agent", "curl/8")
        return try { JSONObject(SafeIo.readText(conn)) } finally { conn.disconnect() }
    }

    private fun wttr(city: String): Report? = runCatching {
        val place = java.net.URLEncoder.encode(city.trim(), "UTF-8").replace("+", "%20")
        val json = getJson("https://wttr.in/$place?format=j1", 4000, 5000)
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

    /**
     * Open-Meteo needs coordinates: the typed city goes through its geocoder, and without
     * one the TV is placed by its public IP (ipwho.is), the same guess wttr.in makes.
     */
    private fun openMeteo(city: String): Report? = runCatching {
        val enc = java.net.URLEncoder.encode(city.trim(), "UTF-8").replace("+", "%20")
        val (lat, lon, place) = if (city.isNotBlank()) {
            val hit = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$enc&count=1").getJSONArray("results").getJSONObject(0)
            Triple(hit.getDouble("latitude"), hit.getDouble("longitude"), hit.optString("name"))
        } else {
            val ip = getJson("https://ipwho.is/")
            Triple(ip.getDouble("latitude"), ip.getDouble("longitude"), ip.optString("city"))
        }
        val json = getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto&forecast_days=3")
        val now = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        val days = (0 until dates.length()).map { i ->
            Day(
                name = if (i == 0) "Today" else dayName.format(parse.parse(dates.getString(i))!!),
                icon = wmoIcon(daily.getJSONArray("weather_code").getInt(i)),
                high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
            )
        }
        // Sunrise and sunset are local times at the place, which is where the TV is.
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        fun sun(key: String) = daily.optJSONArray(key)?.optString(0)?.let { runCatching { iso.parse(it) }.getOrNull() }
        val code = now.getInt("weather_code")
        Report(
            icon = wmoIcon(code),
            temperature = Math.round(now.getDouble("temperature_2m")).toInt(),
            feelsLike = Math.round(now.getDouble("apparent_temperature")).toInt(),
            condition = wmoText(code),
            humidity = now.getInt("relative_humidity_2m"),
            windKmh = Math.round(now.getDouble("wind_speed_10m")).toInt(),
            place = place,
            days = days,
            sunrise = sun("sunrise"),
            sunset = sun("sunset"),
        )
    }.getOrNull()

    /** WMO weather codes, as Open-Meteo reports them. */
    private fun wmoIcon(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57, 80 -> "🌦️"
        61, 63, 65, 66, 67, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌤️"
    }

    private fun wmoText(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 66, 80, 81 -> "Rain"
        65, 67, 82 -> "Heavy rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        else -> "Fair"
    }

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
