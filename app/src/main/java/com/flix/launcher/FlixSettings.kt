package com.flix.launcher

import android.content.Context

/**
 * Every user-facing option in Flix Launcher. Each one is a list of choices stored by id;
 * on/off switches are just two-choice options. The settings screen lists
 * each one with its icon and search words.
 */
object FlixSettings {
    class Option(
        val key: String,
        val title: String,
        val choices: List<Pair<String, String>>, // id to label
        val default: String,
        val summary: String? = null,
    ) {
        fun label(id: String) = choices.firstOrNull { it.first == id }?.second ?: id
    }

    private fun toggle(key: String, title: String, default: Boolean = true, summary: String? = null) =
        Option(key, title, listOf("on" to "On", "off" to "Off"), if (default) "on" else "off", summary)

    // Look
    val THEME = Option("theme", "Theme",
        listOf("cinema" to "Cinema", "glass" to "Glass", "midnight" to "Midnight", "minimal" to "Minimal"), "cinema",
        summary = "Colours and shapes of the home screen")

    // Icons
    val ICON_SHAPE = Option("icon_shape", "Icon shape",
        listOf("circle" to "Circle", "squircle" to "Squircle", "rounded" to "Rounded square", "banner" to "Wide banner"), "circle",
        summary = "How app tiles are drawn")
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
    val WEATHER = toggle("weather", "Weather", summary = "Chip at the top right")
    val FORECAST = toggle("forecast", "3-day forecast")
    val UNITS = Option("units", "Temperature", listOf("c" to "Celsius", "f" to "Fahrenheit"), "c")

    // Wallpaper
    val WALLPAPER = Option("wallpaper", "Background", listOf(
        "nature" to "Nature photos", "cities" to "City photos",
        "video" to "All aerial videos", "v_nature" to "Landscapes video", "v_sea" to "Oceans video",
        "v_city" to "Cities video", "v_space" to "Earth from space", "v_art" to "Colour art video",
        "gradient" to "Plain dark"), "nature",
        summary = "A new Bing photo every minute, or aerial films from Apple TV and Fire TV")
    val SHUFFLE = toggle("shuffle", "Shuffle scenes")
    val DIM = Option("dim", "Background dimming", listOf("low" to "Low", "medium" to "Medium", "high" to "High"), "medium")
    val SCENE_NAME = toggle("scene_name", "Scene name")

    // Behaviour
    val IDLE = Option("idle", "Fade to scenery after",
        listOf("off" to "Never", "30" to "30 seconds", "60" to "1 minute", "120" to "2 minutes", "300" to "5 minutes"), "60")
    val ALL_APPS = toggle("all_apps", "All apps row", default = false)
    val ALL_APPS_TILE = toggle("all_apps_tile", "All apps tile", summary = "A tile at the end of favourites that opens every app")

    // Movies & shows
    val SUGGESTIONS = toggle("suggestions", "Movie & show rows", summary = "Rows from your addons; OK opens in Nuvio")
    val BILLBOARD = toggle("billboard", "Featured carousel", summary = "Big rotating posters above the rows")
    val CONTINUE_WATCHING = toggle("continue_watching", "Continue watching", summary = "From your Nuvio account")
    val GENRE = Option("genre", "Genre filter", listOf("all" to "All genres", "Action" to "Action", "Comedy" to "Comedy", "Drama" to "Drama",
        "Thriller" to "Thriller", "Horror" to "Horror", "Romance" to "Romance", "Sci-Fi" to "Sci-Fi", "Animation" to "Animation",
        "Crime" to "Crime", "Family" to "Family"), "all", summary = "Only for rows whose addon supports it")
    val POSTER_SIZE = Option("poster_size", "Poster size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), "medium")
    val BACKDROP = toggle("backdrop", "Backdrop preview", summary = "Show the focused title's artwork behind the rows")
    val TOP_TEN = Option("top_ten_from", "Top 10 row",
        listOf("mine" to "From my first row", "world" to "Worldwide (mostly English)", "off" to "Off"), "mine",
        summary = "Numbered row of the ten most popular films")
    val TRAILERS = toggle("trailers", "Play trailers", summary = "Plays behind the preview when a poster stays selected")
    val TRAILER_DELAY = Option("trailer_delay", "Trailer starts after",
        listOf("1" to "1 second", "2" to "2 seconds", "3" to "3 seconds", "5" to "5 seconds", "8" to "8 seconds"), "2",
        summary = "How long a poster stays selected before its trailer plays")
    val TRAILER_SOUND = toggle("trailer_sound_v2", "Trailer sound", summary = "Off keeps trailers silent")


    private fun prefs(context: Context) = context.getSharedPreferences("flix_settings", Context.MODE_PRIVATE)

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
