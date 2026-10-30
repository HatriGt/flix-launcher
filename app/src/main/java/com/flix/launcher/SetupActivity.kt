package com.flix.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run setup, three steps with the remote: connect Nuvio, pick the rows, and make the
 * Home button open Flix. Every step can be skipped and done later from Settings. Shown once,
 * on a fresh install; people updating from an older Flix never see it.
 */
class SetupActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    private var step = 0
    private lateinit var dots: LinearLayout
    private lateinit var number: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var primary: TextView
    private lateinit var secondary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt("step") ?: 0
        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF141A26.toInt(), 0xFF07090D.toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(96), 0, px(96), 0)
                gravity = Gravity.CENTER_VERTICAL
                addView(text("FLIX LAUNCHER  ·  SETUP", 12f, bold = true, alpha = 0.55f).apply { letterSpacing = 0.25f })
                number = text("", 15f, bold = true, color = 0xFFE50914.toInt()).also { addView(it, lp(top = 36)) }
                title = text("", 38f, bold = true).also { addView(it, lp(top = 6)) }
                body = text("", 17f, alpha = 0.72f).apply { setLineSpacing(0f, 1.25f) }.also { addView(it, LinearLayout.LayoutParams(px(720), -2).apply { topMargin = px(14) }) }
                status = text("", 15f, bold = true).also { addView(it, lp(top = 18)) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    primary = button(true).also { addView(it) }
                    secondary = button(false).also { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(14) }) }
                }, lp(top = 34))
                dots = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }.also { addView(it, lp(top = 48)) }
            }, FrameLayout.LayoutParams(-1, -1))
        })
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("step", step)
    }

    // Each step is redrawn on return, so a sign-in or a switch flipped in Settings shows as done.
    override fun onResume() {
        super.onResume()
        show()
    }

    private fun show() {
        number.text = "STEP ${step + 1} OF 3"
        when (step) {
            0 -> {
                val signedIn = NuvioAccount.isSignedIn(this)
                title.text = "Pick up where you left off"
                body.text = "Connect your Nuvio account and what you were watching waits on the home screen, one press from playing. " +
                    "Your addons' films and shows fill the rows below. You'll scan a code with your phone; no typing on the TV."
                status(if (signedIn) "✓  Signed in as ${NuvioAccount.profile(this)?.name.orEmpty()}" else null)
                primary(if (signedIn) "Next" else "Connect Nuvio") {
                    if (signedIn) next() else startActivity(Intent(this, NuvioLoginActivity::class.java))
                }
                secondary(if (signedIn) null else "Skip for now") { next() }
            }
            1 -> {
                val rows = Addons.rows(this).count { it.enabled }
                title.text = "Choose what's on"
                body.text = "Pick the film and series rows you actually want to browse, and their order. " +
                    "Your Nuvio addons' catalogues are listed for you; everything else stays off the screen."
                status(if (rows > 0) "✓  $rows rows on the home screen" else null)
                primary("Choose rows") { startActivity(Intent(this, RowsActivity::class.java)) }
                secondary("Next") { next() }
            }
            else -> {
                val on = HomeKeyService.running
                title.text = "One press to your show"
                body.text = "Google TV sends the Home button to its own screen, full of things to buy. Switch on \"Flix Launcher\" in " +
                    "Accessibility and Home brings you back to what you watch instead. It only listens for the Home key."
                status(if (on) "✓  Home opens Flix: you're ready to watch" else null)
                primary(if (on) "Finish" else "Open Accessibility") {
                    if (on) finishSetup()
                    else runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                secondary(if (on) null else "Finish") { finishSetup() }
            }
        }
        dots.removeAllViews()
        repeat(3) { i ->
            dots.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(if (i == step) Color.WHITE else 0x40FFFFFF) }
            }, LinearLayout.LayoutParams(px(if (i == step) 28 else 8), px(6)).apply { marginEnd = px(8) })
        }
        primary.post { primary.requestFocus() }
    }

    private fun next() { step++; show() }

    private fun finishSetup() {
        markDone(this)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (step > 0) { step--; show() } else finishSetup()
    }

    private fun status(text: String?) {
        status.text = text.orEmpty()
        status.setTextColor(0xFF4ADE80.toInt())
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun primary(label: String, action: () -> Unit) { primary.text = label; primary.setOnClickListener { action() } }

    private fun secondary(label: String?, action: () -> Unit) {
        secondary.visibility = if (label == null) View.GONE else View.VISIBLE
        secondary.text = label.orEmpty()
        secondary.setOnClickListener { action() }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(top) }

    private fun text(s: String, size: Float, bold: Boolean = false, alpha: Float = 1f, color: Int = Color.WHITE) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        this.alpha = alpha
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun button(primary: Boolean) = TextView(this).apply {
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(px(28), px(12), px(28), px(12))
        isFocusable = true
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = 10 * dp; setColor(if (primary) Color.WHITE else 0x66FFFFFF); setStroke(px(3), Look.focus(this@SetupActivity))
            })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = 10 * dp; setColor(if (primary) 0xE6FFFFFF.toInt() else 0x33FFFFFF) })
        }
        layoutParams = ViewGroup.LayoutParams(-2, -2)
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("flix", Context.MODE_PRIVATE)

        private fun markDone(context: Context) = prefs(context).edit().putBoolean("setup_done", true).apply()

        /**
         * Whether to show setup: only on a fresh install. Anyone already using Flix (Home set
         * up or Nuvio connected) is marked done without seeing it.
         */
        fun needed(context: Context): Boolean {
            if (prefs(context).getBoolean("setup_done", false)) return false
            if (HomeGuard.wanted(context) || HomeKeyService.running || NuvioAccount.isSignedIn(context)) { markDone(context); return false }
            return true
        }
    }
}
