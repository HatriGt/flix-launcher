package com.homehalo.tv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView that leaves positioning to the home screen, which scrolls rows into a fixed spot itself. */
class RowsScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
}
