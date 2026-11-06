package com.flix.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Keeps everything Flix stores for itself within a fixed budget, with no help from the user.
 * Runs at most every few hours while the home screen is shown:
 *  - the image cache is trimmed to its own limit (oldest unseen first, see [Images]);
 *  - the trailer player's web cache (WebView keeps it in Flix's folders) is cleared once it
 *    passes [WEB_MAX];
 *  - if the whole cache is still over [TOTAL_MAX], the oldest of our own files go until it fits.
 */
object Housekeeping {
    private const val EVERY = 6 * 3600_000L
    private const val WEB_MAX = 25L * 1024 * 1024
    private const val TOTAL_MAX = 80L * 1024 * 1024

    fun maybeRun(context: Context) {
        val p = context.getSharedPreferences("flix", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("housekeeping_at", 0) < EVERY) return
        p.edit().putLong("housekeeping_at", now).apply()
        val app = context.applicationContext
        Thread {
            runCatching { cleanUp(app) }
        }.start()
    }

    private fun cleanUp(app: Context) {
        run {
            Images.pruneNow()
            val web = listOf(File(app.cacheDir, "WebView"), File(app.dataDir, "app_webview"))
            if (web.sumOf { size(it) } > WEB_MAX) {
                // WebView must be touched on the main thread; clearing its cache is all it needs.
                Handler(Looper.getMainLooper()).post {
                    runCatching { android.webkit.WebView(app).apply { clearCache(true); destroy() } }
                }
            }
            if (size(app.cacheDir) > TOTAL_MAX) {
                var total = size(app.cacheDir)
                app.cacheDir.walkTopDown().filter { it.isFile && !it.path.contains("/WebView/") }
                    .map { it to it.lastModified() }.sortedBy { it.second }
                    .forEach { (f, _) -> if (total > TOTAL_MAX) { total -= f.length(); f.delete() } }
            }
        }
    }

    private fun size(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
