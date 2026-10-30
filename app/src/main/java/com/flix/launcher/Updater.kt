package com.flix.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps Flix up to date from its GitHub releases, without a trip to a browser or a
 * sideloading app.
 *
 * Twice a day [check] asks GitHub for the latest release. A newer one is downloaded in
 * the background (about 700 KB) and installed the next time the TV goes idle, or straight
 * away from Settings → About. Android itself refuses an APK that isn't signed with Flix's
 * own key, so a tampered download can't be installed.
 *
 * Installing needs "Install unknown apps" for Flix, once. On Android 12 and later an app
 * updating itself installs silently; earlier versions show Android's confirmation.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/HatriGt/flix-launcher/releases/latest"
    private const val CHECK_EVERY_MS = 12 * 3600_000L

    class Release(val version: String, val apkUrl: String, val notes: String)

    private fun prefs(context: Context) = context.getSharedPreferences("flix_update", Context.MODE_PRIVATE)

    fun installed(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "1.0.10" > "1.0.9": compares numerically, part by part. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** A downloaded newer version waiting to be installed, if any. */
    fun ready(context: Context): Release? {
        val p = prefs(context)
        val version = p.getString("ready_version", null) ?: return null
        if (!isNewer(version, installed(context)) || !apk(context).exists()) return null
        return Release(version, "", p.getString("ready_notes", "").orEmpty())
    }

    private fun apk(context: Context) = File(context.cacheDir, "update.apk")

    /** Background: looks for a newer release (at most every 12 hours unless [force]) and downloads it. */
    fun check(context: Context, force: Boolean = false): Release? {
        val p = prefs(context)
        if (!force && System.currentTimeMillis() - p.getLong("checked_at", 0) < CHECK_EVERY_MS) return ready(context)
        p.edit().putLong("checked_at", System.currentTimeMillis()).apply()
        val release = latest() ?: return null
        if (!isNewer(release.version, installed(context))) return null
        if (ready(context)?.version == release.version) return ready(context)
        download(release.apkUrl, apk(context))
        p.edit().putString("ready_version", release.version).putString("ready_notes", release.notes).apply()
        return release
    }

    private fun latest(): Release? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(SafeIo.readText(conn))
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            Release(json.optString("tag_name").trimStart('v'), apk.getString("browser_download_url"), json.optString("body"))
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        var conn = URL(url).openConnection() as HttpURLConnection
        // GitHub answers with a redirect to its file host.
        repeat(4) {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: error("Update download failed")
                conn.disconnect()
                conn = URL(next).openConnection() as HttpURLConnection
            } else {
                if (code != 200) { conn.disconnect(); error("Update download failed ($code)") }
                try {
                    SafeIo.write(to, conn.inputStream.use { SafeIo.readBytes(it, 30 * 1024 * 1024) })
                } finally { conn.disconnect() }
                return
            }
        }
        error("Update download failed")
    }

    /**
     * Whether Android will install an update without asking: only on Android 12+ and only once
     * Flix itself installed the current copy. A copy put on by adb or a sideloading app asks the
     * first time; after the user confirms that once, later updates are silent.
     */
    fun installsSilently(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        return installer == context.packageName && canInstall(context)
    }

    /** Whether Android lets Flix install packages ("Install unknown apps"). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows Flix to install updates. */
    fun askPermission(context: Context) = runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /**
     * Installs the downloaded update. Flix closes while Android replaces it; the Home
     * service reconnects and opens the new version.
     */
    fun install(context: Context): Boolean {
        val file = apk(context)
        if (!file.exists() || !canInstall(context)) return false
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        return runCatching {
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("flix.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val done = PendingIntent.getBroadcast(context, 1, Intent(context, Result::class.java), flags)
                session.commit(done.intentSender)
            }
        }.isSuccess
    }

    /** Android reports the install here; it may first need the user to confirm. */
    class Result : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let {
                        runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> apk(context).delete()
                else -> android.util.Log.w("Flix", "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }
}
