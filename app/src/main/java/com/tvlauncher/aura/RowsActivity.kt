package com.tvlauncher.aura

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Addons & rows: every catalogue row on the home screen, in order. Rows can be
 * shown, hidden, moved and removed; new addons are added by manifest URL, here
 * or from a phone on the same Wi-Fi.
 */
class RowsActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var list: LinearLayout
    private lateinit var phoneLine: TextView
    private lateinit var panel: View
    private lateinit var remote: PhoneRemote
    private var focusIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = PhoneRemote(this) { changed(); render() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(26), px(28), px(26), px(28))
        }
        content.addView(text("Addons & rows", 22f, bold = true))
        content.addView(text("OK on a row for options · rows appear on the home screen in this order", 11f, alpha = 0.6f)
            .apply { setPadding(0, px(2), 0, px(12)) })
        phoneLine = text("", 12f).apply {
            setPadding(px(14), px(10), px(14), px(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(0x2652A8FF)
            }
        }
        content.addView(phoneLine)
        content.addView(action("+  Add addon by URL") { askForUrl() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = px(10)
        })
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(8), 0, 0)
        }
        content.addView(list)
        content.addView(action("Reset to default rows") {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Remove all added addons and go back to the default rows?")
                .setPositiveButton("Reset") { _, _ -> Addons.reset(this); changed(); render() }
                .setNegativeButton("Cancel", null).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = px(14) })

        panel = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.settings_panel)
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
            })
            layoutParams = FrameLayout.LayoutParams(px(460), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            elevation = 24 * dp
        }
        setContentView(FrameLayout(this).apply { addView(panel) })
        panel.translationX = 460 * dp
        panel.animate().translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
        render()
    }

    override fun onStart() {
        super.onStart()
        remote.start()
        phoneLine.text = remote.address?.let { "📱  Manage from your phone: open  $it  on the same Wi-Fi" }
            ?: "Connect the TV to Wi-Fi to manage rows from your phone"
    }

    override fun onStop() {
        remote.stop()
        super.onStop()
    }

    private fun render() {
        list.removeAllViews()
        val rows = Addons.rows(this)
        rows.forEachIndexed { index, row ->
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setBackgroundResource(R.drawable.menu_item)
                setPadding(px(14), px(8), px(14), px(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = px(2) }
                alpha = if (row.enabled) 1f else 0.5f
                val title = text("${index + 1}.  ${row.title}", 13f)
                val sub = text("${row.addonName} · ${if (row.type == "series") "Series" else row.type.replaceFirstChar { it.uppercase() }}" +
                    if (row.enabled) "" else " · hidden", 10f, alpha = 0.6f)
                addView(title)
                addView(sub)
                setOnFocusChangeListener { _, has ->
                    val c = if (has) 0xFF101418.toInt() else Color.WHITE
                    title.setTextColor(c)
                    sub.setTextColor(c)
                    if (has) focusIndex = index
                }
                setOnClickListener { rowMenu(index, row, rows.size) }
            }
            list.addView(view)
        }
        list.getChildAt(focusIndex.coerceAtMost(rows.size - 1))?.post { list.getChildAt(focusIndex.coerceAtMost(rows.size - 1))?.requestFocus() }
    }

    private fun rowMenu(index: Int, row: Addons.Row, count: Int) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (index > 0) add("Move up" to { Addons.move(this@RowsActivity, index, -1); focusIndex = index - 1 })
            if (index < count - 1) add("Move down" to { Addons.move(this@RowsActivity, index, 1); focusIndex = index + 1 })
            if (index > 0) add("Move to top" to {
                repeat(index) { Addons.move(this@RowsActivity, index - it, -1) }
                focusIndex = 0
            })
            add((if (row.enabled) "Hide row" else "Show row") to { Addons.toggle(this@RowsActivity, index) })
            add("Remove row" to { Addons.remove(this@RowsActivity, index) })
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(row.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                changed()
                render()
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

    private fun changed() = sendBroadcast(Intent(SettingsActivity.ACTION_CHANGED).setPackage(packageName))

    override fun finish() {
        if (panel.translationX != 0f) return super.finish()
        panel.animate().translationX(460 * dp).setDuration(220).withEndAction { super.finish(); overridePendingTransition(0, 0) }.start()
    }

    private fun action(label: String, onClick: () -> Unit) = text(label, 13f).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.menu_item)
        setTextColor(getColorStateList(R.color.menu_text))
        setPadding(px(14), 0, px(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40))
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
}
