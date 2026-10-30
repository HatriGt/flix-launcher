package com.flix.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Frosted pop-up with the app's banner and its actions, in place of the stock alert dialog. */
object OptionsMenu {
    fun show(context: Context, app: AppEntry, actions: List<Pair<String, () -> Unit>>) {
        show(context, app.label, actions, artHeightDp = 118) { image ->
            // A drawable can only belong to one view, so the menu draws its own copy.
            val art = app.banner ?: app.icon
            image.setImageDrawable(if (art is YouTubeBanner) YouTubeBanner() else art.constantState?.newDrawable(image.resources)?.mutate() ?: art)
            image.scaleType = if (app.banner != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
    }

    /** Menu for a movie or show, with its backdrop as the header art. */
    fun show(context: Context, title: Catalog.Title, actions: List<Pair<String, () -> Unit>>) {
        show(context, title.name, actions, artHeightDp = 118) { image ->
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            (title.backdrop ?: title.poster)?.let { Images.into(image, it, (240 * image.resources.displayMetrics.density).toInt()) }
        }
    }

    /** Plain menu with a heading and no artwork. */
    fun show(context: Context, label: String, actions: List<Pair<String, () -> Unit>>) = show(context, label, actions, artHeightDp = 0) { }

    private fun show(context: Context, label: String, actions: List<Pair<String, () -> Unit>>, artHeightDp: Int, bindArt: (ImageView) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val dialog = Dialog(context)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.menu_panel)
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            layoutParams = ViewGroup.LayoutParams((240 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (artHeightDp > 0) panel.addView(ImageView(context).apply {
            bindArt(this)
            setBackgroundResource(R.drawable.card_background)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (artHeightDp * dp).toInt())
        })
        panel.addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
        })

        actions.forEachIndexed { index, (title, action) ->
            panel.addView(TextView(context).apply {
                text = title
                setTextColor(context.getColorStateList(R.color.menu_text))
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setBackgroundResource(R.drawable.menu_item)
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (36 * dp).toInt())
                    .apply { topMargin = (2 * dp).toInt() }
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
                if (index == 0) post { requestFocus() }
            })
        }

        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
            setWindowAnimations(0)
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        panel.alpha = 0f
        panel.scaleX = 0.9f
        panel.scaleY = 0.9f
        dialog.show()
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(1.2f)).withLayer().start()
    }
}
