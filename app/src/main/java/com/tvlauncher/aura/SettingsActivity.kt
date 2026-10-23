package com.tvlauncher.aura

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Aura's settings. The home screen blurs behind a floating panel; sections sit
 * in a rail of colour-coded tiles and their options in grouped cards, with
 * switches for on/off and segmented controls for short lists of choices.
 * Every change applies to the home screen behind as it is made.
 */
class SettingsActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var panel: View
    private lateinit var repo: AppRepository
    private lateinit var rail: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var sectionIndex = 0
    private var created = false

    private val accent get() = SECTIONS[sectionIndex].color

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        blurBehind()

        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(26), px(14), px(20))
        }
        rail.addView(brand())
        SECTIONS.forEachIndexed { i, s -> rail.addView(railItem(i, s)) }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(text("Changes apply instantly", 10f, alpha = 0.45f).apply { setPadding(px(10), 0, 0, 0) })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(30), px(30), px(36))
            clipChildren = false
            clipToPadding = false
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipChildren = false
            addView(content)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rail, LinearLayout.LayoutParams(px(214), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        panel = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xF2161D28.toInt(), 0xF50C1118.toInt())).apply {
                cornerRadii = floatArrayOf(28 * dp, 28 * dp, 0f, 0f, 0f, 0f, 28 * dp, 28 * dp)
                setStroke(px(1), 0x1FFFFFFF)
            }
            addView(body)
            elevation = 40 * dp
        }
        setContentView(FrameLayout(this).apply {
            addView(panel, FrameLayout.LayoutParams(px(PANEL_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        })

        // Panel glides in with a soft settle; the content follows a beat later.
        panel.translationX = PANEL_DP * dp * 0.35f
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(OvershootInterpolator(0.6f)).start()
        showSection(0, stagger = true)
        rail.getChildAt(1)?.post { rail.getChildAt(1)?.requestFocus() }
    }

    /** Blurs the home screen behind the panel where the TV supports it, and dims it otherwise. */
    private fun blurBehind() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.45f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = px(40) }
            window.setDimAmount(0.25f)
        }
    }

    /**
     * Up/Down stays inside the pane it started in: running off the end of the
     * options shouldn't land on a section and switch it, and vice versa.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
            val focused = currentFocus
            if (focused != null) {
                val pane = if (isInside(focused, rail)) rail else if (isInside(focused, content)) content else null
                val next = focused.focusSearch(if (code == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP)
                if (pane != null && (next == null || !isInside(next, pane))) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSection = -1

    override fun onResume() {
        super.onResume()
        // Coming back from the Nuvio or rows screens: their summary cards may have changed.
        if (created) showSection(sectionIndex, stagger = false)
        created = true
    }

    // ---- Rail ----

    /** Wordmark: "lumo" set light with a small gradient dot, the way the brand reads everywhere. */
    private fun brand(): View {
        val word = TextView(this).apply {
            text = "lumo"
            textSize = 24f
            letterSpacing = 0.02f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val dot = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7AA2FF.toInt(), 0xFFC084FC.toInt())).apply {
                shape = GradientDrawable.OVAL
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), 0, 0, px(24))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(word)
                addView(dot, LinearLayout.LayoutParams(px(7), px(7)).apply { marginStart = px(3); bottomMargin = px(7) })
            })
            addView(text("Settings", 11f, alpha = 0.5f).apply { letterSpacing = 0.12f; isAllCaps = true })
        }
    }

    private fun railItem(index: Int, section: Section): View {
        val tileBg = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(tint(section.color, 0.18f)) }
        val icon = ImageView(this).apply { setImageResource(section.icon); setColorFilter(section.color) }
        val tile = FrameLayout(this).apply {
            background = tileBg
            addView(icon, FrameLayout.LayoutParams(px(17), px(17), Gravity.CENTER))
        }
        val label = text(section.title, 13f)
        val bg = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.TRANSPARENT) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = bg
            setPadding(px(8), 0, px(8), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)).apply { topMargin = px(4) }
            addView(tile, LinearLayout.LayoutParams(px(32), px(32)))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(12) })
            fun paint(focused: Boolean) {
                val selected = sectionIndex == index
                bg.setColor(when { focused -> 0x2EFFFFFF; selected -> 0x14FFFFFF; else -> Color.TRANSPARENT })
                label.alpha = if (focused || selected) 1f else 0.7f
                label.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            }
            tag = ::paint
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(160).start()
                if (has && sectionIndex != index) {
                    // Wait for the rail to settle before rebuilding, so scrolling through sections stays smooth.
                    pendingSection = index
                    main.removeCallbacksAndMessages(null)
                    main.postDelayed({ if (pendingSection == index) showSection(index, stagger = false) }, 140)
                }
                repaintRail()
                paint(has)
            }
            setOnClickListener { firstFocusable(content)?.requestFocus() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    firstFocusable(content)?.requestFocus(); true
                } else false
            }
            paint(false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun repaintRail() {
        for (i in 1..SECTIONS.size) {
            val item = rail.getChildAt(i)
            (item.tag as? (Boolean) -> Unit)?.invoke(item.isFocused)
        }
    }

    // ---- Content ----

    private fun showSection(index: Int, stagger: Boolean) {
        sectionIndex = index
        content.removeAllViews()
        val section = SECTIONS[index]
        content.addView(text(section.title, 26f, bold = true).apply { setPadding(px(8), 0, 0, 0) })
        content.addView(text(section.blurb, 12f, alpha = 0.55f).apply { setPadding(px(8), px(4), 0, px(8)) })

        val options = AuraSettings.SECTIONS[index].options
        when (section.title) {
            "Movies & shows" -> {
                group(null, listOf(nuvioCard(), rowsCard()))
                group("Discovery", options.take(4).map(::optionRow))
                group("Look", options.drop(4).map(::optionRow) + listOf(
                    actionRow("Show hidden titles", "Bring back titles you hid from rows", R.drawable.ic_visibility) {
                        Catalog.clearHiddenTitles(this); applyToHome(); toast("Hidden titles are back")
                    },
                ))
            }
            "Behaviour" -> {
                group(null, options.map(::optionRow))
                group("Apps", listOf(
                    actionRow("Hidden apps", "Bring back apps you hid from All apps", R.drawable.ic_visibility) { showHiddenApps() },
                    actionRow("Reset favourites order", "Nuvio, TiviMate and YouTube first", R.drawable.ic_restore) {
                        repo.resetOrder(); applyToHome(); toast("Favourites order reset")
                    },
                ))
            }
            else -> group(null, options.map(::optionRow))
        }
        repaintRail()

        // Cards rise in one after another.
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = 14 * dp
            if (!stagger) child.translationY = 4 * dp
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(if (stagger) i * 35L else 0L)
                .setDuration(if (stagger) 320 else 160)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
        scroll.scrollTo(0, 0)
    }

    /** A rounded card holding rows separated by hairlines, with an optional caption above. */
    private fun group(caption: String?, rows: List<View>) {
        if (caption != null) content.addView(text(caption.uppercase(), 10f, bold = true, alpha = 0.45f).apply {
            letterSpacing = 0.16f
            setPadding(px(10), px(18), 0, px(6))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0x12FFFFFF); setStroke(px(1), 0x0FFFFFFF) }
            setPadding(px(4), px(4), px(4), px(4))
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (caption == null) px(10) else 0
            }
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { marginStart = px(16); marginEnd = px(16) })
            card.addView(row)
        }
        content.addView(card)
    }

    /** Shared row shell: soft highlight and a slight lift when focused. */
    private fun rowShell(): LinearLayout {
        val bg = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.TRANSPARENT) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = bg
            setPadding(px(16), px(11), px(14), px(11))
            minimumHeight = px(54)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = bg
        }
    }

    /** Focus lights the row and draws a short bar in the section colour on its left edge, within the card. */
    private fun focusRow(row: View, has: Boolean) {
        val bg = row.tag as GradientDrawable
        bg.setColor(if (has) 0x24FFFFFF else Color.TRANSPARENT)
        row.foreground = if (has) android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply {
            cornerRadius = 2 * dp
            setColor(accent)
        })).apply { setLayerSize(0, px(3), px(22)); setLayerGravity(0, Gravity.START or Gravity.CENTER_VERTICAL); setLayerInsetLeft(0, px(5)) } else null
    }

    private fun textBlock(title: String, subtitle: String?): Pair<LinearLayout, TextView> {
        val t = text(title, 14f)
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(t)
            subtitle?.let { addView(text(it, 10.5f, alpha = 0.5f).apply { setPadding(0, px(2), 0, 0) }) }
        }
        return block to t
    }

    private fun isToggle(option: AuraSettings.Option) = option.choices.map { it.first } == listOf("on", "off")

    private fun optionRow(option: AuraSettings.Option): View {
        val row = rowShell()
        val (block, _) = textBlock(option.title, option.summary)
        row.addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ids = option.choices.map { it.first }
        val toggle = isToggle(option)
        val segmented = !toggle && ids.size <= 4
        val switch = if (toggle) PillSwitch().also { row.addView(it.view) } else null
        val segments = if (segmented) Segments(option).also { row.addView(it.view) } else null
        val value = if (!toggle && !segmented) text("", 12.5f, bold = true).also { row.addView(it) } else null

        fun refresh(animate: Boolean) {
            val id = AuraSettings.get(this, option)
            switch?.set(id == "on", animate)
            segments?.select(id, animate)
            value?.text = if (row.isFocused) "‹  ${option.label(id)}  ›" else option.label(id)
        }
        fun step(delta: Int) {
            val index = ids.indexOf(AuraSettings.get(this, option))
            val next = if (segmented) (index + delta).coerceIn(0, ids.lastIndex) else (index + delta + ids.size) % ids.size
            if (next == index) return
            AuraSettings.set(this, option, ids[next])
            refresh(animate = true)
            value?.let { v -> v.translationX = 8 * dp * delta; v.animate().translationX(0f).setDuration(180).start() }
            applyToHome()
        }
        // Steps happen on key down: the OK press that opened this screen ends here as a key up.
        row.setOnKeyListener { _, keyCode, event ->
            val select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            when {
                event.action != KeyEvent.ACTION_DOWN -> select
                select -> { if (segmented) step(if (ids.indexOf(AuraSettings.get(this, option)) == ids.lastIndex) -ids.size else 1) else step(1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!toggle) step(1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val atStart = toggle || (segmented && ids.indexOf(AuraSettings.get(this, option)) == 0)
                    if (atStart) rail.getChildAt(sectionIndex + 1)?.requestFocus() else step(-1); true
                }
                else -> false
            }
        }
        row.setOnFocusChangeListener { v, has -> focusRow(v, has); refresh(animate = false) }
        refresh(animate = false)
        return row
    }

    private fun actionRow(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit): View {
        val row = rowShell()
        row.addView(glyph(iconRes, accent), LinearLayout.LayoutParams(px(32), px(32)).apply { marginEnd = px(14) })
        row.addView(textBlock(title, subtitle).first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 20f, alpha = 0.45f))
        row.setOnFocusChangeListener { v, has -> focusRow(v, has) }
        row.setOnClickListener { onClick() }
        return row
    }

    // ---- Movies & shows cards ----

    private fun nuvioCard(): View {
        val signedIn = NuvioAccount.isSignedIn(this)
        val profile = NuvioAccount.profile(this)?.name
        val logo = runCatching { packageManager.getApplicationIcon(MainActivity.NUVIO) }.getOrNull()
        val art = ImageView(this).apply {
            if (logo != null) setImageDrawable(logo) else { setImageResource(R.drawable.ic_sec_movies); setColorFilter(accent) }
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xFF0B0F1A.toInt()) }
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        return heroRow(
            art = art,
            title = "Nuvio",
            subtitle = if (signedIn) "Continue watching from “${profile ?: "?"}”" else "Sign in to bring Continue watching here",
            badge = if (signedIn) "Connected" else "Sign in",
            badgeColor = if (signedIn) 0xFF16A34A.toInt() else accent,
        ) { open(NuvioLoginActivity::class.java) }
    }

    private fun rowsCard(): View {
        val rows = Addons.rows(this)
        val shown = rows.count { it.enabled }
        val addons = rows.map { it.addonName }.distinct().size
        return heroRow(
            art = glyph(R.drawable.ic_layers, 0xFFFB923C.toInt(), size = 40),
            title = "Addons & rows",
            subtitle = "$addons addons · choose and order what appears",
            badge = "$shown on home",
            badgeColor = 0x2EFFFFFF,
        ) { open(RowsActivity::class.java) }
    }

    private fun heroRow(art: View, title: String, subtitle: String, badge: String, badgeColor: Int, onClick: () -> Unit): View {
        val row = rowShell().apply { setPadding(px(14), px(14), px(14), px(14)) }
        row.addView(art, LinearLayout.LayoutParams(px(40), px(40)))
        row.addView(textBlock(title, subtitle).first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
        row.addView(text(badge, 10.5f, bold = true).apply {
            setPadding(px(10), px(4), px(10), px(4))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(badgeColor) }
        })
        row.addView(text("›", 20f, alpha = 0.45f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10) })
        row.setOnFocusChangeListener { v, has -> focusRow(v, has) }
        row.setOnClickListener { onClick() }
        return row
    }

    /** An icon on a soft tile tinted with its own colour. */
    private fun glyph(iconRes: Int, color: Int, size: Int = 32): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = size * 0.31f * dp; setColor(tint(color, 0.16f)) }
        addView(ImageView(context).apply { setImageResource(iconRes); setColorFilter(color) },
            FrameLayout.LayoutParams(px((size * 0.52f).toInt()), px((size * 0.52f).toInt()), Gravity.CENTER))
    }

    private fun tint(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

    // ---- Controls ----

    /** A pill switch in the section's colour whose knob slides when flipped. */
    private inner class PillSwitch {
        private val knob = View(this@SettingsActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = 2 * dp
        }
        private val track = GradientDrawable().apply { cornerRadius = 13 * dp }
        val view = FrameLayout(this@SettingsActivity).apply {
            background = track
            addView(knob, FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER_VERTICAL).apply { marginStart = px(3) })
            layoutParams = LinearLayout.LayoutParams(px(46), px(26)).apply { marginStart = px(14) }
        }

        fun set(on: Boolean, animate: Boolean) {
            track.setColor(if (on) accent else 0x33FFFFFF)
            val x = if (on) 20 * dp else 0f
            if (animate) knob.animate().translationX(x).setDuration(200).setInterpolator(OvershootInterpolator(1.4f)).start()
            else knob.translationX = x
        }
    }

    /** All choices side by side; the selected one sits in a sliding pill. */
    private inner class Segments(option: AuraSettings.Option) {
        private val ids = option.choices.map { it.first }
        private val pill = View(this@SettingsActivity).apply {
            background = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(accent) }
        }
        private val labels = option.choices.map { (_, label) ->
            text(label, 11f, bold = true).apply { gravity = Gravity.CENTER; setPadding(px(10), 0, px(10), 0) }
        }
        private val strip = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        val view = FrameLayout(this@SettingsActivity).apply {
            background = GradientDrawable().apply { cornerRadius = 13 * dp; setColor(0x1FFFFFFF) }
            setPadding(px(3), px(3), px(3), px(3))
            addView(pill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(30)).apply { marginStart = px(14) }
        }
        private var current = -1

        fun select(id: String, animate: Boolean) {
            val index = ids.indexOf(id).coerceAtLeast(0)
            labels.forEachIndexed { i, l -> l.alpha = if (i == index) 1f else 0.55f }
            current = index
            strip.post {
                val target = labels[index]
                pill.layoutParams = pill.layoutParams.apply { width = target.width }
                if (animate) pill.animate().translationX(target.left.toFloat()).setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
                else pill.translationX = target.left.toFloat()
            }
        }
    }

    // ---- Helpers ----

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
        overridePendingTransition(0, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Lists hidden apps; picking one brings it back to the All apps row. */
    private fun showHiddenApps() {
        val hidden = repo.hidden.toList()
        if (hidden.isEmpty()) return toast("No hidden apps")
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
        panel.animate().translationX(PANEL_DP * dp * 0.3f).alpha(0f).setDuration(240).setInterpolator(DecelerateInterpolator())
            .withEndAction { super.finish(); overridePendingTransition(0, 0) }.start()
    }

    /** First focusable view inside [group], searching nested cards. */
    private fun firstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isFocusable) return child
            if (child is ViewGroup) firstFocusable(child)?.let { return it }
        }
        return null
    }

    private fun text(value: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun px(value: Int) = (value * dp).toInt()

    private class Section(val title: String, val blurb: String, val icon: Int, val color: Int)

    companion object {
        const val ACTION_CHANGED = "com.tvlauncher.aura.SETTINGS_CHANGED"
        private const val PANEL_DP = 680
        private val SECTIONS = listOf(
            Section("Icons", "Shape, size and how apps react to focus", R.drawable.ic_sec_icons, 0xFF60A5FA.toInt()),
            Section("Clock & widgets", "What the header shows", R.drawable.ic_sec_clock, 0xFFFBBF24.toInt()),
            Section("Movies & shows", "Your Nuvio rows, trailers and previews", R.drawable.ic_sec_movies, 0xFFF87171.toInt()),
            Section("Background", "Scenery behind the home screen", R.drawable.ic_sec_background, 0xFFA78BFA.toInt()),
            Section("Behaviour", "Idle mode, rows and app housekeeping", R.drawable.ic_sec_behaviour, 0xFF34D399.toInt()),
        )
    }
}
