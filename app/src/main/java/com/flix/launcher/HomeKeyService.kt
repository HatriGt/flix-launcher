package com.flix.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV sends the Home button straight to its own launcher regardless of
 * the HOME role, so this service catches the key and opens Flix instead. When
 * the key never reaches us (some remotes deliver Home below the accessibility
 * layer), the stock home screen coming to the front is caught instead.
 */
class HomeKeyService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        HomeGuard.setWanted(this, true)
        // After an update or a reboot the stock home is already in front, so no window change
        // follows and Home presses go to it unseen: bring Flix back straight away.
        if (rootInActiveWindow?.packageName == STOCK_LAUNCHER) openFlix()
    }

    /**
     * Switched off by the user means [HomeGuard] must leave it off. An update unbinds the
     * service too, but leaves it in the system's list of enabled services, so only a
     * service that is no longer listed counts as switched off.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        if (!HomeGuard.isListed(this)) HomeGuard.setWanted(this, false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) openFlix()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        foreground = event.packageName?.toString()
        if (event.packageName == STOCK_LAUNCHER &&
            event.className?.toString() == STOCK_HOME_ACTIVITY
        ) openFlix()
    }

    /** Package of the app that last came to the front, from window-state events. */
    private var foreground: String? = null
    private var lastOpenedAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recheck = Runnable {
        if (foreground == STOCK_LAUNCHER) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private fun openFlix() {
        // The stock launcher reports several window changes per Home press; open Flix once.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastOpenedAt < DEBOUNCE_MS) return
        lastOpenedAt = now
        // If the stock home still ends up in front (a press during wake-up, a race with
        // its own animation), try once more a moment later.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, RECHECK_MS)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    companion object {
        /** Lets [HomeGuard] tell whether the system currently has this service bound. */
        @Volatile
        var running = false

        private const val DEBOUNCE_MS = 700L
        private const val RECHECK_MS = 900L
        private const val STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx"
        private const val STOCK_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity"
    }

    override fun onInterrupt() {}
}
