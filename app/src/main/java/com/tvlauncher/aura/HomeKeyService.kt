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

    private fun openAura() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private companion object {
        const val STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx"
        const val STOCK_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity"
    }

    override fun onInterrupt() {}
}
