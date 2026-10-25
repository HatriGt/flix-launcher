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

    /**
     * Every launchable app, sorted by name. Apps in [first] are built before the rest and
     * handed to [onFirst], so the favourites row can show while the other icons are drawn.
     * Built tiles are kept per app version, so later reloads only list packages.
     */
    fun loadApps(first: Collection<String> = emptyList(), onFirst: ((List<AppEntry>) -> Unit)? = null): List<AppEntry> {
        val seen = HashSet<String>()
        val found = ArrayList<ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || pkg in HIDDEN || pkg in hidden || !seen.add(pkg)) continue
                found += info
            }
        }
        val (early, late) = found.partition { it.activityInfo.packageName in first }
        val result = ArrayList<AppEntry>()
        early.mapNotNullTo(result, ::entry)
        onFirst?.invoke(result.toList())
        late.mapNotNullTo(result, ::entry)
        return result.sortedBy { it.label.lowercase() }
    }

    private fun entry(info: ResolveInfo): AppEntry? {
        val pkg = info.activityInfo.packageName
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        built[pkg]?.takeIf { it.first == stamp }?.let { return it.second }
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: return null
        val appInfo = info.activityInfo.applicationInfo
        val hasIcon = pkg == SMARTTUBE || info.activityInfo.iconResource != 0
        val icon = if (pkg == SMARTTUBE) YouTubeBanner(iconOnly = true) else hiResIcon(info)
        val banner = if (pkg == SMARTTUBE) YouTubeBanner()
        else info.activityInfo.loadBanner(pm) ?: appInfo.loadBanner(pm)
        val (accent, fullBleed) = look(pkg, appInfo, icon, banner, hasIcon)
        val app = AppEntry(
            packageName = pkg,
            label = if (pkg == SMARTTUBE) "YouTube" else info.loadLabel(pm).toString(),
            banner = banner?.let { compact(it, BANNER_W, BANNER_H) },
            icon = compact(icon, ICON_PX, ICON_PX),
            launchIntent = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            hasIcon = hasIcon,
            iconFullBleed = fullBleed,
            accent = accent,
            category = appInfo.category,
        )
        built[pkg] = stamp to app
        return app
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

    /**
     * Redraws an icon or banner once at the largest size a tile shows it, into a GPU-side
     * bitmap. The app's own artwork (xxxhdpi adaptive layers are 432 px squares, twice over)
     * is then dropped, which took the launcher's native memory from ~54 MB to a few.
     * Adaptive icons stay adaptive, with each layer redrawn, so tiles still mask them their way.
     */
    private fun compact(d: Drawable, w: Int, h: Int): Drawable = when (d) {
        is YouTubeBanner -> d
        is android.graphics.drawable.AdaptiveIconDrawable -> android.graphics.drawable.AdaptiveIconDrawable(
            d.background?.let { raster(it, w, h) }, d.foreground?.let { raster(it, w, h) })
        else -> raster(d, w, h)
    }

    private fun raster(d: Drawable, w: Int, h: Int): Drawable {
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val old = android.graphics.Rect(d.bounds)
        d.setBounds(0, 0, w, h)
        d.draw(android.graphics.Canvas(bitmap))
        d.bounds = old
        val gpu = bitmap.copy(android.graphics.Bitmap.Config.HARDWARE, false)
        if (gpu != null) bitmap.recycle()
        return android.graphics.drawable.BitmapDrawable(context.resources, gpu ?: bitmap)
    }

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
        // Biggest tile: ~74 dp icons zoomed 1.42x for adaptive layers, 192 dp banners, at density 2.
        private const val ICON_PX = 224

        /** Tiles already drawn, by package, with the app version they were drawn from. */
        private val built = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AppEntry>>()
        private const val BANNER_W = 384
        private const val BANNER_H = 216
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
