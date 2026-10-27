package com.flix.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * Connects Flix to Nuvio in as few steps as possible: a QR code that renews
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        blurBehind()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(54), px(6), px(54), px(28))
            clipChildren = false
            clipToPadding = false
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF140A0C.toInt(), 0xFF0A0A0C.toInt(), 0xFF080809.toInt()))
            addView(topBar("Nuvio"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(panel)
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        if (NuvioAccount.isSignedIn(this)) showAccount() else showQr()
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
                background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xFF0B0F1A.toInt()) }
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(px(56), px(56)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 28f, bold = true))
                addView(text(subtitle, 13f, alpha = 0.55f).apply { setPadding(0, px(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(18) })
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
        val status = text("Getting a code…", 15f, alpha = 0.9f).apply { setLineSpacing(0f, 1.2f) }
        val other = button("Use email and password instead", R.drawable.ic_o_keyboard) { showPassword() }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(caption("How it works"))
            addView(steps())
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(group(listOf(other)))
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(qrBox, LinearLayout.LayoutParams(px(220), px(220)))
            addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = px(36) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(24) })
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
        background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0x0FFFFFFF); setStroke(px(1), 0x0DFFFFFF) }
        setPadding(px(16), px(10), px(16), px(10))
        listOf("Open your phone's camera and scan", "Sign in to Nuvio if asked", "Tap Approve — this screen moves on by itself")
            .forEachIndexed { i, line ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, px(4), 0, px(4))
                    addView(text("${i + 1}", 13f, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { cornerRadius = 9 * dp; setColor(0x14FFFFFF) }
                    }, LinearLayout.LayoutParams(px(28), px(28)))
                    addView(text(line, 13f, alpha = 0.8f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(14) })
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
        val error = text("", 13f).apply { setTextColor(0xFFF87171.toInt()) }
        body.addView(email, LinearLayout.LayoutParams(px(FORM_DP), px(52)).apply { topMargin = px(28) })
        body.addView(password, LinearLayout.LayoutParams(px(FORM_DP), px(52)).apply { topMargin = px(10) })
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
        body.addView(submit, LinearLayout.LayoutParams(px(FORM_DP), px(50)).apply { topMargin = px(14) })
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        body.addView(group(listOf(button("Use my phone instead", R.drawable.ic_o_phone) { showQr() })), LinearLayout.LayoutParams(px(FORM_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        email.post { email.requestFocus() }
    }

    // ---- After sign-in ----

    private fun afterSignIn(profiles: List<NuvioAccount.Profile>) {
        if (profiles.size == 1) return choose(profiles[0])
        val gen = screen("Who's watching?", "Continue watching follows this profile")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START; clipChildren = false; clipToPadding = false }
        var first: View? = null
        profiles.forEach { p ->
            val tile = profileTile(p)
            grid.addView(tile)
            if (first == null || p.name.equals(NuvioAccount.profile(this)?.name ?: "Mine", ignoreCase = true)) first = tile
        }
        body.addView(caption("Profiles"))
        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        first?.post { if (gen == generation) first?.requestFocus() }
    }

    private fun profileTile(p: NuvioAccount.Profile): View {
        val avatar = text(p.name.take(1).uppercase().ifBlank { "?" }, 26f, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color) }
        }
        val name = text(p.name.ifBlank { "Profile ${p.index}" }, 15f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
        val bg = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.TRANSPARENT) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            background = bg
            setPadding(px(18), px(16), px(18), px(14))
            layoutParams = LinearLayout.LayoutParams(px(128), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(14) }
            addView(avatar, LinearLayout.LayoutParams(px(76), px(76)))
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) 0x24FFFFFF else Color.TRANSPARENT)
                bg.setStroke(px(1), if (has) 0x33FFFFFF else Color.TRANSPARENT)
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.5f)).start()
                (avatar.background as GradientDrawable).setStroke(if (has) px(3) else 0, RED)
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
        body.addView(check, LinearLayout.LayoutParams(px(96), px(96)).apply { topMargin = px(48); marginStart = px(4) })
        check.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(OvershootInterpolator(2f)).start()
        main.postDelayed({ finish() }, 1700)
    }

    private fun showAccount() {
        val profile = NuvioAccount.profile(this)
        screen("Nuvio", "Connected · Continue watching from “${profile?.name ?: "?"}”")
        val change = button("Switch profile", R.drawable.ic_o_user, "Choose whose Continue watching shows") {
            val gen = generation
            thread {
                val result = runCatching { NuvioAccount.profiles(this) }
                runOnUiThread { if (gen == generation) result.onSuccess(::afterSignIn) }
            }
        }
        val out = button("Sign out", R.drawable.ic_o_logout, "Remove Continue watching from the home screen") {
            NuvioAccount.signOut(this); changed(); finish()
        }
        body.addView(caption("Account"))
        body.addView(group(listOf(change, out)), LinearLayout.LayoutParams(px(FORM_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        change.post { change.requestFocus() }
    }

    // ---- Widgets ----

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
        imeOptions = if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        setTextColor(Color.WHITE)
        setHintTextColor(0x8CFFFFFF.toInt())
        textSize = 15f
        setPadding(px(18), 0, px(18), 0)
        val normal = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x0FFFFFFF); setStroke(px(1), 0x1AFFFFFF) }
        val focused = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x24FFFFFF); setStroke(px(2), Color.WHITE) }
        background = normal
        setOnFocusChangeListener { _, has -> background = if (has) focused else normal }
    }

    /** The main action: brand red, and a white pill with dark text when focused. */
    private fun primary(label: String, onClick: () -> Unit) = text(label, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        val bg = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(RED) }
        background = bg
        setOnFocusChangeListener { v, has ->
            bg.setColor(if (has) Color.WHITE else RED)
            (v as TextView).setTextColor(if (has) INK else Color.WHITE)
            v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f).setDuration(150).start()
        }
        setOnClickListener { onClick() }
    }

    /** A settings-style row: icon tile that turns red on focus, label, chevron. */
    private fun button(label: String, iconRes: Int, subtitle: String? = null, onClick: () -> Unit): View {
        val tileBg = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(0x14FFFFFF) }
        val tile = FrameLayout(this).apply {
            background = tileBg
            addView(ImageView(context).apply { setImageResource(iconRes); alpha = 0.92f }, FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
        }
        val bg = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.TRANSPARENT) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = bg
            minimumHeight = px(60)
            setPadding(px(12), px(10), px(16), px(10))
            addView(tile, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(label, 15f))
                subtitle?.let { addView(text(it, 12f, alpha = 0.52f).apply { setPadding(0, px(2), 0, 0) }) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_o_chevron); alpha = 0.45f },
                LinearLayout.LayoutParams(px(18), px(18)).apply { marginStart = px(12) })
            setOnFocusChangeListener { _, has ->
                bg.setColor(if (has) 0x24FFFFFF else Color.TRANSPARENT)
                bg.setStroke(px(1), if (has) 0x33FFFFFF else Color.TRANSPARENT)
                tileBg.setColor(if (has) RED else 0x14FFFFFF)
            }
            setOnClickListener { onClick() }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_UP) { onClick(); true } else false
            }
        }
    }

    /** A rounded card holding rows separated by hairlines. */
    private fun group(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0x0FFFFFFF); setStroke(px(1), 0x0DFFFFFF) }
        setPadding(px(4), px(4), px(4), px(4))
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(0x12FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { marginStart = px(66); marginEnd = px(16) })
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun caption(label: String) = text(label.uppercase(), 11f, bold = true, alpha = 0.45f).apply {
        letterSpacing = 0.14f
        setPadding(px(12), px(20), 0, px(8))
    }

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
        panel.animate().alpha(0f).setDuration(180).setInterpolator(DecelerateInterpolator())
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
        const val FORM_DP = 520
        const val RED = 0xFFE50914.toInt()
        const val INK = 0xFF141414.toInt()
    }
}
