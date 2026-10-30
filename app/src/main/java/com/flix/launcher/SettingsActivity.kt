package com.flix.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Flix Launcher's settings, full screen over the blurred home. A search box sits at the
 * top and finds any setting by name, by what it does or by everyday words ("sound",
 * "screensaver", "english"). Below it, sections sit in a rail on the left and their
 * options in grouped cards on the right, each with its own line icon. Every change
 * applies to the home screen behind as it is made.
 */
class SettingsActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var root: View
    private lateinit var repo: AppRepository
    private lateinit var rail: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private lateinit var searchBox: LinearLayout
    private var sectionIndex = 0
    private var created = false

    private val query get() = search.text.toString().trim()
    private val searching get() = query.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        blurBehind()
        val top = topBar() // first: the rail reads the search box

        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(40), px(6), px(16), px(28))
        }
        SECTIONS.forEachIndexed { i, s -> rail.addView(railItem(i, s)) }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(4), px(48), px(40))
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
            addView(rail, LinearLayout.LayoutParams(px(268), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xF2140A0C.toInt(), 0xF50A0A0C.toInt(), 0xF7080809.toInt()))
            addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        root.alpha = 0f
        root.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        showSection(0, stagger = true)
        rail.getChildAt(0)?.post { rail.getChildAt(0)?.requestFocus() }
    }

    /** Blurs the home screen behind where the TV supports it, and dims it otherwise. */
    private fun blurBehind() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.5f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = px(48) }
            window.setDimAmount(0.3f)
        }
    }

    /**
     * Up/Down stays inside the pane it started in, except that going up off the top of
     * either pane reaches the search box, and down from the search box goes back in.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
            val focused = currentFocus
            if (focused === search) {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) intoContentOrRail()
                return true
            }
            if (focused != null) {
                val pane = if (isInside(focused, rail)) rail else if (isInside(focused, content)) content else null
                val next = focused.focusSearch(if (code == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP)
                if (pane != null && (next == null || !isInside(next, pane))) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP) search.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun intoContentOrRail() {
        val target = if (searching) firstFocusable(content) else rail.getChildAt(sectionIndex)
        (target ?: rail.getChildAt(sectionIndex))?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back clears a search first, then closes.
        if (searching) {
            search.setText("")
            rail.getChildAt(sectionIndex)?.requestFocus()
        } else @Suppress("DEPRECATION") super.onBackPressed()
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

    /** Where focus was when another screen opened on top, so coming back lands on the same row. */
    private var focusBeforePause = -1

    override fun onPause() {
        super.onPause()
        focusBeforePause = currentFocus?.let { focusables(content).indexOf(it) } ?: -1
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the Nuvio or rows screens: their summary cards may have changed.
        if (created) {
            // Rebuilt in place, with no rise-in, at the same scroll and focus, so coming back
            // doesn't flicker; only the account and rows cards can have changed.
            val y = scroll.scrollY
            refresh(animate = false)
            focusables(content).getOrNull(focusBeforePause)?.requestFocus()
            scroll.post { scroll.scrollTo(0, y) }
        }
        created = true
    }

    private fun focusables(group: ViewGroup): List<View> = buildList {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isFocusable) add(child) else if (child is ViewGroup) addAll(focusables(child))
        }
    }

    private fun refresh(animate: Boolean = true) = if (searching) showResults(query, animate) else showSection(sectionIndex, stagger = false, animate = animate)

    // ---- Top bar ----

    /** The FLIX wordmark and "Settings" on the left; the search box on the right. */
    private fun topBar(): View {
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "FLIX"
                textSize = 26f
                letterSpacing = -0.02f
                setTextColor(RED)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            })
            addView(View(context).apply { setBackgroundColor(0x33FFFFFF) },
                LinearLayout.LayoutParams(px(1), px(22)).apply { marginStart = px(16); marginEnd = px(16) })
            addView(text("Settings", 20f, alpha = 0.9f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        }
        return FrameLayout(this).apply {
            setPadding(px(54), px(26), px(48), px(14))
            clipToPadding = false // the focused search box grows a little past its slot
            clipChildren = false
            addView(brand, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
            addView(searchBox(), FrameLayout.LayoutParams(px(400), px(48), Gravity.END or Gravity.CENTER_VERTICAL))
        }
    }

    /**
     * A pill that turns white when focused, like the search on streaming apps. OK opens
     * the keyboard; results update with every letter.
     */
    private fun searchBox(): View {
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_o_search); setColorFilter(0xB3FFFFFF.toInt()) }
        search = EditText(this).apply {
            hint = "Search settings"
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0x8CFFFFFF.toInt())
            background = null
            isSingleLine = true
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); intoContentOrRail(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    repaintRail()
                    typeHint.visibility = if (searching) View.GONE else View.VISIBLE
                    refresh()
                }
            })
        }
        typeHint = text("OK to type", 11f, alpha = 0.55f)
        val bg = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(0x14FFFFFF); setStroke(px(1), 0x24FFFFFF) }
        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(px(18), 0, px(18), 0)
            addView(icon, LinearLayout.LayoutParams(px(20), px(20)).apply { marginEnd = px(12) })
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(typeHint)
        }
        search.setOnFocusChangeListener { _, has ->
            bg.setColor(if (has) Color.WHITE else 0x14FFFFFF)
            bg.setStroke(px(1), if (has) Color.WHITE else 0x24FFFFFF)
            search.setTextColor(if (has) INK else Color.WHITE)
            search.setHintTextColor(if (has) 0x99141414.toInt() else 0x8CFFFFFF.toInt())
            icon.setColorFilter(if (has) INK else 0xB3FFFFFF.toInt())
            typeHint.setTextColor(if (has) INK else Color.WHITE)
            searchBox.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(160).start()
            if (!has) hideKeyboard()
        }
        return searchBox
    }

    private lateinit var typeHint: TextView

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ---- Rail ----

    private fun railItem(index: Int, section: Section): View {
        val icon = ImageView(this).apply { setImageResource(section.icon) }
        val label = text(section.title, 15f)
        val bar = View(this).apply { background = GradientDrawable().apply { cornerRadius = 2 * dp; setColor(RED) } }
        val bg = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.TRANSPARENT) }
        return FrameLayout(this).apply {
            isFocusable = true
            background = bg
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50)).apply { topMargin = px(4) }
            addView(bar, FrameLayout.LayoutParams(px(3), px(20), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), 0, px(12), 0)
                addView(icon, LinearLayout.LayoutParams(px(22), px(22)))
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(16) })
            })
            // Focused: a white pill with dark text. Selected: a red bar. Others: dimmed.
            fun paint(focused: Boolean) {
                val selected = sectionIndex == index && !searching
                bg.setColor(if (focused) Color.WHITE else Color.TRANSPARENT)
                val ink = if (focused) INK else Color.WHITE
                label.setTextColor(ink)
                icon.setColorFilter(ink)
                label.alpha = if (focused || selected) 1f else 0.62f
                icon.alpha = label.alpha
                label.typeface = Typeface.create(if (focused || selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                bar.visibility = if (selected && !focused) View.VISIBLE else View.INVISIBLE
            }
            tag = ::paint
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(160).start()
                if (has && (sectionIndex != index || searching)) {
                    if (searching) {
                        // Moving into the sections ends a search.
                        sectionIndex = index
                        search.setText("")
                    } else {
                        // Wait for the rail to settle before rebuilding, so scrolling through sections stays smooth.
                        pendingSection = index
                        main.removeCallbacksAndMessages(null)
                        main.postDelayed({ if (pendingSection == index) showSection(index, stagger = false) }, 140)
                    }
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
        for (i in SECTIONS.indices) {
            val item = rail.getChildAt(i)
            (item.tag as? (Boolean) -> Unit)?.invoke(item.isFocused)
        }
    }

    // ---- Catalogue ----

    /** One searchable setting: where it lives, the words it answers to, and how to draw it. */
    private class Entry(val section: Int, val group: String?, val words: String, val rows: (() -> List<View>)? = null, val build: (() -> View)? = null) {
        fun views() = rows?.invoke() ?: listOf(build!!())
    }

    /** Every setting and action, in the order they appear. Built fresh so account cards stay current. */
    private fun catalogue(): List<Entry> = buildList {
        fun opt(s: Int, group: String?, o: FlixSettings.Option, icon: Int, extra: String = "") =
            add(Entry(s, group, listOfNotNull(o.title, o.summary, extra, SECTIONS[s].title, o.choices.joinToString(" ") { it.second }).joinToString(" ")) { optionRow(o, icon) })
        fun act(s: Int, group: String?, title: String, sub: String, icon: Int, extra: String, onClick: () -> Unit) =
            add(Entry(s, group, "$title $sub $extra ${SECTIONS[s].title}") { actionRow(title, sub, icon, onClick) })

        // Home screen
        opt(0, "Look", FlixSettings.THEME, R.drawable.ic_o_contrast, "theme colour color style glass cinema midnight minimal dark apple netflix")
        opt(0, "App tiles", FlixSettings.ICON_SHAPE, R.drawable.ic_o_shape, "icons round square")
        opt(0, "App tiles", FlixSettings.ICON_SIZE, R.drawable.ic_o_size, "icons big bigger smaller")
        opt(0, "App tiles", FlixSettings.LABELS, R.drawable.ic_o_text, "names titles text")
        opt(0, "App tiles", FlixSettings.GLOW, R.drawable.ic_o_glow, "light colour color shine")
        opt(0, "App tiles", FlixSettings.MOTION, R.drawable.ic_o_motion, "animation bounce speed")
        opt(0, "All apps", FlixSettings.ALL_APPS, R.drawable.ic_o_grid, "every app list")
        opt(0, "All apps", FlixSettings.ALL_APPS_TILE, R.drawable.ic_o_tile_plus, "every app button")
        act(0, "All apps", "Hidden apps", "Bring back apps you hid from All apps", R.drawable.ic_o_eye, "unhide restore show") { showHiddenApps() }
        act(0, "All apps", "Reset favourites order", "Nuvio, TiviMate and YouTube first", R.drawable.ic_o_reset, "sort order arrange") {
            repo.resetOrder(); applyToHome(appsOnly = true); toast("Favourites order reset")
        }

        // Movies & shows
        add(Entry(1, null, "Nuvio account sign in login profile continue watching") { nuvioCard() })
        add(Entry(1, null, "Addons rows catalogues order choose") { rowsCard() })
        opt(1, "On the home screen", FlixSettings.SUGGESTIONS, R.drawable.ic_o_rows, "films movies series")
        opt(1, "On the home screen", FlixSettings.BILLBOARD, R.drawable.ic_o_layers, "carousel billboard banner featured hero slideshow poster")
        opt(1, "On the home screen", FlixSettings.CONTINUE_WATCHING, R.drawable.ic_o_resume, "resume progress")
        opt(1, "On the home screen", FlixSettings.TOP_TEN, R.drawable.ic_o_rank, "top 10 english popular tamil")
        opt(1, "On the home screen", FlixSettings.GENRE, R.drawable.ic_o_filter, "category type")
        opt(1, "Previews", FlixSettings.TRAILERS, R.drawable.ic_o_play, "video preview autoplay")
        opt(1, "Previews", FlixSettings.TRAILER_DELAY, R.drawable.ic_o_clock, "trailer wait delay time seconds slow fast")
        opt(1, "Previews", FlixSettings.TRAILER_SOUND, R.drawable.ic_o_sound, "audio volume mute loud")
        opt(1, "Previews", FlixSettings.BACKDROP, R.drawable.ic_o_image, "artwork background picture")
        opt(1, "Look", FlixSettings.POSTER_SIZE, R.drawable.ic_o_poster, "big bigger smaller tiles")
        act(1, "Look", "Show hidden titles", "Bring back titles you hid from rows", R.drawable.ic_o_eye, "unhide restore films movies") {
            Catalog.clearHiddenTitles(this@SettingsActivity); applyToHome(); toast("Hidden titles are back")
        }

        // Clock & weather
        opt(2, "Clock", FlixSettings.CLOCK_STYLE, R.drawable.ic_o_text, "time font look")
        opt(2, "Clock", FlixSettings.CLOCK_FORMAT, R.drawable.ic_o_clock, "time am pm 24 12 hour")
        opt(2, "Clock", FlixSettings.GREETING, R.drawable.ic_o_chat, "hello good morning evening")
        opt(2, "Clock", FlixSettings.DATE, R.drawable.ic_o_calendar, "day month today")
        opt(2, "Weather", FlixSettings.WEATHER, R.drawable.ic_o_sun, "forecast rain temperature")
        opt(2, "Weather", FlixSettings.UNITS, R.drawable.ic_o_thermo, "celsius fahrenheit degrees")

        // Wallpaper
        for ((group, words) in listOf(
            "Photos" to "background wallpaper photo picture nature city cities bing scenery",
            "Aerial video" to "background wallpaper video aerial apple fire tv ocean sea space earth art landscapes cities scenery",
            "Plain" to "background wallpaper plain dark black none",
        )) add(Entry(3, group, words, rows = { wallpaperRows(group) }))
        opt(3, "Picture", FlixSettings.DIM, R.drawable.ic_o_contrast, "dark bright brightness")
        opt(3, "Picture", FlixSettings.SHUFFLE, R.drawable.ic_o_shuffle, "random change")
        opt(3, "Picture", FlixSettings.SCENE_NAME, R.drawable.ic_o_pin, "caption place location title")
        opt(3, "When the TV is idle", FlixSettings.IDLE, R.drawable.ic_o_moon, "screensaver sleep inactive timeout")

        // Backup & restore
        act(4, "Backup & restore", "Back up settings", backupSummary(), R.drawable.ic_o_layers, "backup save export copy keep") { backUp() }
        act(4, "Backup & restore", "Restore settings", "Bring back settings from a backup", R.drawable.ic_o_reset, "restore import load recover backup") { pickBackup() }

        // About
        add(Entry(4, null, "about version app flix launcher") { infoRow("Flix Launcher", "Version ${version()}", R.drawable.ic_s_info) })
        val update = Updater.ready(this@SettingsActivity)
        act(4, "Updates", if (update != null) "Update to ${update.version}" else "Check for updates",
            if (update != null) "Downloaded. Installs now; Flix reopens by itself" else "Flix updates itself when the TV is idle",
            R.drawable.ic_o_reset, "update upgrade new version download install release") { updateNow() }
        add(Entry(4, null, "about tv device model") { infoRow("This TV", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", R.drawable.ic_o_tv) })
    }

    private fun version() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0"

    // ---- Content ----

    private fun header(title: String, blurb: String) {
        content.addView(text(title, 28f, bold = true).apply { setPadding(px(10), 0, 0, 0) })
        content.addView(text(blurb, 13f, alpha = 0.55f).apply { setPadding(px(10), px(4), 0, px(10)) })
    }

    private fun showSection(index: Int, stagger: Boolean, animate: Boolean = true) {
        sectionIndex = index
        checkMarks.clear()
        content.removeAllViews()
        val section = SECTIONS[index]
        header(section.title, section.blurb)
        catalogue().filter { it.section == index }.groupBy { it.group }.forEach { (caption, entries) ->
            group(caption, entries.flatMap { it.views() })
        }
        repaintRail()
        if (animate) rise(stagger)
        scroll.scrollTo(0, 0)
    }

    /** Everything whose words contain every word typed, grouped by the section it lives in. */
    private fun showResults(q: String, animate: Boolean = true) {
        checkMarks.clear()
        content.removeAllViews()
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val found = catalogue().filter { e -> val w = e.words.lowercase(); terms.all { it in w } }
        if (found.isEmpty()) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, px(70), 0, 0)
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_o_search); alpha = 0.3f }, LinearLayout.LayoutParams(px(44), px(44)))
                addView(text("Nothing matches “$q”", 18f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, px(18), 0, px(6)) })
                addView(text("Try words like trailer, clock, sound or wallpaper", 13f, alpha = 0.5f).apply { gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            header("Results", if (found.size == 1) "1 setting matches “$q”" else "${found.size} settings match “$q”")
            found.groupBy { it.section }.forEach { (s, entries) -> group(SECTIONS[s].title, entries.flatMap { it.views() }) }
        }
        if (animate) rise(stagger = false)
        scroll.scrollTo(0, 0)
    }

    /** Cards rise in one after another. */
    private fun rise(stagger: Boolean) {
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = (if (stagger) 14 else 4) * dp
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(if (stagger) i * 35L else 0L)
                .setDuration(if (stagger) 320 else 160)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    /** A rounded card holding rows separated by hairlines, with an optional caption above. */
    private fun group(caption: String?, rows: List<View>) {
        if (caption != null) content.addView(text(caption.uppercase(), 11f, bold = true, alpha = 0.45f).apply {
            letterSpacing = 0.14f
            setPadding(px(12), px(20), 0, px(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0x0FFFFFFF); setStroke(px(1), 0x0DFFFFFF) }
            setPadding(px(4), px(4), px(4), px(4))
            clipChildren = false
            clipToPadding = false // focused wallpaper tiles grow past the edge
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (caption == null) px(12) else 0
            }
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(0x12FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { marginStart = px(66); marginEnd = px(16) })
            card.addView(row)
        }
        content.addView(card)
    }

    /** How a row draws its focus: its background, and the icon tile that turns red. */
    private class Look(val bg: GradientDrawable, val tile: GradientDrawable?)

    private fun rowShell(tile: GradientDrawable? = null): LinearLayout {
        val bg = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.TRANSPARENT) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = bg
            setPadding(px(12), px(10), px(16), px(10))
            minimumHeight = px(60)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = Look(bg, tile)
        }
    }

    private fun focusRow(row: View, has: Boolean) {
        val look = row.tag as Look
        look.bg.setColor(if (has) 0x24FFFFFF else Color.TRANSPARENT)
        look.bg.setStroke(px(1), if (has) 0x33FFFFFF else Color.TRANSPARENT)
        look.tile?.setColor(if (has) RED else 0x14FFFFFF)
    }

    /** A line icon on a soft square tile, which takes the brand red when its row is focused. */
    private fun iconTile(iconRes: Int): Pair<View, GradientDrawable> {
        val bg = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(0x14FFFFFF) }
        val view = FrameLayout(this).apply {
            background = bg
            addView(ImageView(context).apply { setImageResource(iconRes); alpha = 0.92f },
                FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
        }
        return view to bg
    }

    private fun iconRow(iconRes: Int): LinearLayout {
        val (tile, bg) = iconTile(iconRes)
        return rowShell(bg).apply { addView(tile, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(16) }) }
    }

    private fun textBlock(title: String, subtitle: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 15f))
        subtitle?.let { addView(text(it, 12f, alpha = 0.52f).apply { setPadding(0, px(2), 0, 0) }) }
    }

    private fun isToggle(option: FlixSettings.Option) = option.choices.map { it.first } == listOf("on", "off")

    private fun optionRow(option: FlixSettings.Option, iconRes: Int): View {
        val row = iconRow(iconRes)
        row.addView(textBlock(option.title, option.summary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ids = option.choices.map { it.first }
        val toggle = isToggle(option)
        val segmented = !toggle && ids.size <= 3 && option.choices.sumOf { it.second.length } <= 30
        val switch = if (toggle) PillSwitch().also { row.addView(it.view) } else null
        val segments = if (segmented) Segments(option).also { row.addView(it.view) } else null
        val value = if (!toggle && !segmented) Stepper(option).also { row.addView(it.view) } else null

        fun refresh(animate: Boolean) {
            val id = FlixSettings.get(this, option)
            switch?.set(id == "on", animate)
            segments?.select(id, animate)
            value?.set(option.label(id), row.isFocused, ids.indexOf(id), ids.size)
        }
        fun step(delta: Int) {
            val index = ids.indexOf(FlixSettings.get(this, option))
            val next = if (segmented) (index + delta).coerceIn(0, ids.lastIndex) else (index + delta + ids.size) % ids.size
            if (next == index) return
            FlixSettings.set(this, option, ids[next])
            refresh(animate = true)
            value?.nudge(delta)
            applyToHome()
        }
        // Steps happen on key down: the OK press that opened this screen ends here as a key up.
        row.setOnKeyListener { _, keyCode, event ->
            val select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            when {
                event.action != KeyEvent.ACTION_DOWN -> select
                select -> { if (segmented) step(if (ids.indexOf(FlixSettings.get(this, option)) == ids.lastIndex) -ids.size else 1) else step(1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!toggle) step(1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val atStart = toggle || (segmented && ids.indexOf(FlixSettings.get(this, option)) == 0)
                    if (atStart) rail.getChildAt(sectionIndex)?.requestFocus() else step(-1); true
                }
                else -> false
            }
        }
        row.setOnFocusChangeListener { v, has -> focusRow(v, has); refresh(animate = false) }
        refresh(animate = false)
        return row
    }

    private fun actionRow(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit): View {
        val row = iconRow(iconRes)
        row.addView(textBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chevron())
        row.setOnFocusChangeListener { v, has -> focusRow(v, has) }
        row.setOnClickListener { onClick() }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rail.getChildAt(sectionIndex)?.requestFocus(); true } else false
        }
        return row
    }

    /** A plain fact, focusable so the remote can reach it. */
    private fun infoRow(title: String, subtitle: String, iconRes: Int): View {
        val row = iconRow(iconRes)
        row.addView(textBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnFocusChangeListener { v, has -> focusRow(v, has) }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rail.getChildAt(sectionIndex)?.requestFocus(); true } else false
        }
        return row
    }

    private fun chevron() = ImageView(this).apply {
        setImageResource(R.drawable.ic_o_chevron)
        alpha = 0.45f
        layoutParams = LinearLayout.LayoutParams(px(18), px(18)).apply { marginStart = px(12) }
    }

    // ---- Wallpaper picker ----

    /** One background: its name, what it shows, and a real frame from it, shipped in the app. */
    private class Wall(val title: String, val about: String, val preview: Int?, val from: Int, val to: Int)

    private val WALLS = mapOf(
        "nature" to Wall("Nature", "A new Bing photo every minute: forests, peaks and coasts", R.drawable.wall_nature, 0xFF1F5130.toInt(), 0xFF0B1F14.toInt()),
        "cities" to Wall("Cities", "Skylines and landmarks from the same Bing archive", R.drawable.wall_cities, 0xFF5A3A1A.toInt(), 0xFF171019.toInt()),
        "video" to Wall("Everything", "All 270 aerial films, shuffled", R.drawable.wall_video, 0xFF8A0B12.toInt(), 0xFF1A0507.toInt()),
        "v_nature" to Wall("Landscapes", "Mountains, deserts and islands from above", R.drawable.wall_v_nature, 0xFF3E5A22.toInt(), 0xFF121A0B.toInt()),
        "v_sea" to Wall("Oceans", "Waves, reefs and dolphins", R.drawable.wall_v_sea, 0xFF0E4E68.toInt(), 0xFF06141E.toInt()),
        "v_city" to Wall("Cities", "Flying over Dubai, Hong Kong and San Francisco", R.drawable.wall_v_city, 0xFF2B3566.toInt(), 0xFF0B0E1F.toInt()),
        "v_space" to Wall("Earth from space", "Filmed from the International Space Station", R.drawable.wall_v_space, 0xFF1A1F4A.toInt(), 0xFF020308.toInt()),
        "v_art" to Wall("Colour art", "Slow abstract colour from Fire TV", null, 0xFF8E2A7A.toInt(), 0xFF1E0A4A.toInt()),
        "gradient" to Wall("Plain dark", "No picture at all; the lightest on the TV", null, 0xFF2A2A2E.toInt(), 0xFF0A0A0C.toInt()),
    )

    /** The backgrounds as a list in three groups, each row with its preview and a check on the one in use. */
    private fun wallpaperRows(group: String): List<View> {
        val option = FlixSettings.WALLPAPER
        val ids = when (group) {
            "Photos" -> listOf("nature", "cities")
            "Aerial video" -> listOf("video", "v_nature", "v_sea", "v_city", "v_space", "v_art")
            else -> listOf("gradient")
        }
        return ids.map { id ->
            val wall = WALLS.getValue(id)
            val row = rowShell().apply { setPadding(px(10), px(8), px(16), px(8)) }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(wall.from, wall.to)).apply { cornerRadius = 9 * dp }
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            // Bundled, ~5 KB each: they show at once, offline too.
            wall.preview?.let { thumb.setImageResource(it) }
            row.addView(thumb, LinearLayout.LayoutParams(px(112), px(63)).apply { marginEnd = px(16) })
            row.addView(textBlock(wall.title, wall.about), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val check = FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED) }
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_check) }, FrameLayout.LayoutParams(px(14), px(14), Gravity.CENTER))
            }
            row.addView(check, LinearLayout.LayoutParams(px(24), px(24)).apply { marginStart = px(12) })
            fun mark() { check.visibility = if (FlixSettings.get(this, option) == id) View.VISIBLE else View.INVISIBLE }
            mark()
            row.tag = Look(row.tag.let { (it as Look).bg }, null)
            row.setOnFocusChangeListener { v, has -> focusRow(v, has) }
            row.setOnClickListener {
                FlixSettings.set(this, option, id)
                // Every row's check follows the choice.
                content.post { refreshChecks() }
                applyToHome()
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rail.getChildAt(sectionIndex)?.requestFocus(); true } else false
            }
            checkMarks += { mark() }
            row
        }
    }

    private val checkMarks = ArrayList<() -> Unit>()

    private fun refreshChecks() = checkMarks.forEach { it() }

    // ---- Movies & shows cards ----

    private fun nuvioCard(): View {
        val signedIn = NuvioAccount.isSignedIn(this)
        val profile = NuvioAccount.profile(this)?.name
        val logo = runCatching { packageManager.getApplicationIcon(MainActivity.findNuvio(packageManager)) }.getOrNull()
        val art = if (logo != null) ImageView(this).apply {
            setImageDrawable(logo)
            background = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(0xFF0B0F1A.toInt()) }
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        } else iconTile(R.drawable.ic_s_film).first
        return heroRow(
            art = art,
            title = "Nuvio",
            subtitle = if (signedIn) "Continue watching from “${profile ?: "?"}”" else "Sign in to bring Continue watching here",
            badge = if (signedIn) "Connected" else "Sign in",
            badgeColor = if (signedIn) 0xFF16A34A.toInt() else RED,
        ) { open(NuvioLoginActivity::class.java) }
    }

    private fun rowsCard(): View {
        val rows = Addons.rows(this)
        val shown = rows.count { it.enabled }
        val addons = rows.map { it.addonName }.distinct().size
        val (tile, bg) = iconTile(R.drawable.ic_o_layers)
        return heroRow(
            art = tile,
            tile = bg,
            title = "Addons & rows",
            subtitle = "$addons addons · choose and order what appears",
            badge = "$shown on home",
            badgeColor = 0x2EFFFFFF,
        ) { open(RowsActivity::class.java) }
    }

    private fun heroRow(art: View, tile: GradientDrawable? = null, title: String, subtitle: String, badge: String, badgeColor: Int, onClick: () -> Unit): View {
        val row = rowShell(tile).apply { setPadding(px(12), px(14), px(16), px(14)) }
        row.addView(art, LinearLayout.LayoutParams(px(38), px(38)))
        row.addView(textBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16) })
        row.addView(text(badge, 11f, bold = true).apply {
            setPadding(px(11), px(4), px(11), px(4))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(badgeColor) }
        })
        row.addView(chevron())
        row.setOnFocusChangeListener { v, has -> focusRow(v, has) }
        row.setOnClickListener { onClick() }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rail.getChildAt(sectionIndex)?.requestFocus(); true } else false
        }
        return row
    }

    // ---- Controls ----

    /** A pill switch in brand red whose knob slides when flipped. */
    private inner class PillSwitch {
        private val knob = View(this@SettingsActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = 2 * dp
        }
        private val track = GradientDrawable().apply { cornerRadius = 14 * dp }
        val view = FrameLayout(this@SettingsActivity).apply {
            background = track
            addView(knob, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER_VERTICAL).apply { marginStart = px(3) })
            layoutParams = LinearLayout.LayoutParams(px(50), px(28)).apply { marginStart = px(14) }
        }

        fun set(on: Boolean, animate: Boolean) {
            track.setColor(if (on) RED else 0x33FFFFFF)
            val x = if (on) 22 * dp else 0f
            if (animate) knob.animate().translationX(x).setDuration(200).setInterpolator(OvershootInterpolator(1.4f)).start()
            else knob.translationX = x
        }
    }

    /** All choices side by side; the selected one sits in a sliding white pill. */
    private inner class Segments(option: FlixSettings.Option) {
        private val ids = option.choices.map { it.first }
        private val pill = View(this@SettingsActivity).apply {
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.WHITE) }
        }
        private val labels = option.choices.map { (_, label) ->
            text(label, 12f, bold = true).apply { gravity = Gravity.CENTER; setPadding(px(12), 0, px(12), 0) }
        }
        private val strip = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        val view = FrameLayout(this@SettingsActivity).apply {
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x1AFFFFFF) }
            setPadding(px(3), px(3), px(3), px(3))
            addView(pill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32)).apply { marginStart = px(14) }
        }

        fun select(id: String, animate: Boolean) {
            val index = ids.indexOf(id).coerceAtLeast(0)
            labels.forEachIndexed { i, l ->
                l.setTextColor(if (i == index) INK else Color.WHITE)
                l.alpha = if (i == index) 1f else 0.6f
            }
            strip.post {
                val target = labels[index]
                pill.layoutParams = pill.layoutParams.apply { width = target.width }
                if (animate) pill.animate().translationX(target.left.toFloat()).setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
                else pill.translationX = target.left.toFloat()
            }
        }
    }

    /** For longer lists: the current choice between arrows, with dots showing where it sits in the list. */
    private inner class Stepper(option: FlixSettings.Option) {
        // As wide as the longest choice, so the row doesn't shift as the value changes.
        private val label = text("", 13f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            minWidth = option.choices.maxOf { paint.measureText(it.second) }.toInt() + px(10)
        }
        private val prev = text("‹", 18f, alpha = 0f)
        private val next = text("›", 18f, alpha = 0f)
        private val dots = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val view = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10); marginEnd = px(10) })
            addView(next)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(14) }
        }

        fun set(text: String, focused: Boolean, index: Int, count: Int) {
            label.text = text
            prev.alpha = if (focused) 0.8f else 0f
            next.alpha = prev.alpha
            if (dots.childCount != count) {
                dots.removeAllViews()
                repeat(count) { dots.addView(View(this@SettingsActivity), LinearLayout.LayoutParams(px(4), px(4)).apply { marginStart = px(2); marginEnd = px(2) }) }
            }
            for (i in 0 until count) dots.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) RED else 0x40FFFFFF)
            }
        }

        fun nudge(delta: Int) {
            label.translationX = 8 * dp * delta
            label.animate().translationX(0f).setDuration(180).start()
        }
    }

    // ---- Helpers ----

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
        overridePendingTransition(0, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---- Updates ----

    private fun updateNow() {
        if (!Updater.canInstall(this)) {
            toast("Allow Flix Launcher to install apps, then come back")
            Updater.askPermission(this)
            return
        }
        Updater.ready(this)?.let { if (Updater.install(this)) toast("Updating to ${it.version}…"); return }
        toast("Checking for updates…")
        Thread {
            val found = runCatching { Updater.check(this, force = true) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                found.onSuccess { r ->
                    if (r == null) toast("Flix is up to date (${version()})")
                    else { toast("Updating to ${r.version}…"); Updater.install(this) }
                }.onFailure { toast("Couldn't check for updates") }
            }
        }.start()
    }

    // ---- Backup & restore ----

    private val stamp = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())

    private fun backupSummary() = Backup.list(this).firstOrNull()?.let { "Last backup ${stamp.format(it.created)}" }
        ?: "Everything except your Nuvio sign-in"

    private fun backUp() = Thread {
        val result = runCatching { Backup.save(this) }
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            result.onSuccess { toast("Backed up. A copy is also in Download/FlixLauncher"); refresh(animate = false) }
                .onFailure { toast(it.message ?: "Couldn't back up") }
        }
    }.start()

    /** Lists backups newest first; picking one asks before replacing the current settings. */
    private fun pickBackup() {
        val backups = Backup.list(this)
        if (backups.isEmpty()) return toast("No backups yet. Copy one into Android/data/$packageName/files/backups")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Restore which backup?")
            .setItems(backups.map { stamp.format(it.created) }.toTypedArray()) { _, which -> confirmRestore(backups[which]) }
            .show()
    }

    private fun confirmRestore(backup: Backup.Saved) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Restore settings from ${stamp.format(backup.created)}?")
            .setMessage("Your current settings, favourites and rows are replaced. Your Nuvio sign-in stays as it is.")
            .setPositiveButton("Restore") { _, _ ->
                Thread {
                    val result = runCatching { Backup.restore(this, backup) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        result.onSuccess { applyToHome(); recreate(); toast("Settings restored") }
                            .onFailure { toast(it.message ?: "Couldn't restore that backup") }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
                applyToHome(appsOnly = true)
            }
            .show()
    }

    /** [appsOnly]: only favourites or hidden apps changed, so home needn't rebuild its movie rows. */
    private fun applyToHome(appsOnly: Boolean = false) {
        sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName).putExtra(EXTRA_APPS_ONLY, appsOnly))
    }

    private var closing = false

    override fun finish() {
        if (closing) return super.finish()
        closing = true
        root.animate().alpha(0f).setDuration(180).setInterpolator(DecelerateInterpolator())
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

    private class Section(val title: String, val blurb: String, val icon: Int)

    companion object {
        const val ACTION_CHANGED = "com.flix.launcher.SETTINGS_CHANGED"
        /** Set on [ACTION_CHANGED] when only the app tiles changed. */
        const val EXTRA_APPS_ONLY = "apps_only"
        private const val RED = 0xFFE50914.toInt()
        private const val INK = 0xFF141414.toInt()
        private val SECTIONS = listOf(
            Section("Home screen", "App tiles, names and the All apps list", R.drawable.ic_s_home),
            Section("Movies & shows", "Your Nuvio rows, the Top 10 and trailers", R.drawable.ic_s_film),
            Section("Clock & weather", "What the top of the screen shows", R.drawable.ic_o_clock),
            Section("Wallpaper", "The picture behind everything, and the screensaver", R.drawable.ic_o_image),
            Section("About", "Backup, Flix Launcher and this TV", R.drawable.ic_s_info),
        )
    }
}
