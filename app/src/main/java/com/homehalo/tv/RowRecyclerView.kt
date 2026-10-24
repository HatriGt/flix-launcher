package com.homehalo.tv

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * A horizontal row of fixed height. When a card inside it changes width (the focused
 * card widening), the row lays itself out again without asking the whole home screen
 * to re-measure on every animation frame. Everything else (new data, scrolling) goes
 * through the normal path.
 */
class RowRecyclerView(context: Context) : RecyclerView(context) {
    private var localPass = false
    private var pending = false

    /** Called by a card as its width animates. */
    fun relayoutFor(@Suppress("UNUSED_PARAMETER") child: View) {
        if (!isLaidOut || width == 0) return
        if (pending) return
        pending = true
        postOnAnimation {
            pending = false
            localPass = true
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            layout(left, top, right, bottom)
            localPass = false
        }
    }

    override fun requestLayout() {
        if (localPass) return
        super.requestLayout()
    }
}
