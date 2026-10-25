package com.homehalo.tv

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A horizontal row of posters whose focused card is pinned to the start of the row.
 *
 * Moving focus re-lays the row out once, instantly, at its final sizes and scroll
 * position; every card is then shifted back to where it was drawn a moment ago and
 * glides home with a translation. Translations are applied by the compositor, so the
 * animation never re-measures or re-lays out anything mid-flight, which is what made
 * the old per-frame width animation stutter on slow TVs.
 */
class RowRecyclerView(context: Context) : RecyclerView(context) {
    private var localPass = false

    init {
        layoutManager = object : LinearLayoutManager(context, HORIZONTAL, false) {
            // The row positions the focused card itself; don't let RecyclerView smooth-scroll to it too.
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
        }
    }

    /**
     * Applies [change] (new card sizes), pins [pinned] to the row start (if given) and
     * animates every card from its previous on-screen place to its new one.
     */
    fun flip(pinned: View?, change: () -> Unit) {
        if (!isLaidOut || width == 0) { change(); return }
        val before = HashMap<Long, FloatArray>(childCount * 2)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val id = getChildItemId(c)
            if (id != NO_ID) before[id] = floatArrayOf(c.left + c.translationX, c.top + c.translationY)
        }
        localPass = true // the size changes below are laid out right here, not by the whole screen
        change()
        localPass = false
        stopScroll()
        val pos = pinned?.let { getChildAdapterPosition(it) } ?: NO_POSITION
        if (pos != NO_POSITION) (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
        for (i in 0 until childCount) getChildAt(i).forceLayout()
        localPass = true
        forceLayout()
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(left, top, right, bottom)
        localPass = false

        // Cards that weren't on screen before move with the rest of the row.
        val shift = (0 until childCount).map { getChildAt(it) }
            .firstNotNullOfOrNull { c -> before[getChildItemId(c)]?.let { it[0] - c.left } } ?: 0f
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val b = before[getChildItemId(c)]
            c.animate().cancel()
            c.translationX = b?.let { it[0] - c.left } ?: shift
            c.translationY = b?.let { it[1] - c.top } ?: 0f
            if (c.translationX == 0f && c.translationY == 0f) continue
            c.animate().translationX(0f).translationY(0f).setDuration(DURATION).setInterpolator(EASE).start()
        }
    }

    private var pending = false

    /**
     * The row's own size never changes, so when something inside it asks for a new layout
     * (a card's text or logo arriving, new data) only the row is laid out again, on the next
     * frame, rather than the whole home screen being measured from the top.
     */
    override fun requestLayout() {
        if (localPass) return
        if (!isLaidOut || width == 0) return super.requestLayout()
        forceLayout()
        if (pending) return
        pending = true
        postOnAnimation {
            pending = false
            if (!isAttachedToWindow) return@postOnAnimation
            localPass = true
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            layout(left, top, right, bottom)
            localPass = false
        }
    }

    companion object {
        const val DURATION = 260L
        val EASE = DecelerateInterpolator(2f)
    }
}
