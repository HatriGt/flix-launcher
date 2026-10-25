package com.homehalo.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.concurrent.thread

/**
 * Addons & rows. Rows on the home screen come first, in order; every other
 * catalogue is under its addon, folded. OK switches a row on or off; on rows
 * on the home screen, Left/Right moves them. A QR code opens the same controls
 * on a phone.
 */
class RowsActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var root: View
    private lateinit var list: LinearLayout
    private lateinit var qr: ImageView
    private lateinit var qrCaption: TextView
    private lateinit var remote: PhoneRemote
    private val expanded = mutableSetOf<String>()
    private var focusKey: String? = null
    /** Every focusable row in the list, in order, for putting focus back after a rebuild. */
    private val rowViews = mutableListOf<View>()
    /** How each row draws focus; rows keep their own tag for their key. */
    private val looks = HashMap<View, Look>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = PhoneRemote(this) { changed(); render() }
        // The phone page only runs while this screen is open, so keep the screensaver away.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        blurBehind()

        // Left: the title, the remote keys and the phone QR. Nothing here takes focus.
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(54), px(6), px(16), px(28))
            addView(text("Addons & rows", 28f, bold = true))
            addView(text("Choose and order the rows on your home screen", 13f, alpha = 0.55f).apply { setPadding(0, px(4), 0, 0) })
            addView(caption("Remote"))
            addView(card().apply {
                setPadding(px(14), px(10), px(14), px(10))
                addView(keyHint("OK", "turns a row on or off"))
                addView(keyHint("◀ ▶", "moves a row on the home screen"))
                addView(keyHint("Menu", "more options"))
            })
        }
        qr = ImageView(this).apply {
            background = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(Color.WHITE) }
            setPadding(px(6), px(6), px(6), px(6))
        }
        qrCaption = text("", 12f, alpha = 0.6f)
        side.addView(caption("Phone"))
        side.addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(12), px(14), px(12))
            addView(qr, LinearLayout.LayoutParams(px(96), px(96)).apply { marginEnd = px(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_o_phone); alpha = 0.7f }, LinearLayout.LayoutParams(px(20), px(20)))
                addView(qrCaption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        // Right: adding an addon, then the rows.
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(4), px(48), px(40))
            clipChildren = false
            clipToPadding = false
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipChildren = false
            addView(list)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(side, LinearLayout.LayoutParams(px(340), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF140A0C.toInt(), 0xFF0A0A0C.toInt(), 0xFF080809.toInt()))
            addView(topBar("Addons & rows"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        render()
    }

    override fun onStart() {
        super.onStart()
        remote.start()
        val address = remote.address
        if (address != null) {
            qr.setImageBitmap(qrBitmap(address, px(84)))
            qr.visibility = View.VISIBLE
            qrCaption.text = "Scan to manage\nfrom your phone"
        } else {
            qr.visibility = View.GONE
            qrCaption.text = "Connect to Wi-Fi to use your phone"
        }
    }

    override fun onStop() {
        remote.stop()
        super.onStop()
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

    // ---- List ----

    private fun render() {
        list.removeAllViews()
        rowViews.clear()
        looks.clear()
        val add = actionRow("Add an addon by URL", "Paste a Stremio-style manifest link", R.drawable.ic_o_plus) { askForUrl() }
        list.addView(caption("Addons", top = 0))
        list.addView(group(listOf(add)))

        val rows = Addons.rows(this)
        val on = rows.filter { it.enabled }
        list.addView(caption("On your home screen · ${on.size}"))
        if (on.isEmpty()) list.addView(text("Nothing yet — switch on a row below.", 13f, alpha = 0.55f).apply { setPadding(px(12), px(2), 0, px(4)) })
        else list.addView(group(on.mapIndexed { i, row -> rowView(row, position = i + 1, count = on.size) }))

        val off = rows.filter { !it.enabled }.groupBy { it.manifest }
        if (off.isNotEmpty()) {
            list.addView(caption("More from your addons"))
            val items = mutableListOf<View>()
            for ((manifest, group) in off) {
                val open = manifest in expanded
                items.add(addonHeader(manifest, group.first().addonName, group.size, open))
                if (open) group.forEach { items.add(rowView(it, position = null, count = 0)) }
            }
            list.addView(group(items))
        }

        val target = rowViews.firstOrNull { it.tag == focusKey } ?: rowViews.firstOrNull { it !== add } ?: add
        target.post { target.requestFocus() }
    }

    private fun rowView(row: Addons.Row, position: Int?, count: Int): View {
        val kind = if (row.type == "series") "Series" else row.type.replaceFirstChar { it.uppercase() }
        // Rows on the home screen show their place in the tile; the others show a row icon.
        val tileBg = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(0x14FFFFFF) }
        val tile = FrameLayout(this).apply {
            background = tileBg
            if (position != null) addView(text("$position", 14f, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            else addView(ImageView(context).apply { setImageResource(R.drawable.ic_o_rows); alpha = 0.92f },
                FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
        }
        val prev = position?.let { text("‹", 20f, alpha = 0f) }
        val next = position?.let { text("›", 20f, alpha = 0f).apply { setPadding(px(10), 0, 0, 0) } }
        val switch = PillSwitch(row.enabled)
        return rowShell(tileBg).apply {
            tag = row.key
            addView(tile, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(16) })
            addView(textBlock(row.title, "${row.addonName} · $kind"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            prev?.let { addView(it) }
            next?.let { addView(it) }
            addView(switch.view)
            setOnFocusChangeListener { v, has ->
                focusRow(v, has)
                // On focus, the arrows show which ways this row can move.
                prev?.alpha = if (has && position!! > 1) 0.8f else 0f
                next?.alpha = if (has && position!! < count) 0.8f else 0f
                if (has) focusKey = row.key
            }
            setOnKeyListener { _, keyCode, event ->
                val select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                when {
                    event.action != KeyEvent.ACTION_DOWN -> select || keyCode == KeyEvent.KEYCODE_MENU
                    select -> {
                        Addons.setEnabled(this@RowsActivity, row.key, !row.enabled)
                        switch.set(!row.enabled)
                        changed()
                        postDelayed({ render() }, 180) // let the switch finish sliding
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_MENU -> { rowMenu(row); true }
                    position != null && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { moveRow(row, -1); true }
                    position != null && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { moveRow(row, 1); true }
                    else -> false
                }
            }
            setOnLongClickListener { rowMenu(row); true }
            rowViews.add(this)
        }
    }

    private fun addonHeader(manifest: String, name: String, count: Int, open: Boolean): View {
        val (tile, bg) = iconTile(R.drawable.ic_o_layers)
        val arrow = chevron().apply { rotation = if (open) 90f else 0f }
        return rowShell(bg).apply {
            tag = "addon:$manifest"
            addView(tile, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(16) })
            addView(textBlock(name, if (open) "$count more rows · OK to fold" else "$count more rows", bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
            setOnFocusChangeListener { v, has ->
                focusRow(v, has)
                if (has) focusKey = tag as String
            }
            setOnClickListener {
                if (!expanded.add(manifest)) expanded.remove(manifest)
                render()
            }
            setOnLongClickListener { addonMenu(manifest, name); true }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) addonMenu(manifest, name); true } else false
            }
            rowViews.add(this)
        }
    }

    private fun moveRow(row: Addons.Row, delta: Int) {
        Addons.moveEnabled(this, row.key, delta)
        focusKey = row.key
        changed()
        render()
    }

    private fun rowMenu(row: Addons.Row) {
        val actions = buildList<Pair<String, () -> Unit>> {
            add((if (row.enabled) "Remove from home screen" else "Show on home screen") to { Addons.setEnabled(this@RowsActivity, row.key, !row.enabled) })
            if (row.enabled) add("Move to top" to {
                val rows = Addons.rows(this@RowsActivity)
                repeat(rows.indexOfFirst { it.key == row.key }) { Addons.moveEnabled(this@RowsActivity, row.key, -1) }
            })
            add("Delete row" to { Addons.rows(this@RowsActivity).indexOfFirst { it.key == row.key }.let { Addons.remove(this@RowsActivity, it) } })
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(row.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second(); changed(); render() }
            .show()
    }

    private fun addonMenu(manifest: String, name: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(arrayOf("Remove this addon and all its rows")) { _, _ ->
                Addons.removeAddon(this, manifest); changed(); render()
            }.show()
    }

    private fun askForUrl() {
        val input = EditText(this).apply {
            hint = "https://…/manifest.json"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Addon manifest URL")
            .setMessage("Tip: scanning the QR code lets you paste it from your phone.")
            .setView(input)
            .setPositiveButton("Add") { _, _ -> addUrl(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addUrl(url: String) {
        if (url.isBlank()) return
        Toast.makeText(this, "Reading addon…", Toast.LENGTH_SHORT).show()
        thread {
            val result = runCatching { Addons.add(this, url) }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "Added $it rows", Toast.LENGTH_SHORT).show(); changed(); render() }
                    .onFailure { Toast.makeText(this, it.message ?: "Couldn't add that addon", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---- Look (copied from SettingsActivity) ----

    /** The FLIX wordmark, then where we are: Settings › this page. */
    private fun topBar(page: String): View {
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
            addView(text("Settings", 20f, alpha = 0.55f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
            addView(text("›", 20f, alpha = 0.4f).apply { setPadding(px(10), 0, px(10), 0) })
            addView(text(page, 20f, alpha = 0.9f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        }
        return FrameLayout(this).apply {
            setPadding(px(54), px(26), px(48), px(14))
            addView(brand, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        }
    }

    private fun caption(label: String, top: Int = 20) = text(label.uppercase(), 11f, bold = true, alpha = 0.45f).apply {
        letterSpacing = 0.14f
        setPadding(px(12), px(top), 0, px(8))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0x0FFFFFFF); setStroke(px(1), 0x0DFFFFFF) }
        setPadding(px(4), px(4), px(4), px(4))
        clipChildren = false
    }

    /** A rounded card holding rows separated by hairlines. */
    private fun group(rows: List<View>) = card().apply {
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(0x12FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { marginStart = px(66); marginEnd = px(16) })
            addView(row)
        }
    }

    /** A remote key in a small outlined chip, and what it does. */
    private fun keyHint(key: String, what: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(5), 0, px(5))
        addView(text(key, 11f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(px(8), px(2), px(8), px(2))
            background = GradientDrawable().apply { cornerRadius = 6 * dp; setStroke(px(1), 0x40FFFFFF) }
        }, LinearLayout.LayoutParams(px(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text(what, 13f, alpha = 0.75f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) })
    }

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
            looks[this] = Look(bg, tile)
        }
    }

    private fun focusRow(row: View, has: Boolean) {
        val look = looks[row] ?: return
        look.bg.setColor(if (has) 0x24FFFFFF else Color.TRANSPARENT)
        look.bg.setStroke(px(1), if (has) 0x33FFFFFF else Color.TRANSPARENT)
        look.tile?.setColor(if (has) RED else 0x14FFFFFF)
    }

    private fun iconTile(iconRes: Int): Pair<View, GradientDrawable> {
        val bg = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(0x14FFFFFF) }
        val view = FrameLayout(this).apply {
            background = bg
            addView(ImageView(context).apply { setImageResource(iconRes); alpha = 0.92f },
                FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
        }
        return view to bg
    }

    private fun textBlock(title: String, subtitle: String?, bold: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 15f, bold = bold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        subtitle?.let { addView(text(it, 12f, alpha = 0.52f).apply { setPadding(0, px(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) }
    }

    private fun actionRow(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit): View {
        val (tile, bg) = iconTile(iconRes)
        return rowShell(bg).apply {
            addView(tile, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(16) })
            addView(textBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron())
            setOnFocusChangeListener { v, has -> focusRow(v, has) }
            setOnClickListener { onClick() }
        }
    }

    private fun chevron() = ImageView(this).apply {
        setImageResource(R.drawable.ic_o_chevron)
        alpha = 0.45f
        layoutParams = LinearLayout.LayoutParams(px(18), px(18)).apply { marginStart = px(12) }
    }

    /** A pill switch in brand red whose knob slides when flipped. */
    private inner class PillSwitch(private var on: Boolean) {
        private val knob = View(this@RowsActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = 2 * dp
        }
        private val track = GradientDrawable().apply { cornerRadius = 14 * dp }
        val view = FrameLayout(this@RowsActivity).apply {
            background = track
            addView(knob, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER_VERTICAL).apply { marginStart = px(3) })
            layoutParams = LinearLayout.LayoutParams(px(50), px(28)).apply { marginStart = px(14) }
        }

        init {
            paint(animate = false)
        }

        fun set(value: Boolean) {
            on = value
            paint(animate = true)
        }

        private fun paint(animate: Boolean) {
            track.setColor(if (on) RED else 0x33FFFFFF)
            val x = if (on) 22 * dp else 0f
            if (animate) knob.animate().translationX(x).setDuration(200).setInterpolator(OvershootInterpolator(1.4f)).start()
            else knob.translationX = x
        }
    }

    private fun changed() = sendBroadcast(Intent(SettingsActivity.ACTION_CHANGED).setPackage(packageName))

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private var closing = false

    override fun finish() {
        if (closing) return super.finish()
        closing = true
        root.animate().alpha(0f).setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction { super.finish(); overridePendingTransition(0, 0) }.start()
    }

    private fun text(value: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun px(v: Int) = (v * dp).toInt()

    private companion object {
        const val RED = 0xFFE50914.toInt()
    }
}
