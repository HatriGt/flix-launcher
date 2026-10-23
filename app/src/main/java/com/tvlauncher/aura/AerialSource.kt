package com.tvlauncher.aura

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlin.concurrent.thread

/**
 * Pulls wallpaper media through the Projectivy wallpaper-provider plugin
 * interface, so Aerial Views (or any other installed provider plugin) feeds
 * this launcher the same way it feeds Projectivy. Aerial Views is asked first.
 */
object AerialSource {
    private const val TAG = "AerialSource"
    private const val PREFERRED_PACKAGE = "com.neilturner.aerialviews"
    private const val ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER"
    private const val DESCRIPTOR = "tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService"
    private const val TRANSACTION_GET_WALLPAPERS = 1
    private const val EVENT_TIME_ELAPSED = 1
    private const val TYPE_IMAGE = 0

    data class Media(val uri: String, val isVideo: Boolean, val title: String?)

    fun fetch(context: Context, onResult: (List<Media>) -> Unit) {
        val app = context.applicationContext
        val providers = app.packageManager.queryIntentServices(Intent(ACTION), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .sortedBy { if (it.packageName == PREFERRED_PACKAGE) 0 else 1 }
        tryProviders(app, providers, onResult)
    }

    private fun tryProviders(app: Context, providers: List<ComponentName>, onResult: (List<Media>) -> Unit) {
        val component = providers.firstOrNull() ?: return onResult(emptyList())
        val rest = providers.drop(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                thread(name = "wallpaper-fetch") {
                    val media = runCatching { query(binder) }
                        .onFailure { Log.w(TAG, "Wallpaper query to ${name.packageName} failed", it) }
                        .getOrDefault(emptyList())
                    Log.i(TAG, "${name.packageName} returned ${media.size} items")
                    runCatching { app.unbindService(this) }
                    if (media.isNotEmpty()) onResult(media) else tryProviders(app, rest, onResult)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = runCatching {
            app.bindService(Intent(ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) tryProviders(app, rest, onResult)
    }

    private fun query(binder: IBinder): List<Media> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1) // event is non-null
            data.writeInt(EVENT_TIME_ELAPSED)
            binder.transact(TRANSACTION_GET_WALLPAPERS, data, reply, 0)
            reply.readException()
            val count = reply.readInt()
            val items = ArrayList<Media>(maxOf(count, 0))
            repeat(count) {
                if (reply.readInt() == 0) return@repeat
                val uri = reply.readString()
                val type = reply.readInt()
                reply.readInt() // displayMode
                val title = reply.readString()
                reply.readString() // source
                reply.readString() // author
                reply.readString() // actionUri
                if (!uri.isNullOrBlank()) items += Media(uri, type != TYPE_IMAGE, title)
            }
            items.firstOrNull()?.let { Log.i(TAG, "First item: $it") }
            return items
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
