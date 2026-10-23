package com.tvlauncher.aura

import android.content.Context

/**
 * Every user-facing option in Aura. Each one is a list of choices stored by id;
 * on/off switches are just two-choice options. The settings screen is built
 * from [SECTIONS], so adding an option here is all it takes to expose it.
 */
object AuraSettings {
    class Option(
        val key: String,
        val title: String,
        val choices: List<Pair<String, String>>, // id to label
        val default: String,
        val summary: String? = null,
    ) {
        fun label(id: String) = choices.firstOrNull { it.first == id }?.second ?: id
    }

    class Section(val title: String, val options: List<Option>, val actions: List<String> = emptyList())

    private fun toggle(key: String, title: String, default: Boolean = true, summary: String? = null) =
        Option(key, title, listOf("on" to "On", "off" to "Off"), if (default) "on" else "off", summary)

    // Icons
    val ICON_SHAPE = Option("icon_shape", "Icon shape",
        listOf("circle" to "Circle", "squircle" to "Squircle", "rounded" to "Rounded square", "banner" to "Wide banner"), "circle")
    val ICON_SIZE = Option("icon_size", "Icon size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), "medium")
    val LABELS = Option("labels", "App names", listOf("always" to "Always", "focus" to "When selected", "never" to "Hidden"), "always")
    val GLOW = toggle("glow", "Colour glow", summary = "Selected apps glow in their own colour")
    val MOTION = Option("motion", "Focus animation", listOf("spring" to "Springy", "smooth" to "Smooth", "none" to "Off"), "spring")

    // Clock & widgets
    val CLOCK_STYLE = Option("clock_style", "Clock style", listOf("duo" to "Duo", "thin" to "Thin", "bold" to "Bold"), "duo")
    val CLOCK_FORMAT = Option("clock_format", "Time format", listOf("system" to "System", "12" to "12-hour", "24" to "24-hour"), "system")
    val GREETING = toggle("greeting", "Greeting")
    val DATE = toggle("date", "Date")
    val SUN_ARC = toggle("sun_arc", "Sunrise & sunset arc")
    val WEATHER = toggle("weather", "Weather card")
    val FORECAST = toggle("forecast", "3-day forecast")
    val UNITS = Option("units", "Temperature", listOf("c" to "Celsius", "f" to "Fahrenheit"), "c")

    // Wallpaper
    val WALLPAPER = Option("wallpaper", "Background", listOf("video" to "Scenic video", "gradient" to "Plain gradient"), "video")
    val SHUFFLE = toggle("shuffle", "Shuffle scenes")
    val DIM = Option("dim", "Background dimming", listOf("low" to "Low", "medium" to "Medium", "high" to "High"), "medium")
    val SCENE_NAME = toggle("scene_name", "Scene name")

    // Behaviour
    val IDLE = Option("idle", "Fade to scenery after",
        listOf("off" to "Never", "30" to "30 seconds", "60" to "1 minute", "120" to "2 minutes", "300" to "5 minutes"), "60")
    val ALL_APPS = toggle("all_apps", "All apps row")
    val ALL_APPS_TILE = toggle("all_apps_tile", "All apps tile", summary = "A tile at the end of favourites that opens every app")

    // Movies & shows
    val SUGGESTIONS = toggle("suggestions", "Movie & show rows", summary = "Suggestions that open in Nuvio")
    val ROW_POPULAR_MOVIES = toggle("row_popular_movies", "Popular movies")
    val ROW_POPULAR_SERIES = toggle("row_popular_series", "Popular series")
    val ROW_TOP_RATED = toggle("row_top_rated", "Top rated movies")
    val GENRE = Option("genre", "Genre", listOf("all" to "All genres", "Action" to "Action", "Comedy" to "Comedy", "Drama" to "Drama",
        "Thriller" to "Thriller", "Horror" to "Horror", "Romance" to "Romance", "Sci-Fi" to "Sci-Fi", "Animation" to "Animation",
        "Crime" to "Crime", "Family" to "Family"), "all")
    val POSTER_SIZE = Option("poster_size", "Poster size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), "medium")
    val BACKDROP = toggle("backdrop", "Backdrop preview", summary = "Show the focused title's artwork behind the rows")

    const val ACTION_SHOW_TITLES = "Show hidden titles"
    const val ACTION_HIDDEN = "Hidden apps"
    const val ACTION_RESET = "Reset favourites order"

    val SECTIONS = listOf(
        Section("Icons", listOf(ICON_SHAPE, ICON_SIZE, LABELS, GLOW, MOTION)),
        Section("Clock & widgets", listOf(CLOCK_STYLE, CLOCK_FORMAT, GREETING, DATE, SUN_ARC, WEATHER, FORECAST, UNITS)),
        Section("Movies & shows", listOf(SUGGESTIONS, ROW_POPULAR_MOVIES, ROW_POPULAR_SERIES, ROW_TOP_RATED, GENRE, POSTER_SIZE, BACKDROP),
            listOf(ACTION_SHOW_TITLES)),
        Section("Background", listOf(WALLPAPER, SHUFFLE, DIM, SCENE_NAME)),
        Section("Behaviour", listOf(IDLE, ALL_APPS, ALL_APPS_TILE), listOf(ACTION_HIDDEN, ACTION_RESET)),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)

    fun get(context: Context, option: Option): String =
        prefs(context).getString(option.key, null)?.takeIf { id -> option.choices.any { it.first == id } } ?: option.default

    fun isOn(context: Context, option: Option) = get(context, option) == "on"

    fun set(context: Context, option: Option, id: String) {
        val p = prefs(context)
        p.edit().putString(option.key, id).putInt(KEY_REVISION, p.getInt(KEY_REVISION, 0) + 1).apply()
    }

    /** Bumped on every change, so the home screen knows when to re-apply. */
    fun revision(context: Context) = prefs(context).getInt(KEY_REVISION, 0)

    private const val KEY_REVISION = "revision"
}
