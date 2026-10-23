package com.tvlauncher.aura

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Aura's own settings: a frosted panel that slides over the right of the home
 * screen. The home screen stays visible behind it, so every change shows live.
 * OK or Right steps an option forward, Left steps it back.
 */
class SettingsActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var panel: View
    private lateinit var repo: AppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(26), px(28), px(26), px(28))
        }
        list.addView(text("Aura settings", 22f, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
        list.addView(text("Changes apply instantly", 11f, alpha = 0.6f).apply { setPadding(0, px(2), 0, px(8)) })

        var first: View? = null
        for (section in AuraSettings.SECTIONS) {
            list.addView(text(section.title.uppercase(), 10f, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0.55f).apply {
                letterSpacing = 0.14f
                setPadding(px(4), px(18), 0, px(6))
            })
            for (option in section.options) {
                val row = optionRow(option)
                if (first == null) first = row
                list.addView(row)
            }
            for (action in section.actions) list.addView(actionRow(action))
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            addView(list)
        }
        panel = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.settings_panel)
            addView(scroll)
            layoutParams = FrameLayout.LayoutParams(px(380), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            elevation = 24 * dp
        }
        setContentView(FrameLayout(this).apply { addView(panel) })

        panel.translationX = 380 * dp
        panel.animate().translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
        first?.post { first?.requestFocus() }
    }

    private fun optionRow(option: AuraSettings.Option): View {
        val value = text("", 12f, alpha = 0.9f).apply { gravity = Gravity.END }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setBackgroundResource(R.drawable.menu_item)
            setPadding(px(14), 0, px(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)).apply { topMargin = px(2) }
            addView(text(option.title, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(value)
        }
        fun refresh(animate: Int = 0) {
            val label = option.label(AuraSettings.get(this, option))
            val shown = if (row.isFocused) "‹  $label  ›" else label
            if (animate == 0) {
                value.text = shown
                return
            }
            value.animate().cancel()
            value.translationX = 10 * dp * animate
            value.alpha = 0.2f
            value.text = shown
            value.animate().translationX(0f).alpha(0.9f).setDuration(180).start()
        }
        fun step(delta: Int) {
            val ids = option.choices.map { it.first }
            val index = ids.indexOf(AuraSettings.get(this, option))
            AuraSettings.set(this, option, ids[(index + delta + ids.size) % ids.size])
            refresh(delta)
            applyToHome()
        }
        row.setOnClickListener { step(1) }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                else -> false
            }
        }
        row.setOnFocusChangeListener { _, _ ->
            refresh()
            val titleView = row.getChildAt(0) as TextView
            val color = if (row.isFocused) DARK else Color.WHITE
            titleView.setTextColor(color)
            value.setTextColor(color)
        }
        refresh()
        return row
    }

    private fun actionRow(title: String): View = text(title, 13f).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.menu_item)
        setTextColor(getColorStateList(R.color.menu_text))
        setPadding(px(14), 0, px(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)).apply { topMargin = px(2) }
        setOnClickListener {
            when (title) {
                AuraSettings.ACTION_HIDDEN -> showHiddenApps()
                AuraSettings.ACTION_RESET -> {
                    repo.resetOrder()
                    applyToHome()
                    Toast.makeText(this@SettingsActivity, "Favourites order reset", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Lists hidden apps; picking one brings it back to the All apps row. */
    private fun showHiddenApps() {
        val hidden = repo.hidden.toList()
        if (hidden.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = hidden.map { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Tap an app to show it again")
            .setItems(labels.toTypedArray()) { _, which ->
                repo.unhide(hidden[which])
                applyToHome()
            }
            .show()
    }

    /** Touching any setting bumps the revision, which the home screen picks up when it resumes. */
    private fun applyToHome() {
        sendBroadcast(android.content.Intent(ACTION_CHANGED).setPackage(packageName))
    }

    override fun finish() {
        if (panel.translationX != 0f) return super.finish()
        panel.animate().translationX(380 * dp).setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { super.finish(); overridePendingTransition(0, 0) }.start()
    }

    private fun text(value: String, size: Float, face: Typeface? = null, alpha: Float = 1f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        face?.let { typeface = it }
    }

    private fun px(value: Int) = (value * dp).toInt()

    companion object {
        const val ACTION_CHANGED = "com.tvlauncher.aura.SETTINGS_CHANGED"
        private const val DARK = 0xFF101418.toInt()
    }
}
