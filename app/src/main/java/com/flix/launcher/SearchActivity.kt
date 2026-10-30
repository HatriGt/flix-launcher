package com.flix.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Netflix-style search: an on-screen keyboard on the left that the remote drives directly
 * (no pop-up system keyboard), and a grid of films and shows on the right that updates as
 * you type. Before anything is typed it recommends titles from the user's own rows.
 * Results come from the user's addons first, then Cinemeta; OK opens one in Nuvio.
 */
class SearchActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private var token = 0
    private var query = ""

    private lateinit var typed: TextView
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var results: PosterAdapter
    private lateinit var grid: RecyclerView
    private lateinit var firstKey: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Images.init(this)
        MainActivity.findNuvio(packageManager)

        results = PosterAdapter(px(POSTER_W), px(POSTER_W * 3 / 2), dp, springy = false,
            onFocus = {}, onClick = ::open, onLongClick = ::open, grows = false)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SearchActivity, COLUMNS)
            adapter = results
            itemAnimator = null
            clipToPadding = true
            setPadding(px(10), px(14), px(10), px(40))
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(out: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) { out.bottom = px(16) }
            })
        }
        heading = text("", 17f, bold = true)
        status = text("", 13f, alpha = 0.55f).apply { visibility = View.GONE }

        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0B0D12.toInt(), 0xFF101521.toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(keyboardPanel(), LinearLayout.LayoutParams(px(310), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(18), px(34), px(30), 0)
                    addView(heading, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10) })
                    addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(10); topMargin = px(4) })
                    addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
        })
        io.execute { Catalog.warmSearch(applicationContext) }
        showRecommended()
        firstKey.post { firstKey.requestFocus() }
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) listen()
    }

    // ---- Keyboard ----

    private fun keyboardPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(40), px(34), px(16), 0)
        setBackgroundColor(0x66000000)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_search); setColorFilter(0x99FFFFFF.toInt()) }, LinearLayout.LayoutParams(px(20), px(20)))
            typed = text("", 20f, bold = true).apply {
                hint = "Search"
                setHintTextColor(0x66FFFFFF)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
            }
            addView(typed, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(10) })
        })
        addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(10); bottomMargin = px(14) })

        // Wide keys first, like Netflix: speak, space, delete.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(key(icon = R.drawable.ic_mic) { listen() }, LinearLayout.LayoutParams(0, px(KEY), 1f))
            addView(key("␣  Space") { type(" ") }, LinearLayout.LayoutParams(0, px(KEY), 2f).apply { marginStart = px(GAP) })
            addView(key("⌫") { delete() }.also { it.setOnLongClickListener { clear(); true } },
                LinearLayout.LayoutParams(0, px(KEY), 1f).apply { marginStart = px(GAP) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(GAP) })

        addView(GridLayout(context).apply {
            columnCount = 6
            ("abcdefghijklmnopqrstuvwxyz1234567890").forEachIndexed { i, c ->
                val k = key(c.toString()) { type(c.toString()) }
                if (i == 0) firstKey = k
                addView(k, GridLayout.LayoutParams().apply {
                    width = px(KEY); height = px(KEY)
                    setMargins(0, 0, px(GAP), px(GAP))
                })
            }
        })
        addView(key("Clear") { clear() }, LinearLayout.LayoutParams(-1, px(KEY)).apply { topMargin = px(4) })
        addView(text("Hold ⌫ to clear · OK on a title opens it in Nuvio", 11f, alpha = 0.4f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(14) })
    }

    private fun key(label: String? = null, icon: Int? = null, onClick: () -> Unit): View {
        val bg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = 6 * dp; setColor(Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = 6 * dp; setColor(0x1FFFFFFF) })
        }
        val view: View = if (icon != null) ImageView(this).apply {
            setImageResource(icon)
            setPadding(px(9), px(9), px(9), px(9))
            setColorFilter(Color.WHITE)
            setOnFocusChangeListener { v, has -> (v as ImageView).setColorFilter(if (has) Color.BLACK else Color.WHITE) }
        } else TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
        }
        return view.apply {
            background = bg
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun type(s: String) { if (query.length < 40) setQuery(query + s) }
    private fun delete() { if (query.isNotEmpty()) setQuery(query.dropLast(1)) }
    private fun clear() = setQuery("")

    /** Letters and digits on a real keyboard or the phone remote type straight in. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val c = event.unicodeChar.toChar()
            if (event.isPrintingKey && (c.isLetterOrDigit() || c == ' ')) { type(c.lowercase()); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) { delete(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Results ----

    private fun setQuery(q: String) {
        query = q
        typed.text = q
        main.removeCallbacksAndMessages(null)
        if (q.trim().length < 2) { showRecommended(); return }
        val mine = ++token
        status.text = "Searching…"
        status.visibility = View.VISIBLE
        // Wait for a pause between key presses before asking the addons.
        main.postDelayed({
            io.execute {
                val found = runCatching {
                    Catalog.search(applicationContext, q) { partial ->
                        runOnUiThread {
                            if (mine != token || isDestroyed) return@runOnUiThread
                            heading.text = "Results for “${q.trim()}”"
                            results.items = partial.filter { it.poster?.startsWith("https://") == true }
                        }
                    }
                }.getOrDefault(emptyList())
                runOnUiThread {
                    if (mine != token || isDestroyed) return@runOnUiThread
                    heading.text = if (found.isEmpty()) "No films or shows for “${q.trim()}”" else "Results for “${q.trim()}”"
                    status.visibility = View.GONE
                    results.items = found.filter { it.poster?.startsWith("https://") == true }
                    grid.scrollToPosition(0)
                }
            }
        }, 450)
    }

    /** Before typing: titles from the user's own rows, interleaved so every row is represented. */
    private fun showRecommended() {
        val mine = ++token
        heading.text = "Recommended"
        status.visibility = View.GONE
        io.execute {
            val rows = Addons.rows(this).filter { it.enabled }.mapNotNull { Catalog.cached(this, it, null) }
            val mixed = buildList { for (i in 0 until 20) rows.forEach { r -> r.getOrNull(i)?.let(::add) } }
                .filter { it.poster?.startsWith("https://") == true }.distinctBy { it.id }.distinctBy { it.name.lowercase().trim() }.take(60)
            runOnUiThread {
                if (mine != token || isDestroyed) return@runOnUiThread
                results.items = mixed
                grid.scrollToPosition(0)
            }
        }
    }

    private fun open(title: Catalog.Title) {
        val intent = runCatching { title.nuvioStreamIntent() }.getOrNull()?.takeIf { it.resolveActivity(packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(title.nuvioUri)).setPackage(MainActivity.NUVIO)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "Install Nuvio to watch this", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    // ---- Voice ----

    private fun listen() {
        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a film or show")
        // Google's own recogniser, so no "Complete action using" chooser appears.
        ask.setPackage(GOOGLE_VOICE).takeIf { it.resolveActivity(packageManager) == null }?.setPackage(null)
        @Suppress("DEPRECATION")
        if (ask.resolveActivity(packageManager) == null || runCatching { startActivityForResult(ask, VOICE) }.isFailure) {
            Toast.makeText(this, "Voice search isn't available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VOICE) return
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?.let { setQuery(it.lowercase().take(40)) }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun text(s: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    companion object {
        /** Start listening for a spoken search straight away. */
        const val EXTRA_VOICE = "voice"
        private const val VOICE = 8
        private const val GOOGLE_VOICE = "com.google.android.katniss"
        private const val COLUMNS = 6
        private const val POSTER_W = 92
        private const val KEY = 38
        private const val GAP = 6
    }
}
