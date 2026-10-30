package com.flix.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Android drops an accessibility service when its app is updated or force-stopped,
 * which would send Home back to the stock launcher. With WRITE_SECURE_SETTINGS
 * (granted once over adb) Flix switches its Home service back on by itself.
 *
 * It only does so when the service was on and got dropped, never against the user:
 * [HomeKeyService] records "wanted" when it connects and clears it when it is unbound
 * and no longer in the system's list of enabled services (the user switched it off in
 * Settings). An update unbinds it but keeps it listed, so "wanted" stays set.
 */
object HomeGuard {
    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    fun ensure(context: Context) {
        if (HomeKeyService.running) return
        if (!wanted(context)) return
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val component = ComponentName(context, HomeKeyService::class.java)
        val me = component.flattenToString()
        val resolver = context.contentResolver
        // Drop only our own entry (it may be stored in the short ".HomeKeyService" form); keep every other app's service.
        val others = Settings.Secure.getString(resolver, KEY).orEmpty()
            .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
        runCatching {
            // Writing the list without us and then with us makes the system bind the service again.
            Settings.Secure.putString(resolver, KEY, others.joinToString(":"))
            Settings.Secure.putString(resolver, KEY, (others + me).joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /**
     * A crash takes the Home service down with Flix, and Android waits up to 30 minutes
     * before restarting it, so Home goes to the stock launcher meanwhile. Before the
     * process dies, ask for [Receiver] to run a couple of seconds later and bind it again.
     */
    fun healAfterCrash(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHeal) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHeal(previous) {
            runCatching {
                val pending = android.app.PendingIntent.getBroadcast(app, 0, Intent(app, Receiver::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                (app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                    .set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 2000, pending)
            }
        })
    }

    private class CrashHeal(val previous: Thread.UncaughtExceptionHandler?, val heal: () -> Unit) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            heal()
            previous?.uncaughtException(t, e)
        }
    }

    /** Whether the system still lists our service as enabled. */
    fun isListed(context: Context): Boolean {
        val component = ComponentName(context, HomeKeyService::class.java)
        return Settings.Secure.getString(context.contentResolver, KEY).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("flix", Context.MODE_PRIVATE)

    fun wanted(context: Context) = prefs(context).getBoolean(WANTED, false)

    fun setWanted(context: Context, on: Boolean) = prefs(context).edit().putBoolean(WANTED, on).apply()

    private const val WANTED = "home_service_wanted"

    /** Runs after boot and after every update of Flix. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = ensure(context)
    }
}
