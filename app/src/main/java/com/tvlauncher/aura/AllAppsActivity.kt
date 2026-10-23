package com.tvlauncher.aura

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The library: every app, filterable by category, and one search box that finds
 * apps on the TV and movies and shows to open in Nuvio.
 */
class AllAppsActivity : Activity() {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter
    private lateinit var grid: RecyclerView
    private lateinit var search: EditText
    private lateinit var chips: LinearLayout
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var titlesBlock: View
    private lateinit var titlesLabel: TextView
    private lateinit var titlesAdapter: PosterAdapter
    private var apps: List<AppEntry> = emptyList()
    private var category = Category.ALL
    private var query = ""
    private var searchToken = 0

    private enum class Category(val label: String) { ALL("All"), WATCH("Watch"), MUSIC("Music"), GAMES("Games"), TOOLS("Tools"), SYSTEM("System") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        Images.init(this)
        val style = tileStyle()
        val columns = if (style.isBanner) 5 else 8

        count = text("", 12f, alpha = 0.55f)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(text("Library", 28f, bold = true))
            addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(12); bottomMargin = px(5) })
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(text("Type to search · Menu or hold OK for options", 10.5f, alpha = 0.45f),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(6) })
        }
        search = searchField()
        val searchBox = FrameLayout(this).apply {
            addView(search, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_search); setColorFilter(0xB3FFFFFF.toInt()) },
                FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER_VERTICAL).apply { marginStart = px(16) })
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Category.values().forEach { chips.addView(chip(it)) }

        // Movies and shows found by the search sit above the app grid.
        titlesLabel = text("Movies & shows", 14f, bold = true).apply { setPadding(px(4), 0, 0, px(2)) }
        titlesAdapter = PosterAdapter(px(76), px(114), dp, springy = true, onFocus = {}, onClick = ::openTitle, onLongClick = ::openTitle)
        val titlesList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AllAppsActivity, RecyclerView.HORIZONTAL, false)
            adapter = titlesAdapter
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(0, px(10), 0, px(10))
        }
        titlesBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            clipChildren = false
            addView(titlesLabel)
            addView(titlesList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(140)))
        }

        adapter = AppAdapter(style, onFocus = {}, onClick = ::launch, onLongClick = ::showMenu)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AllAppsActivity, columns)
            this.adapter = this@AllAppsActivity.adapter
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(0, px(14), 0, px(40))
            val rowGap = px(if (style.isBanner) 16 else 22)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(out: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    out.bottom = rowGap
                }
            })
        }
        empty = text("", 14f, alpha = 0.6f).apply { gravity = Gravity.CENTER; visibility = View.GONE }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(px(52), px(34), px(52), 0)
            addView(header)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(searchBox, LinearLayout.LayoutParams(px(360), px(46)))
                addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(18) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18) })
            addView(titlesBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
            addView(FrameLayout(context).apply {
                clipChildren = false
                addView(grid)
                addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.all_apps_background)
            addView(content)
        }
        setContentView(root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(220).start()
        content.translationY = 30 * dp
        content.animate().translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
        load()
    }

    // ---- Search ----

    private fun searchField(): EditText {
        val idle = GradientDrawable().apply { cornerRadius = 23 * dp; setColor(0x1AFFFFFF); setStroke(px(1), 0x1FFFFFFF) }
        val active = GradientDrawable().apply { cornerRadius = 23 * dp; setColor(0x26FFFFFF); setStroke(px(2), 0xFF7AA2FF.toInt()) }
        return EditText(this).apply {
            hint = "Search apps, movies and shows"
            setHintTextColor(0x8CFFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = idle
            setPadding(px(44), 0, px(16), 0)
            // Moving over the box shouldn't summon the keyboard; OK (or typing) does.
            showSoftInputOnFocus = false
            setOnFocusChangeListener { v, has ->
                background = if (has) active else idle
                // The TV keyboard opens on focus regardless; close it unless it was asked for.
                if (has && !wantKeyboard) v.post { imm().hideSoftInputFromWindow(v.windowToken, 0) }
                if (!has) wantKeyboard = false
            }
            setOnClickListener { wantKeyboard = true; imm().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = onQuery(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    val target = if (titlesBlock.visibility == View.VISIBLE) titlesBlock else grid
                    firstFocusable(target as ViewGroup)?.requestFocus()
                    true
                } else false
            }
        }
    }

    private var wantKeyboard = false

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun onQuery(text: String) {
        query = text.trim()
        render()
        val token = ++searchToken
        main.removeCallbacksAndMessages(null)
        if (query.length < 2) {
            titlesBlock.visibility = View.GONE
            return
        }
        // Wait for a pause in typing before asking the catalogue.
        main.postDelayed({
            io.execute {
                val found = Catalog.search(query)
                runOnUiThread {
                    if (token != searchToken || isDestroyed) return@runOnUiThread
                    titlesAdapter.items = found
                    titlesLabel.text = if (found.isEmpty()) "No movies or shows for “$query”" else "Movies & shows · ${found.size}"
                    titlesBlock.visibility = View.VISIBLE
                }
            }
        }, 350)
    }

    // ---- Categories ----

    private fun categoryOf(app: AppEntry): Category {
        val pkg = app.packageName
        return when {
            app.category == ApplicationInfo.CATEGORY_GAME || "games" in pkg -> Category.GAMES
            app.category == ApplicationInfo.CATEGORY_AUDIO || "music" in pkg || "spotify" in pkg -> Category.MUSIC
            app.category == ApplicationInfo.CATEGORY_VIDEO || WATCH_HINTS.any { it in pkg } -> Category.WATCH
            pkg.startsWith("com.sony.dtv") || pkg.startsWith("com.google.android") || pkg.startsWith("com.android") -> Category.SYSTEM
            else -> Category.TOOLS
        }
    }

    private fun chip(c: Category): View {
        val bg = GradientDrawable().apply { cornerRadius = 18 * dp }
        val label = text(c.label, 12.5f, bold = true)
        return FrameLayout(this).apply {
            isFocusable = true
            background = bg
            setPadding(px(16), px(8), px(16), px(8))
            addView(label)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8) }
            val paint = {
                val selected = category == c
                bg.setColor(when { isFocused -> 0xF2FFFFFF.toInt(); selected -> 0x40FFFFFF; else -> 0x14FFFFFF })
                label.setTextColor(if (isFocused) 0xFF0B0F1A.toInt() else Color.WHITE)
                label.alpha = if (selected || isFocused) 1f else 0.7f
            }
            tag = paint
            setOnFocusChangeListener { _, _ -> paint() }
            setOnClickListener {
                category = c
                @Suppress("UNCHECKED_CAST")
                for (i in 0 until chips.childCount) (chips.getChildAt(i).tag as () -> Unit)()
                render()
            }
            paint()
        }
    }

    // ---- Grid ----

    private fun render() {
        val q = query.lowercase()
        val shown = apps.filter { (category == Category.ALL || categoryOf(it) == category) && (q.isEmpty() || q in it.label.lowercase()) }
        adapter.items = shown
        count.text = if (q.isEmpty() && category == Category.ALL) "${apps.size} apps" else "${shown.size} of ${apps.size}"
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (q.isNotEmpty()) "No apps named “$query”" else "No ${category.label.lowercase()} apps"
    }

    private fun tileStyle(): TileStyle {
        val shape = AuraSettings.get(this, AuraSettings.ICON_SHAPE)
        val step = listOf("small", "medium", "large").indexOf(AuraSettings.get(this, AuraSettings.ICON_SIZE))
        val (w, h) = if (shape == "banner") listOf(128, 144, 160)[step].let { it to it * 9 / 16 }
        else listOf(56, 66, 76)[step].let { it to it }
        val labels = AuraSettings.get(this, AuraSettings.LABELS).let { if (it == "never") "focus" else it }
        return TileStyle(shape, (w * dp).roundToInt(), (h * dp).roundToInt(), labels,
            AuraSettings.isOn(this, AuraSettings.GLOW), AuraSettings.get(this, AuraSettings.MOTION), dp)
    }

    private fun load() {
        io.execute {
            val loaded = repo.loadApps()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val first = apps.isEmpty()
                apps = loaded
                render()
                if (first) grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    // ---- Actions ----

    private fun launch(app: AppEntry) {
        runCatching { startActivity(app.launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show() }
    }

    private fun openTitle(title: Catalog.Title) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(title.nuvioUri)).setPackage(MainActivity.NUVIO)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "Install Nuvio to watch this", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showMenu(app: AppEntry) {
        val favorite = app.packageName in repo.favorites
        OptionsMenu.show(this, app, listOf(
            "Open" to { launch(app) },
            (if (favorite) "Remove from favourites" else "Add to favourites") to {
                repo.toggleFavorite(app.packageName)
                changed()
                Toast.makeText(this, if (favorite) "Removed from favourites" else "Added to favourites", Toast.LENGTH_SHORT).show()
            },
            "Hide from All apps" to {
                repo.hide(app.packageName)
                changed()
                load()
            },
            "App info" to { launch(app.copy(launchIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))) },
            "Uninstall" to { launch(app.copy(launchIntent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))) },
        ))
    }

    /** Tells the home screen behind us to refresh its rows. */
    private fun changed() = sendBroadcast(Intent(SettingsActivity.ACTION_CHANGED).setPackage(packageName))

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MENU) return super.onKeyUp(keyCode, event)
        val focused = currentFocus ?: return true
        adapter.items.getOrNull(grid.getChildAdapterPosition(focused))?.let(::showMenu)
        return true
    }

    /** Typing a letter anywhere jumps straight into search. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !search.isFocused && event.isPrintingKey && event.unicodeChar != 0) {
            wantKeyboard = true
            search.requestFocus()
            search.append(event.unicodeChar.toChar().toString())
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { imm().hideSoftInputFromWindow(window.decorView.windowToken, 0) }
        if (apps.isNotEmpty()) load() // pick up installs and uninstalls made while away
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun firstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
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

    private companion object {
        val WATCH_HINTS = listOf("hotstar", "sonyliv", "graymatrix", "nuvio", "stremio", "smarttube", "youtube", "vlc", "tvplayer",
            "amazonvideo", "netflix", "appletv", "videos", "tivimate", "mxtech", "arvio", "maertsno", "sonypictures", "tvx", "onstream")
    }
}
