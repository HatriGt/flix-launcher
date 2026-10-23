package com.tvlauncher.aura

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
    private lateinit var list: LinearLayout
    private lateinit var panel: View
    private lateinit var qr: ImageView
    private lateinit var qrCaption: TextView
    private lateinit var remote: PhoneRemote
    private val expanded = mutableSetOf<String>()
    private var focusKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = PhoneRemote(this) { changed(); render() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(26), px(28), px(26), px(28))
        }
        // Header: title on the left, phone QR on the right.
        qr = ImageView(this).apply {
            background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(Color.WHITE) }
            setPadding(px(6), px(6), px(6), px(6))
        }
        qrCaption = text("", 10f, alpha = 0.7f).apply { gravity = Gravity.CENTER }
        val qrBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(qr, LinearLayout.LayoutParams(px(96), px(96)))
            addView(qrCaption, LinearLayout.LayoutParams(px(120), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Addons & rows", 22f, bold = true))
            addView(text("OK  turns a row on or off\n◀ ▶  moves a row on the home screen\nMenu  more options", 11f, alpha = 0.65f).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(0, px(6), 0, 0)
            })
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(qrBlock)
        })
        content.addView(action("+  Add an addon by URL") { askForUrl() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = px(14)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        panel = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.settings_panel_solid)
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
            })
            layoutParams = FrameLayout.LayoutParams(px(PANEL_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            elevation = 24 * dp
        }
        setContentView(FrameLayout(this).apply { addView(panel) })
        panel.translationX = PANEL_DP * dp
        panel.animate().translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
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

    // ---- List ----

    private fun render() {
        list.removeAllViews()
        val rows = Addons.rows(this)
        val on = rows.filter { it.enabled }
        list.addView(caption("On your home screen · ${on.size}"))
        if (on.isEmpty()) list.addView(text("Nothing yet — switch on a row below.", 11f, alpha = 0.6f).apply { setPadding(px(6), px(4), 0, px(4)) })
        on.forEachIndexed { i, row -> list.addView(rowView(row, position = i + 1, count = on.size)) }

        val off = rows.filter { !it.enabled }.groupBy { it.manifest }
        if (off.isNotEmpty()) list.addView(caption("More from your addons"))
        for ((manifest, group) in off) {
            val open = manifest in expanded
            list.addView(addonHeader(manifest, group.first().addonName, group.size, open))
            if (open) group.forEach { list.addView(rowView(it, position = null, count = 0)) }
        }

        val target = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == focusKey }
            ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }
        target?.post { target.requestFocus() }
    }

    private fun rowView(row: Addons.Row, position: Int?, count: Int): View {
        val kind = if (row.type == "series") "Series" else row.type.replaceFirstChar { it.uppercase() }
        val number = position?.let { text("$it", 12f, bold = true, alpha = 0.5f).apply { minWidth = px(22) } }
        val title = text(row.title, 13f)
        val sub = text("${row.addonName} · $kind", 10f, alpha = 0.6f)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) }
        val arrows = position?.let { text("${if (it > 1) "◀" else "  "}  ${if (it < count) "▶" else "  "}", 11f, alpha = 0f) }
        val switch = PillSwitch(row.enabled)
        return LinearLayout(this).apply {
            tag = row.key
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = cardBackground()
            setPadding(px(14), px(9), px(14), px(9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(5)
                if (position == null) marginStart = px(14)
            }
            number?.let { addView(it) }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            arrows?.let { addView(it) }
            addView(switch.view)
            setOnFocusChangeListener { _, has ->
                val c = if (has) DARK else Color.WHITE
                listOfNotNull(number, title, sub, arrows).forEach { it.setTextColor(c) }
                arrows?.alpha = if (has) 0.8f else 0f
                switch.focused(has)
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
        }
    }

    private fun addonHeader(manifest: String, name: String, count: Int, open: Boolean): View {
        val title = text(name, 13f, bold = true)
        val sub = text("$count more rows", 10f, alpha = 0.6f)
        val chevron = text(if (open) "▾" else "▸", 14f, alpha = 0.8f)
        return LinearLayout(this).apply {
            tag = "addon:$manifest"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = cardBackground()
            setPadding(px(14), px(10), px(14), px(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5) }
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
            setOnFocusChangeListener { _, has ->
                val c = if (has) DARK else Color.WHITE
                title.setTextColor(c); sub.setTextColor(c); chevron.setTextColor(c)
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

    /** A pill switch whose knob slides when flipped. */
    private inner class PillSwitch(private var on: Boolean) {
        private val knobShape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        private val knob = View(this@RowsActivity).apply { background = knobShape }
        private val track = GradientDrawable().apply { cornerRadius = 12 * dp }
        private var hasFocus = false
        val view = FrameLayout(this@RowsActivity).apply {
            background = track
            addView(knob, FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER_VERTICAL).apply { marginStart = px(3) })
            layoutParams = LinearLayout.LayoutParams(px(42), px(24)).apply { marginStart = px(12) }
        }

        init {
            paint(animate = false)
        }

        fun set(value: Boolean) {
            on = value
            paint(animate = true)
        }

        fun focused(value: Boolean) {
            hasFocus = value
            paint(animate = false)
        }

        private fun paint(animate: Boolean) {
            track.setColor(if (on) ON else if (hasFocus) 0x40000000 else 0x40FFFFFF)
            knobShape.setColor(if (!on && hasFocus) DARK else Color.WHITE)
            val x = if (on) 18 * dp else 0f
            if (animate) knob.animate().translationX(x).setDuration(160).start() else knob.translationX = x
        }
    }

    private fun changed() = sendBroadcast(Intent(SettingsActivity.ACTION_CHANGED).setPackage(packageName))

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun finish() {
        if (panel.translationX != 0f) return super.finish()
        panel.animate().translationX(PANEL_DP * dp).setDuration(220).withEndAction { super.finish(); overridePendingTransition(0, 0) }.start()
    }

    private fun caption(label: String) = text(label.uppercase(), 10f, bold = true, alpha = 0.5f).apply {
        letterSpacing = 0.14f
        setPadding(px(6), px(20), 0, px(2))
    }

    private fun cardBackground() = StateListDrawable().apply {
        setEnterFadeDuration(120); setExitFadeDuration(120)
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xF2FFFFFF.toInt()) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x14FFFFFF) })
    }

    private fun action(label: String, onClick: () -> Unit) = text(label, 13f, bold = true).apply {
        isFocusable = true
        gravity = Gravity.CENTER
        background = cardBackground()
        setTextColor(getColorStateList(R.color.menu_text))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44))
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun px(v: Int) = (v * dp).toInt()

    private companion object {
        const val PANEL_DP = 540
        const val DARK = 0xFF101418.toInt()
        const val ON = 0xFF2E7DF6.toInt()
    }
}
