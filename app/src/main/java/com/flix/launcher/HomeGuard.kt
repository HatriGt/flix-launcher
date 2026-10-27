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
 * while Flix keeps running (the user switched it off in Settings). An update or
 * force-stop kills the process instead, so no unbind is seen and "wanted" stays set.
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

    private fun prefs(context: Context) = context.getSharedPreferences("flix", Context.MODE_PRIVATE)

    fun wanted(context: Context) = prefs(context).getBoolean(WANTED, false)

    fun setWanted(context: Context, on: Boolean) = prefs(context).edit().putBoolean(WANTED, on).apply()

    private const val WANTED = "home_service_wanted"

    /** Runs after boot and after every update of Flix. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = ensure(context)
    }
}
