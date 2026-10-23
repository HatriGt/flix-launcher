package com.tvlauncher.aura

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Every installed app in a grid, floating over the home screen's scenery.
 * Uses the same tile shape and size as the home screen.
 */
class AllAppsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter
    private lateinit var grid: RecyclerView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        val style = tileStyle()
        val columns = if (style.isBanner) 5 else 8

        title = TextView(this).apply {
            text = "All apps"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        count = TextView(this).apply {
            textSize = 12f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(px(12), 0, 0, px(4))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            setPadding(px(52), px(34), px(52), px(10))
            addView(title)
            addView(count)
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(TextView(context).apply {
                text = "Hold OK or press Menu for options"
                textSize = 10f
                setTextColor(0x99FFFFFF.toInt())
            })
        }

        adapter = AppAdapter(style, onFocus = {}, onClick = ::launch, onLongClick = ::showMenu)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AllAppsActivity, columns)
            this.adapter = this@AllAppsActivity.adapter
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(px(40), px(16), px(40), px(40))
            layoutAnimation = AnimationUtils.loadLayoutAnimation(this@AllAppsActivity, R.anim.row_enter)
            val rowGap = px(if (style.isBanner) 16 else 22)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(out: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    out.bottom = rowGap
                }
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(grid)
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

    /** Same look as the home screen, with spacing tuned for a grid. */
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
            val apps = repo.loadApps()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val first = adapter.items.isEmpty()
                adapter.items = apps
                count.text = "${apps.size} apps"
                if (first) grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    private fun launch(app: AppEntry) {
        runCatching { startActivity(app.launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show() }
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

    override fun onResume() {
        super.onResume()
        load() // pick up installs and uninstalls made while away
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun px(value: Int) = (value * dp).toInt()
}
