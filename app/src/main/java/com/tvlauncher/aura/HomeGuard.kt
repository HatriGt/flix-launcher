package com.tvlauncher.aura

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
 * (granted once over adb) Aura switches its Home service back on by itself.
 */
object HomeGuard {
    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    fun ensure(context: Context) {
        if (HomeKeyService.running) return
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(context, HomeKeyService::class.java).flattenToString()
        val resolver = context.contentResolver
        val others = Settings.Secure.getString(resolver, KEY).orEmpty()
            .split(':').filter { it.isNotBlank() && it != me && !it.endsWith("/.HomeKeyService") }
        runCatching {
            // Writing the list without us and then with us makes the system bind the service again.
            Settings.Secure.putString(resolver, KEY, others.joinToString(":"))
            Settings.Secure.putString(resolver, KEY, (others + me).joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /** Runs after boot and after every update of Aura. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = ensure(context)
    }
}
