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

    /**
     * [force] re-binds even when the service says it is running: right after an update the
     * system can bind the new copy and then mark it crashed, so it stops getting the Home key
     * while [HomeKeyService.running] still reads true.
     */
    fun ensure(context: Context, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val skip = (HomeKeyService.running && !force) || !wanted(context) ||
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED
        if (skip) { onDone?.invoke(); return }
        val component = ComponentName(context, HomeKeyService::class.java)
        val me = component.flattenToString()
        val resolver = context.contentResolver
        // Drop only our own entry (it may be stored in the short ".HomeKeyService" form); keep every other app's service.
        val others = Settings.Secure.getString(resolver, KEY).orEmpty()
            .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
        // The unbind this causes must not read as the user switching the service off.
        prefs(context).edit().putLong(REBINDING, android.os.SystemClock.elapsedRealtime()).apply()
        // Writing the list without us and then with us makes the system bind the service again.
        // The two writes need a pause between them: back to back, the system only sees the
        // final list, which is unchanged, and leaves a service it marked crashed alone.
        runCatching { Settings.Secure.putString(resolver, KEY, others.joinToString(":").ifEmpty { null }) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                Settings.Secure.putString(resolver, KEY, (others + me).joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            onDone?.invoke()
        }, REBIND_GAP_MS)
    }

    private const val REBIND_GAP_MS = 1500L

    /**
     * A crash takes the Home service down with Flix, and Android waits up to 30 minutes
     * before restarting it, so Home goes to the stock launcher meanwhile. Before the
     * process dies, ask for [Receiver] to run a couple of seconds later and bind it again.
     */
    fun healAfterCrash(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHeal) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHeal(previous) { ensureLater(app, 2000, 0) })
    }

    /** Runs [ensure] again after [delayMs]; each [requestCode] is its own pending alarm. */
    private fun ensureLater(context: Context, delayMs: Long, requestCode: Int, force: Boolean = false) = runCatching {
        val pending = android.app.PendingIntent.getBroadcast(context, requestCode, Intent(context, Receiver::class.java).putExtra(FORCE, force),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + delayMs, pending)
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
    private const val REBINDING = "home_service_rebinding"
    private const val FORCE = "force"

    /** True for a few seconds after Flix itself re-bound the service. */
    fun rebinding(context: Context) =
        android.os.SystemClock.elapsedRealtime() - prefs(context).getLong(REBINDING, -60_000) < 10_000

    /** Runs after boot and after every update of Flix. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Keep the receiver alive through the pause between the two writes.
            val result = goAsync()
            ensure(context, intent.getBooleanExtra(FORCE, false)) { result.finish() }
            // Right after an update the system may still be tearing down the old binding and
            // mark the fresh one as crashed, leaving Home on the stock launcher while the
            // service believes it is running. Bind it afresh once things have settled.
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                ensureLater(context, 8000, 1, force = true)
                ensureLater(context, 25000, 2, force = true)
            } else if (intent.action != null) {
                ensureLater(context, 6000, 1)
                ensureLater(context, 20000, 2)
            }
        }
    }
}
