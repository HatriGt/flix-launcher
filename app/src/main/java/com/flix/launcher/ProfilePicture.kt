package com.flix.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView

/**
 * The cartoon picture each Nuvio profile shows as its face: one of sixteen bundled portraits,
 * chosen in Settings and kept per profile on this TV. A profile that has never picked one gets
 * a default that depends on its index, so a family's profiles start out different.
 */
object ProfilePicture {
    const val COUNT = 16

    /** The bundled portraits, in the order the picker shows them. */
    val all: List<Int> = listOf(
        R.drawable.avatar_01, R.drawable.avatar_02, R.drawable.avatar_03, R.drawable.avatar_04,
        R.drawable.avatar_05, R.drawable.avatar_06, R.drawable.avatar_07, R.drawable.avatar_08,
        R.drawable.avatar_09, R.drawable.avatar_10, R.drawable.avatar_11, R.drawable.avatar_12,
        R.drawable.avatar_13, R.drawable.avatar_14, R.drawable.avatar_15, R.drawable.avatar_16,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("profile_pictures", Context.MODE_PRIVATE)

    /** 0-based picture index for [profile]: the stored choice, else a default spread by profile index. */
    fun indexFor(context: Context, profile: NuvioAccount.Profile): Int =
        prefs(context).getInt("p${profile.index}", -1).takeIf { it in 0 until COUNT } ?: ((profile.index - 1).mod(COUNT))

    fun set(context: Context, profile: NuvioAccount.Profile, index: Int) {
        prefs(context).edit().putInt("p${profile.index}", index.coerceIn(0, COUNT - 1)).apply()
    }

    fun drawable(context: Context, profile: NuvioAccount.Profile): Drawable? =
        context.getDrawable(all[indexFor(context, profile)])

    /** Shows [profile]'s picture in [view], clipped to a circle. */
    fun into(view: ImageView, profile: NuvioAccount.Profile) {
        view.setImageDrawable(drawable(view.context, profile))
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        circle(view)
    }

    /** Clips any view to a circle, for a picture or its focus ring. */
    fun circle(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
        }
    }

    /** A white ring drawn over the picture while focused. */
    fun ring(dp: Float): Drawable = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke((3 * dp).toInt(), android.graphics.Color.WHITE)
        })
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
    }
}
