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
    fun relayoutFor(child: View) {
        // The child must be measured again, and this row laid out even though its own size
        // hasn't changed (View.layout skips onLayout for an unchanged, un-forced view).
        child.forceLayout()
        if (!isLaidOut || width == 0) return super.requestLayout()
        if (pending) return
        pending = true
        postOnAnimation {
            pending = false
            localPass = true
            forceLayout()
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
