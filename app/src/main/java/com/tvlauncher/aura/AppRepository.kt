package com.tvlauncher.aura

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    val launchIntent: Intent,
    /** Dominant colour of the banner, used for the focus glow. */
    val accent: Int,
)

class AppRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("aura", Context.MODE_PRIVATE)
    private val pm: PackageManager = context.packageManager

    fun loadApps(): List<AppEntry> {
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || pkg in HIDDEN || !seen.add(pkg)) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                val appInfo = info.activityInfo.applicationInfo
                val icon = info.loadIcon(pm)
                val banner = if (pkg == SMARTTUBE) YouTubeBanner()
                else info.activityInfo.loadBanner(pm) ?: appInfo.loadBanner(pm)
                result += AppEntry(
                    packageName = pkg,
                    label = if (pkg == SMARTTUBE) "YouTube" else info.loadLabel(pm).toString(),
                    banner = banner,
                    icon = icon,
                    launchIntent = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    accent = Accent.of(banner ?: icon),
                )
            }
        }
        return result.sortedBy { it.label.lowercase() }
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
