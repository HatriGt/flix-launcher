package com.tvlauncher.aura

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Aura's settings: a frosted two-pane panel over the right of the home screen.
 * Sections on the left, their options on the right; switches flip with OK,
 * choices step with Left/Right. The home screen behind updates live.
 */
class SettingsActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var panel: View
    private lateinit var repo: AppRepository
    private lateinit var tabs: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var sectionIndex = 0
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)

        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(30), px(12), px(24))
        }
        tabs.addView(text("Settings", 22f, bold = true).apply { setPadding(px(10), 0, 0, px(18)) })
        SECTIONS.forEachIndexed { i, (title, icon) -> tabs.addView(tab(i, title, icon)) }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(30), px(26), px(30))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabs, LinearLayout.LayoutParams(px(190), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(context).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(px(1), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        panel = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.settings_panel)
            addView(body)
            layoutParams = FrameLayout.LayoutParams(px(PANEL_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            elevation = 24 * dp
        }
        setContentView(FrameLayout(this).apply { addView(panel) })
        panel.translationX = PANEL_DP * dp
        panel.animate().translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
        showSection(0)
        tabs.getChildAt(1)?.post { tabs.getChildAt(1)?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the Nuvio or rows screens: their summary cards may have changed.
        if (created) showSection(sectionIndex)
        created = true
    }

    // ---- Left: sections ----

    private fun tab(index: Int, title: String, iconRes: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        setPadding(px(12), 0, px(10), 0)
        background = tabBackground()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(42)).apply { topMargin = px(3) }
        val icon = ImageView(context).apply { setImageResource(iconRes); setColorFilter(Color.WHITE) }
        val label = text(title, 13f)
        addView(icon, LinearLayout.LayoutParams(px(18), px(18)))
        addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(12) })
        setOnFocusChangeListener { _, has ->
            val c = if (has) DARK else Color.WHITE
            icon.setColorFilter(c)
            label.setTextColor(c)
            if (has && sectionIndex != index) showSection(index)
        }
        setOnClickListener { firstFocusable(content)?.requestFocus() }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                firstFocusable(content)?.requestFocus(); true
            } else false
        }
    }

    private fun showSection(index: Int) {
        sectionIndex = index
        for (i in 1 until tabs.childCount) tabs.getChildAt(i).isSelected = i - 1 == index
        content.removeAllViews()
        content.addView(text(SECTIONS[index].first, 18f, bold = true).apply { setPadding(px(6), 0, 0, px(10)) })
        val section = AuraSettings.SECTIONS[index]
        if (section.title == "Movies & shows") addMoviesCards()
        section.options.forEach { content.addView(optionRow(it)) }
        if (section.title == "Behaviour") {
            content.addView(caption("Apps"))
            content.addView(actionCard("Hidden apps", "Bring back apps you hid from All apps", R.drawable.ic_apps) { showHiddenApps() })
            content.addView(actionCard("Reset favourites order", "Nuvio, TiviMate, YouTube first", R.drawable.ic_tune) {
                repo.resetOrder(); applyToHome()
                Toast.makeText(this, "Favourites order reset", Toast.LENGTH_SHORT).show()
            })
        }
        content.alpha = 0f
        content.translationY = 10 * dp
        content.animate().alpha(1f).translationY(0f).setDuration(200).start()
        scroll.scrollTo(0, 0)
    }

    /** Movies & shows leads with the two things people come here for, then the display options. */
    private fun addMoviesCards() {
        val signedIn = NuvioAccount.isSignedIn(this)
        val profile = NuvioAccount.profile(this)?.name
        content.addView(actionCard(
            "Nuvio account",
            if (signedIn) "Continue watching from “${profile ?: "?"}”" else "Connect to see Continue watching",
            R.drawable.ic_settings,
            badge = if (signedIn) "Connected" else "Connect",
            badgeOn = signedIn,
        ) { open(NuvioLoginActivity::class.java) })
        val rows = Addons.rows(this)
        val shown = rows.count { it.enabled }
        val addons = rows.map { it.addonName }.distinct().size
        content.addView(actionCard(
            "Addons & rows",
            "Choose and order the rows · $addons addons",
            R.drawable.ic_apps,
            badge = "$shown on home",
            badgeOn = true,
        ) { open(RowsActivity::class.java) })
        content.addView(actionCard("Show hidden titles", "Bring back titles you hid from rows", R.drawable.ic_tune) {
            Catalog.clearHiddenTitles(this); applyToHome()
            Toast.makeText(this, "Hidden titles are back", Toast.LENGTH_SHORT).show()
        })
        content.addView(caption("Display"))
    }

    // ---- Right: options ----

    private fun isToggle(option: AuraSettings.Option) = option.choices.map { it.first } == listOf("on", "off")

    private fun optionRow(option: AuraSettings.Option): View {
        val title = text(option.title, 13f)
        val summary = option.summary?.let { text(it, 10f, alpha = 0.6f) }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            summary?.let { addView(it) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = cardBackground()
            setPadding(px(16), px(10), px(14), px(10))
            minimumHeight = px(52)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val toggle = isToggle(option)
        val switch = if (toggle) PillSwitch().also { row.addView(it.view) } else null
        val value = if (!toggle) text("", 12f, bold = true).also { row.addView(it) } else null

        fun refresh(animate: Boolean) {
            val id = AuraSettings.get(this, option)
            switch?.set(id == "on", animate, row.isFocused)
            value?.text = if (row.isFocused) "‹  ${option.label(id)}  ›" else option.label(id)
            val c = if (row.isFocused) DARK else Color.WHITE
            title.setTextColor(c); summary?.setTextColor(c); value?.setTextColor(c)
        }
        fun step(delta: Int) {
            val ids = option.choices.map { it.first }
            val index = ids.indexOf(AuraSettings.get(this, option))
            AuraSettings.set(this, option, ids[(index + delta + ids.size) % ids.size])
            refresh(animate = true)
            value?.let { v -> v.translationX = 8 * dp * delta; v.animate().translationX(0f).setDuration(160).start() }
            applyToHome()
        }
        // Steps happen on key down: the OK press that opened this screen ends here as a key up.
        row.setOnKeyListener { _, keyCode, event ->
            val select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            when {
                event.action != KeyEvent.ACTION_DOWN -> select
                select -> { step(1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!toggle) step(1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (toggle) tabs.getChildAt(sectionIndex + 1)?.requestFocus() else step(-1); true
                }
                else -> false
            }
        }
        row.setOnFocusChangeListener { _, _ -> refresh(animate = false) }
        refresh(animate = false)
        return row
    }

    /** A pill switch whose knob slides when flipped. */
    private inner class PillSwitch {
        private val knobShape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        private val knob = View(this@SettingsActivity).apply { background = knobShape }
        private val track = GradientDrawable().apply { cornerRadius = 12 * dp }
        val view = FrameLayout(this@SettingsActivity).apply {
            background = track
            addView(knob, FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER_VERTICAL).apply { marginStart = px(3) })
            layoutParams = LinearLayout.LayoutParams(px(42), px(24)).apply { marginStart = px(12) }
        }

        fun set(on: Boolean, animate: Boolean, focused: Boolean) {
            track.setColor(if (on) ON else if (focused) 0x40000000 else 0x40FFFFFF)
            knobShape.setColor(if (!on && focused) DARK else Color.WHITE)
            val x = if (on) 18 * dp else 0f
            if (animate) knob.animate().translationX(x).setDuration(160).start() else knob.translationX = x
        }
    }

    private fun actionCard(title: String, subtitle: String, iconRes: Int, badge: String? = null, badgeOn: Boolean = false, onClick: () -> Unit): View {
        val icon = ImageView(this).apply { setImageResource(iconRes); setColorFilter(Color.WHITE) }
        val iconBg = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(0x26FFFFFF) }
        val iconWrap = FrameLayout(this).apply {
            background = iconBg
            addView(icon, FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))
        }
        val t = text(title, 13f, bold = true)
        val s = text(subtitle, 10f, alpha = 0.65f)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t); addView(s) }
        val badgeView = badge?.let {
            text(it, 10f, bold = true).apply {
                setPadding(px(9), px(3), px(9), px(3))
                background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(if (badgeOn) ON else 0x33FFFFFF) }
            }
        }
        val chevron = text("›", 18f, alpha = 0.6f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = cardBackground()
            setPadding(px(12), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) }
            addView(iconWrap, LinearLayout.LayoutParams(px(36), px(36)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) })
            badgeView?.let { addView(it) }
            addView(chevron, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
            setOnFocusChangeListener { _, has ->
                val c = if (has) DARK else Color.WHITE
                t.setTextColor(c); s.setTextColor(c); chevron.setTextColor(c); icon.setColorFilter(c)
                iconBg.setColor(if (has) 0x1A000000 else 0x26FFFFFF)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun caption(label: String) = text(label.uppercase(), 10f, bold = true, alpha = 0.5f).apply {
        letterSpacing = 0.14f
        setPadding(px(6), px(18), 0, px(2))
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
        overridePendingTransition(0, 0)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Tap an app to show it again")
            .setItems(labels.toTypedArray()) { _, which ->
                repo.unhide(hidden[which])
                applyToHome()
            }
            .show()
    }

    private fun applyToHome() {
        sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName))
    }

    override fun finish() {
        if (panel.translationX != 0f) return super.finish()
        panel.animate().translationX(PANEL_DP * dp).setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { super.finish(); overridePendingTransition(0, 0) }.start()
    }

    private fun firstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) if (group.getChildAt(i).isFocusable) return group.getChildAt(i)
        return null
    }

    private fun cardBackground() = StateListDrawable().apply {
        setEnterFadeDuration(120); setExitFadeDuration(120)
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xF2FFFFFF.toInt()) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x14FFFFFF) })
    }

    private fun tabBackground() = StateListDrawable().apply {
        setEnterFadeDuration(120); setExitFadeDuration(120)
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xF2FFFFFF.toInt()) })
        addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0x26FFFFFF) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.TRANSPARENT) })
    }

    private fun text(value: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun px(value: Int) = (value * dp).toInt()

    companion object {
        const val ACTION_CHANGED = "com.tvlauncher.aura.SETTINGS_CHANGED"
        private const val DARK = 0xFF101418.toInt()
        private const val ON = 0xFF2E7DF6.toInt()
        private const val PANEL_DP = 620
        private val SECTIONS = listOf(
            "Icons" to R.drawable.ic_apps,
            "Clock & widgets" to R.drawable.ic_settings,
            "Movies & shows" to R.drawable.ic_input,
            "Background" to R.drawable.ic_wifi,
            "Behaviour" to R.drawable.ic_tune,
        )
    }
}
