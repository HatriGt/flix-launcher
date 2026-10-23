package com.tvlauncher.aura

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.concurrent.thread

/**
 * Connects Aura to a Nuvio account: shows a QR code for nuvio.tv/tv-login, waits
 * for approval, then asks which profile's Continue watching to show.
 */
class NuvioLoginActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var panel: View
    private var login: NuvioAccount.Login? = null
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(28), px(30), px(28), px(28))
        }
        panel = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.settings_panel_solid)
            addView(body)
            layoutParams = FrameLayout.LayoutParams(px(420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            elevation = 24 * dp
        }
        setContentView(FrameLayout(this).apply { addView(panel) })
        panel.translationX = 420 * dp
        panel.animate().translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
        if (NuvioAccount.isSignedIn(this)) showAccount() else startLogin()
    }

    override fun onDestroy() {
        polling = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun header(subtitle: String) {
        body.removeAllViews()
        body.addView(text("Nuvio account", 22f, bold = true))
        body.addView(text(subtitle, 11f, alpha = 0.65f).apply { setPadding(0, px(4), 0, px(16)) })
    }

    // ---- Step 1: code ----

    private fun startLogin() {
        header("For Continue watching on the home screen. Aura only reads your watch progress.")
        val status = text("Getting a sign-in code…", 12f, alpha = 0.8f)
        body.addView(status)
        thread {
            val result = runCatching { NuvioAccount.startLogin(this) }
            runOnUiThread {
                result.onSuccess { showCode(it) }
                    .onFailure { status.text = "Couldn't reach Nuvio: ${it.message}" ; body.addView(button("Try again") { startLogin() }) }
            }
        }
    }

    private fun showCode(login: NuvioAccount.Login) {
        this.login = login
        header("Scan with your phone, or open the link on any device where you're signed in to Nuvio, and approve.")
        body.addView(ImageView(this).apply {
            setImageBitmap(qr(login.webUrl, px(210)))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.WHITE) }
            setPadding(px(10), px(10), px(10), px(10))
            layoutParams = LinearLayout.LayoutParams(px(230), px(230)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        body.addView(text(login.webUrl.replace("https://", ""), 10f, alpha = 0.75f).apply {
            setPadding(0, px(14), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val status = text("Waiting for approval…", 13f).apply { setPadding(0, px(18), 0, 0) }
        body.addView(status)
        body.addView(button("Cancel") { finish() }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = px(14) })
        polling = true
        poll(login, status)
    }

    private fun poll(login: NuvioAccount.Login, status: TextView) {
        if (!polling) return
        thread {
            val result = runCatching { NuvioAccount.poll(this, login) }
            runOnUiThread {
                if (!polling) return@runOnUiThread
                when (result.getOrNull()) {
                    "approved", "completed", "authorized" -> {
                        polling = false
                        status.text = "Approved — signing in…"
                        finishLogin(login, status)
                    }
                    "expired", "cancelled", "denied" -> {
                        polling = false
                        status.text = "That code expired."
                        body.addView(button("Get a new code") { startLogin() })
                    }
                    else -> main.postDelayed({ poll(login, status) }, login.pollSeconds * 1000L)
                }
            }
        }
    }

    private fun finishLogin(login: NuvioAccount.Login, status: TextView) {
        thread {
            val result = runCatching {
                NuvioAccount.finishLogin(this, login)
                NuvioAccount.profiles(this)
            }
            runOnUiThread {
                result.onSuccess { pickProfile(it) }
                    .onFailure { status.text = "Sign-in failed: ${it.message}"; body.addView(button("Try again") { startLogin() }) }
            }
        }
    }

    // ---- Step 2: profile ----

    private fun pickProfile(profiles: List<NuvioAccount.Profile>) {
        header("Whose Continue watching should the home screen show?")
        var first: View? = null
        for (p in profiles) {
            val b = button(p.name.ifBlank { "Profile ${p.index}" }) {
                NuvioAccount.setProfile(this, p)
                changed()
                showAccount()
            }
            body.addView(b)
            if (first == null || p.name.equals("Mine", ignoreCase = true)) first = b
        }
        first?.post { first?.requestFocus() }
    }

    // ---- Signed in ----

    private fun showAccount() {
        val profile = NuvioAccount.profile(this)
        header("Signed in. Continue watching shows ${profile?.name?.let { "“$it”" } ?: "no profile yet"}.")
        val change = button("Change profile") {
            thread {
                val result = runCatching { NuvioAccount.profiles(this) }
                runOnUiThread { result.onSuccess { pickProfile(it) } }
            }
        }
        body.addView(change)
        body.addView(button("Sign out of Nuvio") {
            NuvioAccount.signOut(this)
            changed()
            finish()
        })
        body.addView(button("Done") { finish() })
        change.post { change.requestFocus() }
    }

    private fun changed() = sendBroadcast(Intent(SettingsActivity.ACTION_CHANGED).setPackage(packageName))

    private fun qr(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun finish() {
        if (panel.translationX != 0f) return super.finish()
        panel.animate().translationX(420 * dp).setDuration(220).withEndAction { super.finish(); overridePendingTransition(0, 0) }.start()
    }

    private fun button(label: String, onClick: () -> Unit) = text(label, 13f).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.menu_item)
        setTextColor(getColorStateList(R.color.menu_text))
        setPadding(px(14), 0, px(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)).apply { topMargin = px(4) }
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
