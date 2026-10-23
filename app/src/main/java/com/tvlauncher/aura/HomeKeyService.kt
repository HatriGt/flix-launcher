package com.tvlauncher.aura

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV sends the Home button straight to its own launcher regardless of
 * the HOME role, so this service catches the key and opens Aura instead. When
 * the key never reaches us (some remotes deliver Home below the accessibility
 * layer), the stock home screen coming to the front is caught instead.
 */
class HomeKeyService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        // After a reboot or an update the stock home may already be on screen; replace it right away.
        if (rootInActiveWindow?.packageName == STOCK_LAUNCHER) openAura()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) openAura()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == STOCK_LAUNCHER &&
            event.className?.toString() == STOCK_HOME_ACTIVITY
        ) openAura()
    }

    private var lastOpenedAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recheck = Runnable {
        if (rootInActiveWindow?.packageName == STOCK_LAUNCHER) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private fun openAura() {
        // The stock launcher reports several window changes per Home press; open Aura once.
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
