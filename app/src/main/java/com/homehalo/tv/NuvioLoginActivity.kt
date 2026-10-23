package com.homehalo.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.concurrent.thread

/**
 * Connects HomeHalo to Nuvio in as few steps as possible: a QR code that renews
 * itself before it expires (scan, tap Approve, done), or email and password
 * typed on the TV. Then the profile, then a check mark, and back home.
 */
class NuvioLoginActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var panel: View
    private var login: NuvioAccount.Login? = null
    private var generation = 0 // bumps whenever the screen changes, so stale callbacks stop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screensaver away while someone is scanning or typing.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND or android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDimAmount(0.45f)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(34), px(34), px(34), px(30))
        }
        panel = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFA161D28.toInt(), 0xFC0C1118.toInt())).apply {
                cornerRadii = floatArrayOf(28 * dp, 28 * dp, 0f, 0f, 0f, 0f, 28 * dp, 28 * dp)
                setStroke(px(1), 0x1FFFFFFF)
            }
            addView(body)
            elevation = 40 * dp
        }
        setContentView(FrameLayout(this).apply {
            addView(panel, FrameLayout.LayoutParams(px(PANEL_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        })
        panel.translationX = PANEL_DP * dp * 0.35f
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(OvershootInterpolator(0.6f)).start()
        if (NuvioAccount.isSignedIn(this)) showAccount() else showQr()
    }

    override fun onDestroy() {
        generation++
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Clears the panel and draws the shared header: Nuvio's own icon, a title and a line of help. */
    private fun screen(title: String, subtitle: String): Int {
        generation++
        main.removeCallbacksAndMessages(null)
        body.removeAllViews()
        val logo = runCatching { packageManager.getApplicationIcon(MainActivity.NUVIO) }.getOrNull()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                logo?.let(::setImageDrawable)
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xFF0B0F1A.toInt()) }
                clipToOutline = true
            }, LinearLayout.LayoutParams(px(40), px(40)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 20f, bold = true))
                addView(text(subtitle, 11.5f, alpha = 0.6f))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
        })
        body.alpha = 0f
        body.translationY = 10 * dp
        body.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        return generation
    }

    // ---- Way 1: QR, renews itself ----

    private fun showQr() {
        val gen = screen("Sign in to Nuvio", "Point your phone's camera at the code, then tap Approve")
        val qr = ImageView(this).apply {
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.WHITE) }
            setPadding(px(14), px(14), px(14), px(14))
            alpha = 0.15f
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val qrBox = FrameLayout(this).apply {
            addView(qr, FrameLayout.LayoutParams(px(220), px(220)))
            addView(spinner, FrameLayout.LayoutParams(px(36), px(36), Gravity.CENTER))
        }
        body.addView(qrBox, LinearLayout.LayoutParams(px(220), px(220)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = px(26)
        })
        val status = text("Getting a code…", 12.5f, alpha = 0.85f).apply { gravity = Gravity.CENTER }
        body.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
        body.addView(steps(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18) })
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val other = button("Use email and password instead", R.drawable.ic_keyboard) { showPassword() }
        body.addView(other)
        other.post { other.requestFocus() }

        fun fetch() {
            thread {
                val result = runCatching { NuvioAccount.startLogin(this) }
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    result.onSuccess { l ->
                        login = l
                        qr.setImageBitmap(qrBitmap(l.webUrl, px(192)))
                        spinner.visibility = View.GONE
                        qr.animate().alpha(1f).setDuration(300).start()
                        status.text = "Waiting for you to approve on your phone\nor open ${l.webUrl.removePrefix("https://")}"
                        poll(gen, l, status)
                        // Swap in a fresh code a little before this one runs out, so it never shows as expired.
                        val renewIn = (l.expiresAtMs - System.currentTimeMillis() - 20_000).coerceAtLeast(30_000)
                        main.postDelayed({ if (gen == generation) { qr.animate().alpha(0.15f).setDuration(200).start(); spinner.visibility = View.VISIBLE; fetch() } }, renewIn)
                    }.onFailure {
                        status.text = "Can't reach Nuvio right now. Retrying…"
                        main.postDelayed({ if (gen == generation) fetch() }, 4000)
                    }
                }
            }
        }
        fetch()
    }

    /** Three short numbered steps under the code. */
    private fun steps(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(0x12FFFFFF) }
        setPadding(px(16), px(12), px(16), px(12))
        listOf("Open your phone's camera and scan", "Sign in to Nuvio if asked", "Tap Approve — this screen moves on by itself")
            .forEachIndexed { i, line ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, px(4), 0, px(4))
                    addView(text("${i + 1}", 10.5f, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2EFFFFFF) }
                    }, LinearLayout.LayoutParams(px(20), px(20)))
                    addView(text(line, 11.5f, alpha = 0.8f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(12) })
                })
            }
    }

    private fun poll(gen: Int, l: NuvioAccount.Login, status: TextView) {
        main.postDelayed({
            if (gen != generation || login !== l) return@postDelayed
            thread {
                val state = runCatching { NuvioAccount.poll(this, l) }.getOrNull()
                runOnUiThread {
                    if (gen != generation || login !== l) return@runOnUiThread
                    when (state) {
                        "approved", "completed", "authorized" -> {
                            status.text = "Approved — signing you in…"
                            finishCodeLogin(gen, l, status)
                        }
                        else -> poll(gen, l, status) // pending, or expired and about to be replaced
                    }
                }
            }
        }, l.pollSeconds * 1000L)
    }

    private fun finishCodeLogin(gen: Int, l: NuvioAccount.Login, status: TextView) {
        thread {
            val result = runCatching { NuvioAccount.finishLogin(this, l); NuvioAccount.profiles(this) }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                result.onSuccess(::afterSignIn).onFailure { status.text = "That didn't work: ${it.message}. Getting a new code…"; showQr() }
            }
        }
    }

    // ---- Way 2: email and password on the TV ----

    private fun showPassword() {
        screen("Sign in to Nuvio", "Your password goes straight to Nuvio and isn't stored")
        val email = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val error = text("", 11.5f).apply { setTextColor(0xFFF87171.toInt()) }
        body.addView(email, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50)).apply { topMargin = px(28) })
        body.addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50)).apply { topMargin = px(10) })
        body.addView(error, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        lateinit var submit: View
        fun go() {
            if (email.text.isBlank() || password.text.isEmpty()) { error.text = "Enter your email and password"; return }
            error.text = ""
            (submit as TextView).text = "Signing in…"
            val e = email.text.toString()
            val p = password.text.toString()
            password.setText("")
            val gen = generation
            thread {
                val result = runCatching { NuvioAccount.passwordLogin(this, e, p); NuvioAccount.profiles(this) }
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    result.onSuccess(::afterSignIn).onFailure {
                        (submit as TextView).text = "Sign in"
                        error.text = if (it.message?.contains("Invalid", true) == true) "Email or password is wrong" else (it.message ?: "Couldn't sign in")
                    }
                }
            }
        }
        submit = primary("Sign in") { go() }
        password.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { go(); true } else false }
        body.addView(submit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)).apply { topMargin = px(14) })
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        body.addView(button("Use my phone instead", R.drawable.ic_phone) { showQr() })
        email.post { email.requestFocus() }
    }

    // ---- After sign-in ----

    private fun afterSignIn(profiles: List<NuvioAccount.Profile>) {
        if (profiles.size == 1) return choose(profiles[0])
        val gen = screen("Who's watching?", "Continue watching follows this profile")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        var first: View? = null
        profiles.forEach { p ->
            val tile = profileTile(p)
            grid.addView(tile)
            if (first == null || p.name.equals(NuvioAccount.profile(this)?.name ?: "Mine", ignoreCase = true)) first = tile
        }
        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(40) })
        first?.post { if (gen == generation) first?.requestFocus() }
    }

    private fun profileTile(p: NuvioAccount.Profile): View {
        val avatar = text(p.name.take(1).uppercase().ifBlank { "?" }, 26f, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color) }
        }
        val name = text(p.name.ifBlank { "Profile ${p.index}" }, 13f).apply { gravity = Gravity.CENTER }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            setPadding(px(10), px(10), px(10), px(10))
            addView(avatar, LinearLayout.LayoutParams(px(76), px(76)))
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.5f)).start()
                (avatar.background as GradientDrawable).setStroke(if (has) px(3) else 0, Color.WHITE)
                name.alpha = if (has) 1f else 0.6f
            }
            setOnClickListener { choose(p) }
        }
    }

    private fun choose(p: NuvioAccount.Profile) {
        NuvioAccount.setProfile(this, p)
        changed()
        success(p.name)
    }

    /** A check mark that pops in, then the panel closes on its own. */
    private fun success(name: String) {
        screen("You're all set", "Continue watching from “$name” is on your home screen")
        val check = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF16A34A.toInt()) }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_check); setColorFilter(Color.WHITE) },
                FrameLayout.LayoutParams(px(46), px(46), Gravity.CENTER))
            scaleX = 0.3f; scaleY = 0.3f; alpha = 0f
        }
        body.addView(check, LinearLayout.LayoutParams(px(96), px(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(70) })
        check.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(OvershootInterpolator(2f)).start()
        main.postDelayed({ finish() }, 1700)
    }

    private fun showAccount() {
        val profile = NuvioAccount.profile(this)
        screen("Nuvio", "Connected · Continue watching from “${profile?.name ?: "?"}”")
        val change = button("Switch profile", R.drawable.ic_sec_behaviour) {
            val gen = generation
            thread {
                val result = runCatching { NuvioAccount.profiles(this) }
                runOnUiThread { if (gen == generation) result.onSuccess(::afterSignIn) }
            }
        }
        body.addView(change, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(28) })
        body.addView(button("Sign out", R.drawable.ic_restore) {
            NuvioAccount.signOut(this); changed(); finish()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(8) })
        change.post { change.requestFocus() }
    }

    // ---- Widgets ----

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
        imeOptions = if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        setTextColor(Color.WHITE)
        setHintTextColor(0x80FFFFFF.toInt())
        textSize = 14f
        setPadding(px(16), 0, px(16), 0)
        val normal = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x1AFFFFFF); setStroke(px(1), 0x1FFFFFFF) }
        val focused = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x24FFFFFF); setStroke(px(2), 0xFF7AA2FF.toInt()) }
        background = normal
        setOnFocusChangeListener { _, has -> background = if (has) focused else normal }
    }

    private fun primary(label: String, onClick: () -> Unit) = text(label, 14f, bold = true).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        setTextColor(0xFF0B0F1A.toInt())
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFE8EEFF.toInt(), Color.WHITE)).apply { cornerRadius = 14 * dp }
        setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f).setDuration(150).start() }
        setOnClickListener { onClick() }
    }

    private fun button(label: String, iconRes: Int, onClick: () -> Unit): View {
        val icon = ImageView(this).apply { setImageResource(iconRes); setColorFilter(Color.WHITE) }
        val t = text(label, 13f)
        val bg = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x14FFFFFF) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = bg
            setPadding(px(16), px(12), px(16), px(12))
            addView(icon, LinearLayout.LayoutParams(px(18), px(18)))
            addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(12) })
            setOnFocusChangeListener { _, has ->
                bg.setColor(if (has) 0xF2FFFFFF.toInt() else 0x14FFFFFF)
                val c = if (has) 0xFF0B0F1A.toInt() else Color.WHITE
                t.setTextColor(c); icon.setColorFilter(c)
            }
            setOnClickListener { onClick() }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_UP) { onClick(); true } else false
            }
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
        panel.animate().translationX(PANEL_DP * dp * 0.3f).alpha(0f).setDuration(240)
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
        const val PANEL_DP = 480
    }
}
