package com.poyal.yeobaek.epub

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Filters user gestures without changing Readium's programmatic navigation or zoomed-page pan. */
internal class EpubGestureFrameLayout(context: Context) : FrameLayout(context) {
    var fixedLayout = false
    var scrollMode = true
    var swipeToTurn = true
    var pinchZoom = true
    var onUserMovement: () -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var cancelled = false
    private var selecting = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; cancelled = false
        }
        if (cancelled) return true
        val dx = abs(event.x - downX)
        val dy = abs(event.y - downY)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1 && (dx > slop || dy > slop)) onUserMovement()
        val blockedPinch = fixedLayout && !pinchZoom && event.pointerCount > 1
        // Scroll mode uses Readium's supported disablePageTurnsWhileScrolling configuration.
        // In paged text, keep active text-selection handles working.
        val blockedPageDrag = !fixedLayout && !scrollMode && !swipeToTurn && !selecting &&
            event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_MOVE &&
            dx > slop && dx > dy * 1.2f
        if (blockedPinch || blockedPageDrag) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            cancelled = true
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionModeForChild(originalView, object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
                callback.onCreateActionMode(mode, menu).also { if (it) selecting = true }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = callback.onActionItemClicked(mode, item)
            override fun onDestroyActionMode(mode: ActionMode) {
                try { callback.onDestroyActionMode(mode) } finally { selecting = false }
            }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }, type)
}
