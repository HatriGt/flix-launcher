package com.homehalo.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

data class AppEntry(
    val packageName: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    val launchIntent: Intent,
    /** False when the app ships no launcher icon of its own (many TV-only apps), so tiles fall back to the banner. */
    val hasIcon: Boolean,
    /** The icon is an opaque square, so round tiles crop it to fill rather than framing it. */
    val iconFullBleed: Boolean,
    /** Dominant colour of the banner, used for the focus glow. */
    val accent: Int,
    /** The app's own category (ApplicationInfo.CATEGORY_*), for the All apps filters. */
    val category: Int = -1,
)

class AppRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("halo", Context.MODE_PRIVATE)
    private val pm: PackageManager = context.packageManager

    fun loadApps(): List<AppEntry> {
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || pkg in HIDDEN || pkg in hidden || !seen.add(pkg)) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                val appInfo = info.activityInfo.applicationInfo
                val hasIcon = pkg == SMARTTUBE || info.activityInfo.iconResource != 0
                val icon = if (pkg == SMARTTUBE) YouTubeBanner(iconOnly = true) else hiResIcon(info)
                val banner = if (pkg == SMARTTUBE) YouTubeBanner()
                else info.activityInfo.loadBanner(pm) ?: appInfo.loadBanner(pm)
                result += AppEntry(
                    packageName = pkg,
                    label = if (pkg == SMARTTUBE) "YouTube" else info.loadLabel(pm).toString(),
                    banner = banner,
                    icon = icon,
                    launchIntent = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    hasIcon = hasIcon,
                    iconFullBleed = look(pkg, appInfo, icon, banner).second,
                    accent = look(pkg, appInfo, icon, banner, hasIcon).first,
                    category = appInfo.category,
                )
            }
        }
        return result.sortedBy { it.label.lowercase() }
    }

    private val looks = context.getSharedPreferences("halo_looks", Context.MODE_PRIVATE)

    /**
     * An app's glow colour and whether its icon is a full square. Working these out
     * means drawing the icon, so the answer is kept until the app is updated.
     */
    private fun look(pkg: String, info: android.content.pm.ApplicationInfo, icon: Drawable, banner: Drawable?, hasIcon: Boolean = true): Pair<Int, Boolean> {
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "$pkg@$stamp"
        looks.getString(key, null)?.split(',')?.takeIf { it.size == 2 }?.let { return it[0].toInt() to (it[1] == "1") }
        val accent = Accent.of(if (hasIcon) icon else banner ?: icon)
        val full = Accent.isFullBleed(icon)
        looks.edit().putString(key, "$accent,${if (full) 1 else 0}").apply()
        return accent to full
    }

    /** The tile at the end of the favourites row that opens the All apps page. */
    fun allAppsTile(): AppEntry = AppEntry(
        packageName = ALL_APPS_TILE,
        label = "All apps",
        banner = null,
        icon = context.getDrawable(R.drawable.ic_apps)!!,
        launchIntent = Intent(context, AllAppsActivity::class.java),
        hasIcon = true,
        iconFullBleed = false,
        accent = 0xFF8FA6C8.toInt(),
    )

    /** The xxxhdpi icon where the app has one: the TV's own density gives blurry icons at HomeHalo's tile sizes. */
    private fun hiResIcon(info: ResolveInfo): Drawable {
        val activity = info.activityInfo
        val res = activity.iconResource
        if (res != 0) {
            runCatching {
                pm.getResourcesForApplication(activity.applicationInfo)
                    .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
            }.getOrNull()?.let { return it }
        }
        return info.loadIcon(pm)
    }

    var favorites: List<String>
        get() {
            migrateOrder()
            return prefs.getString(KEY_FAVORITES, null)?.split(',')?.filter { it.isNotBlank() }
                ?: DEFAULT_FAVORITES
        }
        set(value) = prefs.edit().putString(KEY_FAVORITES, value.joinToString(",")).apply()

    /** Puts the apps the user asked to lead the row first, keeping any other favourites after them. */
    private fun migrateOrder() {
        if (prefs.getInt(KEY_ORDER_VERSION, 0) >= ORDER_VERSION) return
        val saved = prefs.getString(KEY_FAVORITES, null)?.split(',')?.filter { it.isNotBlank() }
            ?: DEFAULT_FAVORITES
        val ordered = LEADING + saved.filter { it !in LEADING }
        prefs.edit().putString(KEY_FAVORITES, ordered.joinToString(",")).putInt(KEY_ORDER_VERSION, ORDER_VERSION).apply()
    }

    /** Apps the user chose to hide from the All apps row. */
    val hidden: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    fun hide(pkg: String) {
        prefs.edit().putStringSet(KEY_HIDDEN, hidden + pkg).apply()
        favorites = favorites - pkg
    }

    fun unhide(pkg: String) {
        prefs.edit().putStringSet(KEY_HIDDEN, hidden - pkg).apply()
    }

    fun resetOrder() {
        prefs.edit().remove(KEY_FAVORITES).remove(KEY_ORDER_VERSION).apply()
    }

    fun toggleFavorite(pkg: String) {
        favorites = if (pkg in favorites) favorites - pkg else favorites + pkg
    }

    fun moveFavorite(pkg: String, delta: Int) {
        val list = favorites.toMutableList()
        val from = list.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        favorites = list
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"
        const val ALL_APPS_TILE = "halo:all_apps"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_ORDER_VERSION = "favorites_order_version"
        private const val ORDER_VERSION = 2
        private const val SMARTTUBE = "org.smarttube.stable"
        private val LEADING = listOf("com.nuvio.tv", "ar.tvplayer.tv", SMARTTUBE)
        private val HIDDEN = setOf("com.spocky.projengmenu")
        private val DEFAULT_FAVORITES = LEADING + listOf(
            "com.stremio.one",
            "in.startv.hotstar",
            "com.sonyliv",
            "com.graymatrix.did",
            "org.videolan.vlc",
        )
    }
}
