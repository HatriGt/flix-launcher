package com.flix.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * First-run setup, four steps with the remote (connect Nuvio, choose a profile picture, pick
 * the rows, make the Home button open Flix), then a short tour of the home screen. Every step
 * can be skipped and done later from Settings. Shown once, on a fresh install; people
 * updating from an older Flix never see it.
 *
 * The look: a tilted wall of the bundled wallpaper previews drifting slowly behind a dark
 * veil (all offline, so it works before anything is signed in), the words on the left, and
 * on the right an illustration of what the step does: a phone code, your face, a slice of
 * the home screen, the remote's Home button.
 */
class SetupActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()
    private fun px(v: Float) = (v * dp).toInt()

    private var step = 0
    /** Which way the last move went, so a skipped step is passed in that direction. */
    private var forward = true

    private lateinit var page: LinearLayout
    private lateinit var words: LinearLayout
    private lateinit var visual: FrameLayout
    private lateinit var below: FrameLayout
    private lateinit var progress: LinearLayout
    private lateinit var progressLabel: TextView
    private lateinit var kicker: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    private var drift: ValueAnimator? = null
    private var pulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt("step") ?: 0
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF06070A.toInt()); clipChildren = false }
        root.addView(mosaic(), FrameLayout.LayoutParams(-1, -1))
        // Darkest on the left where the words are, the wall showing through on the right.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFA06070A.toInt(), 0xE606070A.toInt(), 0xA606070A.toInt(), 0x8C06070A.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x9906070A.toInt(), 0, 0, 0xF206070A.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        // A low red glow behind the illustration, the only colour on the page.
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 420 * dp
                colors = intArrayOf(0x33E50914, 0x00E50914)
            }
        }, FrameLayout.LayoutParams(px(840), px(840), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -px(160) })

        // Top bar: the wordmark, and the steps as a segmented line.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("FLIX", 22f, bold = true, color = RED).apply { letterSpacing = 0.06f; typeface = Typeface.create("sans-serif-black", Typeface.BOLD) })
            addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(px(1), px(22)).apply { marginStart = px(16); marginEnd = px(16) })
            addView(text("Setup", 15f, alpha = 0.7f))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            progressLabel = text("", 13f, bold = true, alpha = 0.6f).apply { letterSpacing = 0.12f }.also { addView(it) }
            progress = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                .also { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(16) }) }
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = px(34); marginStart = px(64); marginEnd = px(64) })

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(px(64), px(116), px(64), px(28))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false; clipToPadding = false
                words = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false; clipToPadding = false
                    kicker = text("", 13f, bold = true, color = RED).apply { letterSpacing = 0.18f }.also { addView(it) }
                    title = text("", 34f, bold = true).apply {
                        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                        letterSpacing = -0.01f
                        setLineSpacing(0f, 0.95f)
                    }.also { addView(it, lp(top = 8)) }
                    body = text("", 15f, alpha = 0.72f).apply { setLineSpacing(0f, 1.28f) }.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) }) }
                    status = text("", 13f, bold = true, color = GREEN).apply {
                        background = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(0x1F4ADE80); setStroke(px(1), 0x404ADE80) }
                        setPadding(px(12), px(6), px(14), px(6))
                        compoundDrawablePadding = px(8)
                        setCompoundDrawablesRelativeWithIntrinsicBounds(context.getDrawable(R.drawable.ic_check)?.mutate()?.apply {
                            setTint(GREEN); setBounds(0, 0, px(14), px(14))
                        }, null, null, null)
                    }.also { addView(it, lp(top = 14)) }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        clipChildren = false; clipToPadding = false
                        primary = button(true).also { addView(it) }
                        secondary = button(false).also { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(14) }) }
                    }, lp(top = 20))
                }
                addView(words, LinearLayout.LayoutParams(0, -2, 1.05f))
                visual = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
                addView(visual, LinearLayout.LayoutParams(0, px(360), 1f).apply { marginStart = px(40) })
            }, LinearLayout.LayoutParams(-1, -2))
            // Full-width content under the two columns (the tour's cards).
            below = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
                .also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) }) }
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("step", step)
    }

    // Each step is redrawn on return, so a sign-in or a switch flipped in Settings shows as done.
    override fun onResume() {
        super.onResume()
        show()
        drift?.resume()
    }

    override fun onPause() {
        super.onPause()
        drift?.pause()
    }

    override fun onDestroy() {
        drift?.cancel(); pulse?.cancel()
        super.onDestroy()
    }

    private fun show() {
        // The picture belongs to a Nuvio profile: without one there's nothing to set yet.
        if (step == 1 && NuvioAccount.profile(this) == null) { step = if (forward) 2 else 0 }
        kicker.text = if (step < STEPS) "STEP ${step + 1}" else "YOU'RE ALL SET"
        progressLabel.text = if (step < STEPS) "${step + 1} / $STEPS" else "DONE"
        pulse?.cancel(); pulse = null
        // The faces strip lives in the left column only on its own step (and is rebuilt on return).
        words.findViewWithTag<View>("faces")?.let { words.removeView(it) }
        visual.removeAllViews()
        below.removeAllViews()
        visual.visibility = View.VISIBLE
        (primary.parent as View).visibility = View.VISIBLE
        when (step) {
            0 -> {
                val signedIn = NuvioAccount.isSignedIn(this)
                title.text = "Pick up where\nyou left off"
                body.text = "Connect Nuvio and what you were watching waits on the home screen, one press from playing. " +
                    "Your addons fill the rows. You scan a code with your phone, no typing on the TV."
                status(if (signedIn) "Signed in as ${NuvioAccount.profile(this)?.name.orEmpty()}" else null)
                primary(if (signedIn) "Continue" else "Connect Nuvio") {
                    if (signedIn) next() else startActivity(Intent(this, NuvioLoginActivity::class.java))
                }
                secondary(if (signedIn) null else "Skip for now") { next() }
                visual.addView(phone(signedIn), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            1 -> {
                val profile = NuvioAccount.profile(this)!!
                title.text = "Pick your face"
                body.text = "Choose a picture for ${profile.name.ifBlank { "your profile" }}. It sits at the top of the home screen " +
                    "and in Who's watching. Change it any time in Settings."
                status(null)
                primary("Continue") { next() }
                secondary(null) {}
                faces(profile)
            }
            2 -> {
                val rows = Addons.rows(this).count { it.enabled }
                title.text = "Choose\nwhat's on"
                body.text = "Pick the film and series rows you actually browse, and their order. " +
                    "Your Nuvio addons' catalogues are listed for you; everything else stays off the screen."
                status(if (rows > 0) "$rows rows on your home screen" else null)
                if (rows > 0) {
                    primary("Continue") { next() }
                    secondary("Change rows") { startActivity(Intent(this, RowsActivity::class.java)) }
                } else {
                    primary("Choose rows") { startActivity(Intent(this, RowsActivity::class.java)) }
                    secondary("Skip for now") { next() }
                }
                visual.addView(miniHome(), FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }
            3 -> {
                // Each TV gets its own way to send Home to Flix (see HomeButton).
                val state = HomeButton.state(this)
                val on = HomeButton.works(this)
                val googleTv = HomeButton.isGoogleTv(this)
                title.text = "One press\nto your show"
                body.text = when {
                    state == HomeButton.State.BLOCKED -> "Flix is switched on in Accessibility, but this TV won't start it. " +
                        "Android does this for apps installed from a downloaded APK. There's a one-time fix."
                    googleTv -> "Google TV sends Home to its own screen, full of things to buy. Switch on \"Flix Launcher\" in " +
                        "Accessibility and Home brings you back here instead. It only listens for the Home key."
                    else -> "Make Flix your home app and the Home button brings you straight back to what you watch. " +
                        "Choose Flix Launcher, then Always."
                }
                status(if (on) "Home opens Flix" else null)
                when {
                    on -> primary("Continue") { next() }
                    state == HomeButton.State.BLOCKED -> primary("Show me the fix") { HomeButton.showBlockedHelp(this) }
                    googleTv -> primary("Open Accessibility") { HomeButton.openAccessibility(this) }
                    else -> primary("Make Flix the home app") { HomeButton.chooseDefault(this) }
                }
                secondary(if (on) null else "Skip for now") { next() }
                visual.addView(remote(on), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            else -> {
                title.text = "Here's how it works"
                body.text = "Four things worth knowing before you start."
                status(null)
                primary("Start watching") { finishSetup() }
                secondary(null) {}
                visual.visibility = View.GONE
                // On the tour the button comes after the cards, as the last thing to read.
                (primary.parent as View).visibility = View.GONE
                tour()
            }
        }
        // The illustration rises in a moment after the words.
        visual.alpha = 0f; visual.scaleX = 0.94f; visual.scaleY = 0.94f; visual.translationY = 14 * dp
        visual.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(90).setDuration(460).setInterpolator(RowRecyclerView.EASE).start()
        drawProgress()
        if (step < STEPS && step != 1) primary.post { primary.requestFocus() }
    }

    private fun drawProgress() {
        progress.removeAllViews()
        repeat(STEPS) { i ->
            progress.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 2 * dp; setColor(if (i <= step) RED else 0x33FFFFFF) }
            }, LinearLayout.LayoutParams(px(if (i == step) 44 else 28), px(4)).apply { if (i > 0) marginStart = px(6) })
        }
    }

    // ---- Background ----

    /**
     * Rows of wallpaper previews, tilted and drifting sideways. Drawn once into a GPU layer
     * and only moved, so it costs the TV next to nothing.
     */
    private fun mosaic(): View {
        val walls = listOf(
            R.drawable.wall_v_nature, R.drawable.wall_scifi, R.drawable.wall_v_sea, R.drawable.wall_movies, R.drawable.wall_v_snow,
            R.drawable.wall_castles, R.drawable.wall_v_space, R.drawable.wall_wildlife, R.drawable.wall_v_city, R.drawable.wall_gaming,
            R.drawable.wall_v_underwater, R.drawable.wall_night, R.drawable.wall_v_deserts, R.drawable.wall_beaches, R.drawable.wall_v_nightsky,
            R.drawable.wall_flowers, R.drawable.wall_v_fireplace, R.drawable.wall_winter, R.drawable.wall_v_wildlife, R.drawable.wall_deserts,
        )
        val w = px(240); val h = px(135); val gap = px(14)
        val wall = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            rotation = -9f
            alpha = 0.7f
            for (r in 0 until 7) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                translationX = if (r % 2 == 0) 0f else -(w / 2f)
                for (c in 0 until 12) addView(ImageView(context).apply {
                    setImageResource(walls[(r * 5 + c * 3) % walls.size])
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 10 * dp)
                    }
                    clipToOutline = true
                }, LinearLayout.LayoutParams(w, h).apply { marginEnd = gap })
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = gap })
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        val holder = FrameLayout(this).apply { clipChildren = false; addView(wall, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = -px(260); topMargin = -px(140) }) }
        val span = (w + gap) * 2f
        drift = ValueAnimator.ofFloat(0f, -span).apply {
            duration = 60_000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { wall.translationX = it.animatedValue as Float }
            start()
        }
        return holder
    }

    // ---- Illustrations ----

    /** A phone with a code on it: what the Nuvio sign-in asks for. Signed in, the code becomes a tick. */
    private fun phone(signedIn: Boolean): View {
        val screen = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(0xFF0E1117.toInt()) }
            if (signedIn) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val profile = NuvioAccount.profile(this@SetupActivity)
                    addView(ImageView(context).apply {
                        if (profile != null) ProfilePicture.into(this, profile) else setImageResource(ProfilePicture.all[ProfilePicture.DEFAULT])
                        ProfilePicture.circle(this)
                    }, LinearLayout.LayoutParams(px(84), px(84)))
                    addView(text(profile?.name.orEmpty().ifBlank { "Nuvio" }, 16f, bold = true).apply { gravity = Gravity.CENTER }, lp(top = 14))
                    addView(text("Connected", 12f, bold = true, color = GREEN).apply { letterSpacing = 0.1f }, lp(top = 4))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            } else {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(ImageView(context).apply {
                        // Nearest-neighbour scaling keeps the code's squares crisp.
                        setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, qr("https://nuvio.tv", 29)).apply { isFilterBitmap = false })
                        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.WHITE) }
                        setPadding(px(10), px(10), px(10), px(10))
                    }, LinearLayout.LayoutParams(px(118), px(118)))
                    addView(text("Scan to sign in", 13f, bold = true, alpha = 0.8f), lp(top = 14))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            // The camera notch.
            addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(0xFF1C2230.toInt()) } },
                FrameLayout.LayoutParams(px(52), px(6), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(12) })
        }
        return FrameLayout(this).apply {
            rotation = -6f
            elevation = 24 * dp
            background = GradientDrawable().apply { cornerRadius = 32 * dp; setColor(0xFF1B202B.toInt()); setStroke(px(1.5f), 0x33FFFFFF) }
            setPadding(px(8), px(8), px(8), px(8))
            addView(screen, FrameLayout.LayoutParams(px(170), px(320)))
        }
    }

    /** Your face, big, with the sixteen to choose from in a strip under the words. */
    private fun faces(profile: NuvioAccount.Profile) {
        val big = ImageView(this).apply { ProfilePicture.into(this, profile) }
        val name = text(profile.name.ifBlank { "You" }, 22f, bold = true).apply { gravity = Gravity.CENTER }
        visual.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(FrameLayout(context).apply {
                clipChildren = false
                // A soft red halo behind the face.
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 150 * dp
                        colors = intArrayOf(0x66E50914, 0x00E50914)
                    }
                }, FrameLayout.LayoutParams(px(280), px(280), Gravity.CENTER))
                addView(View(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(px(4), Color.WHITE) }
                }, FrameLayout.LayoutParams(px(196), px(196), Gravity.CENTER))
                addView(big, FrameLayout.LayoutParams(px(178), px(178), Gravity.CENTER))
            }, LinearLayout.LayoutParams(px(280), px(280)))
            addView(name, lp(top = -8))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(px(6), px(12), px(6), px(12)) }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            addView(strip)
        }
        val chosen = ProfilePicture.indexFor(this, profile)
        ProfilePicture.all.forEachIndexed { i, res ->
            val face = ImageView(this).apply {
                setImageResource(res)
                scaleType = ImageView.ScaleType.CENTER_CROP
                isFocusable = true
                foreground = ProfilePicture.ring(dp)
                alpha = if (i == chosen) 1f else 0.62f
                setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.16f else 1f).scaleY(if (has) 1.16f else 1f).alpha(if (has) 1f else 0.62f)
                        .setDuration(200).setInterpolator(RowRecyclerView.EASE).start()
                    // The big face follows the focus, so you see each one full size.
                    if (has) {
                        big.setImageResource(res)
                        big.animate().cancel()
                        big.scaleX = 0.92f; big.scaleY = 0.92f
                        big.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(RowRecyclerView.EASE).start()
                    }
                }
                setOnClickListener {
                    ProfilePicture.set(this@SetupActivity, profile, i)
                    status("Picture saved")
                    primary.requestFocus()
                }
            }
            ProfilePicture.circle(face)
            strip.addView(face, LinearLayout.LayoutParams(px(52), px(52)).apply { marginEnd = px(12) })
        }
        // The strip sits between the words and the buttons.
        words.addView(scroll, words.indexOfChild(status), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        scroll.tag = "faces"
        strip.post { strip.getChildAt(chosen)?.requestFocus() }
    }

    /** A slice of the home screen: three rows of covers, the first card focused. */
    private fun miniHome(): View {
        val names = listOf("Midnight Run", "Sunlit", "Odyssey", "Harbour", "Neon City", "Wildwood", "Echo", "Atlas", "Northbound",
            "Velvet", "Ember", "Lagoon", "Paper Moon", "Drift", "Orbit")
        val labels = listOf("Continue watching", "Top 10 today", "Your addons")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(0xB30E1117.toInt()); setStroke(px(1), 0x1FFFFFFF) }
            setPadding(px(20), px(18), 0, px(18))
            elevation = 20 * dp
            labels.forEachIndexed { r, label ->
                addView(text(label, 11f, bold = true, alpha = 0.85f), lp(top = if (r == 0) 0 else 12))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    for (c in 0 until 6) addView(View(context).apply {
                        background = CoverArt(names[(r * 5 + c) % names.size])
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 6 * dp)
                        }
                        clipToOutline = true
                        if (r == 0 && c == 0) {
                            foreground = GradientDrawable().apply { cornerRadius = 6 * dp; setStroke(px(2.5f), Color.WHITE) }
                            scaleX = 1.08f; scaleY = 1.08f
                        } else alpha = 0.85f
                    }, LinearLayout.LayoutParams(px(50), px(74)).apply { marginEnd = px(9) })
                }, lp(top = 6))
            }
        }
    }

    /** The remote, its Home button glowing; once Home works, a tick beside it. */
    private fun remote(on: Boolean): View {
        val ring = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(px(3), RED) } }
        val home = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_s_home); setColorFilter(Color.BLACK) }, FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A303C.toInt(), 0xFF161A22.toInt())).apply {
                cornerRadius = 70 * dp; setStroke(px(1), 0x26FFFFFF)
            }
            setPadding(0, px(22), 0, px(22))
            elevation = 24 * dp
            rotation = 8f
            // Power dot, then the d-pad.
            addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3A4150.toInt()) } }, LinearLayout.LayoutParams(px(14), px(14)))
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0F1219.toInt()); setStroke(px(1), 0x1FFFFFFF) }
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF232937.toInt()) } },
                    FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER))
            }, LinearLayout.LayoutParams(px(88), px(88)).apply { topMargin = px(18) })
            // Back · Home · Menu, Home lit.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3A4150.toInt()) } }, LinearLayout.LayoutParams(px(26), px(26)))
                addView(FrameLayout(context).apply {
                    clipChildren = false
                    addView(ring, FrameLayout.LayoutParams(px(58), px(58), Gravity.CENTER))
                    addView(home, FrameLayout.LayoutParams(px(46), px(46), Gravity.CENTER))
                }, LinearLayout.LayoutParams(px(58), px(58)).apply { marginStart = px(14); marginEnd = px(14) })
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3A4150.toInt()) } }, LinearLayout.LayoutParams(px(26), px(26)))
            }, lp(top = 18))
            // Volume and channel rockers.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(2) { i ->
                    addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xFF232937.toInt()) } },
                        LinearLayout.LayoutParams(px(24), px(56)).apply { if (i > 0) marginStart = px(20) })
                }
            }, lp(top = 18))
        }
        // The ring breathes out from the Home button.
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedValue as Float
                ring.scaleX = 0.8f + f * 0.7f; ring.scaleY = ring.scaleX; ring.alpha = 1f - f
            }
            start()
        }
        return FrameLayout(this).apply {
            clipChildren = false
            addView(body, FrameLayout.LayoutParams(px(130), px(330), Gravity.CENTER))
            // A label beside the Home button, to its right.
            addView(text(if (on) "Home  →  Flix" else "Press Home", 13f, bold = true, color = if (on) GREEN else Color.WHITE).apply {
                background = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(if (on) 0x264ADE80 else 0x33FFFFFF) }
                setPadding(px(12), px(6), px(12), px(6))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = px(150); topMargin = px(40) })
        }
    }

    /** The tour: four tips, each an icon in a soft tile, a short title and a line under it. */
    private fun tour() {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tips = listOf(
            Triple(R.drawable.ic_s_home, "Home button", "Brings you back here from any app"),
            Triple(R.drawable.ic_o_play, "Hold OK", "On a film or an app for more options"),
            Triple(R.drawable.ic_s_film, "Movies, Shows, Apps", "The tabs at the top filter the rows"),
            Triple(R.drawable.ic_tune, "Flix settings", "The sliders button, top right"),
        )
        tips.forEachIndexed { i, (icon, head, line) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x0DFFFFFF)).apply {
                    cornerRadius = 20 * dp; setStroke(px(1), 0x1FFFFFFF)
                }
                setPadding(px(18), px(18), px(18), px(18))
                addView(FrameLayout(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFE50914.toInt(), 0xFF8C0610.toInt())).apply { cornerRadius = 14 * dp }
                    addView(ImageView(context).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
                }, LinearLayout.LayoutParams(px(42), px(42)))
                addView(text(head, 15f, bold = true), lp(top = 14))
                addView(text(line, 13f, alpha = 0.62f).apply { setLineSpacing(0f, 1.25f) }, lp(top = 6))
                // Each card arrives a beat after the last.
                alpha = 0f; translationY = 22 * dp
                animate().alpha(1f).translationY(0f).setStartDelay(140L + i * 80L).setDuration(420).setInterpolator(RowRecyclerView.EASE).start()
            }
            grid.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = px(14) })
        }
        val start = button(true).apply { text = "Start watching"; setOnClickListener { finishSetup() } }
        below.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            addView(grid, LinearLayout.LayoutParams(-1, -2))
            addView(start, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(24) })
        }, FrameLayout.LayoutParams(-1, -2))
        start.post { start.requestFocus() }
    }

    private fun qr(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF0B0E14.toInt() else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    // ---- Moving between steps ----

    /** Moves to [to], the old page sliding out the way it came and the new one in after it. */
    private fun go(to: Int) {
        val dir = if (to > step) 1 else -1
        forward = dir > 0
        page.animate().cancel()
        page.animate().alpha(0f).translationX(-dir * 36 * dp).setDuration(160).setInterpolator(RowRecyclerView.EASE).withEndAction {
            step = to
            show()
            page.translationX = dir * 36 * dp
            page.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(RowRecyclerView.EASE).start()
        }.start()
    }

    private fun next() = go(step + 1)

    private fun finishSetup() {
        markDone(this)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (step > 0) go(step - 1) else finishSetup()
    }

    private fun status(text: String?) {
        status.text = text.orEmpty()
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

    /** Pill buttons: the main one Flix red, the other glass; the focused one lifts and turns white. */
    private fun button(primary: Boolean) = TextView(this).apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(px(24), px(10), px(24), px(10))
        isFocusable = true
        val idle = if (primary) Color.WHITE else 0xE6FFFFFF.toInt()
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, idle)))
        background = StateListDrawable().apply {
            setEnterFadeDuration(160); setExitFadeDuration(160)
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = 100 * dp; setColor(Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = 100 * dp
                if (primary) setColor(RED) else { setColor(0x1FFFFFFF); setStroke(px(1), 0x33FFFFFF) }
            })
        }
        setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(180).setInterpolator(RowRecyclerView.EASE).start()
        }
        layoutParams = ViewGroup.LayoutParams(-2, -2)
    }

    companion object {
        /** Numbered steps; the tour after them is unnumbered. */
        private const val STEPS = 4
        private const val RED = 0xFFE50914.toInt()
        private const val GREEN = 0xFF4ADE80.toInt()

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
