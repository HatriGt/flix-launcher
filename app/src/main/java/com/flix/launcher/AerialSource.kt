package com.flix.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
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
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val QUERY_TIMEOUT_MS = 15_000L

    data class Media(val uri: String, val isVideo: Boolean, val title: String?)

    /** Asks each provider in turn; [onResult] is called exactly once, on the main thread. */
    fun fetch(context: Context, onResult: (List<Media>) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val deliver = { media: List<Media> -> if (delivered.compareAndSet(false, true)) main.post { onResult(media) } }
        val providers = runCatching {
            app.packageManager.queryIntentServices(Intent(ACTION), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
                .sortedBy { if (it.packageName == PREFERRED_PACKAGE) 0 else 1 }
        }.getOrDefault(emptyList())
        Attempt(app, main, providers, deliver).start()
    }

    /**
     * One provider's turn. It ends exactly once (answer, failure, dead or null binding, or
     * timeout), always unbinds, and then either delivers or hands over to the next provider.
     * Without the timeout a provider that binds but never connects would wedge the source
     * and keep its binding forever.
     */
    private class Attempt(
        private val app: Context,
        private val main: Handler,
        private val providers: List<ComponentName>,
        private val deliver: (List<Media>) -> Unit,
    ) : ServiceConnection {
        private val ended = AtomicBoolean(false)
        private val timeout = Runnable { Log.w(TAG, "${providers.first().packageName} timed out"); end(emptyList()) }

        fun start() {
            val component = providers.firstOrNull() ?: return deliver(emptyList())
            main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
            val bound = runCatching {
                app.bindService(Intent(ACTION).setComponent(component), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) end(emptyList())
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // Connected: give the query itself longer, but not forever (a hung transact).
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, QUERY_TIMEOUT_MS)
            thread(name = "wallpaper-fetch") {
                val media = runCatching { query(binder) }
                    .onFailure { Log.w(TAG, "Wallpaper query to ${name.packageName} failed", it) }
                    .getOrDefault(emptyList())
                Log.i(TAG, "${name.packageName} returned ${media.size} items")
                end(media)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {}

        override fun onBindingDied(name: ComponentName) = end(emptyList())

        override fun onNullBinding(name: ComponentName) = end(emptyList())

        private fun end(media: List<Media>) {
            if (!ended.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            // Unbind even when bindService returned false: the system may still hold the connection.
            runCatching { app.unbindService(this) }
            if (media.isNotEmpty()) deliver(media) else Attempt(app, main, providers.drop(1), deliver).start()
        }
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
