package com.flix.launcher

import android.content.Context
import android.graphics.drawable.GradientDrawable

/**
 * Home screen themes: one setting that changes the accent colour (progress bars, row
 * markers, the Top 10 badge), the focus ring and the glass panels' tint and roundness.
 */
object Look {
    class Theme(
        val accent: Int,
        val focus: Int,
        /** Fill and edge of the dock, top menu and weather chip. */
        val panel: Int,
        val panelEdge: Int,
        val radiusDp: Float,
        val billboardRadiusDp: Float,
    )

    private val THEMES = mapOf(
        // Netflix red on smoked glass: Flix's own look.
        "cinema" to Theme(0xFFE50914.toInt(), 0xFF5AA9FF.toInt(), 0x40080C12, 0x24FFFFFF, 30f, 16f),
        // Apple TV: bright frosted panels, white focus, softer and rounder.
        "glass" to Theme(0xFFF5F5F7.toInt(), 0xFF8EC5FF.toInt(), 0x33FFFFFF, 0x4DFFFFFF, 36f, 28f),
        // Deep violet for late-night watching.
        "midnight" to Theme(0xFF8B5CF6.toInt(), 0xFFA78BFA.toInt(), 0x5A140B2E, 0x338B5CF6, 30f, 20f),
        // Quiet: grey accents, no tint, squarer corners, so the artwork does the talking.
        "minimal" to Theme(0xFFB8BCC4.toInt(), 0xFFE5E7EB.toInt(), 0x26000000, 0x14FFFFFF, 14f, 8f),
    )

    fun of(context: Context): Theme = THEMES[FlixSettings.get(context, FlixSettings.THEME)] ?: THEMES.getValue("cinema")

    fun accent(context: Context) = of(context).accent

    fun focus(context: Context) = of(context).focus

    /** A glass panel in the theme's colours. */
    fun panel(context: Context, radiusDp: Float = of(context).radiusDp) = GradientDrawable().apply {
        val t = of(context)
        val dp = context.resources.displayMetrics.density
        cornerRadius = radiusDp * dp
        setColor(t.panel)
        setStroke(dp.toInt().coerceAtLeast(1), t.panelEdge)
    }
}
