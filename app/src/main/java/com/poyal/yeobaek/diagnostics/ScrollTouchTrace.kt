package com.poyal.yeobaek.diagnostics

import android.view.MotionEvent
import kotlin.math.roundToInt

/** Observes MotionEvents without consuming/replacing them or changing velocity tracking. */
class ScrollTouchTrace(private val diagnostics: ScrollDiagnostics) {
    private data class Sample(val time: Long, val detail: String)
    private val tail = ArrayDeque<Sample>()
    private var session: String? = null
    private var gesture = 0L
    private var lastMove = 0L

    fun touch(event: MotionEvent, readerSession: String?) {
        if (readerSession != session) { finish("reader_changed"); session = readerSession }
        if (readerSession == null) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { finish("new_down"); gesture++; lastMove = 0 }
        for (history in (event.historySize - 8).coerceAtLeast(0) until event.historySize) {
            remember(Sample(event.getHistoricalEventTime(history), points(event, history)))
        }
        val points = points(event, null)
        remember(Sample(event.eventTime, points))
        if (event.actionMasked != MotionEvent.ACTION_MOVE || event.eventTime - lastMove >= 100) {
            diagnostics.record("touch", "session=$session gesture=$gesture action=${MotionEvent.actionToString(event.action)} " +
                "actionIndex=${event.actionIndex} pointers=${event.pointerCount} history=${event.historySize} $points", event.eventTime)
            lastMove = event.eventTime
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) finish("gesture_end")
    }

    private fun remember(sample: Sample) {
        if (tail.size == 64) tail.removeFirst()
        tail.addLast(sample)
    }

    private fun points(event: MotionEvent, history: Int?): String = buildString {
        for (i in 0 until event.pointerCount.coerceAtMost(10)) {
            val x = history?.let { event.getHistoricalX(i, it) } ?: event.getX(i)
            val y = history?.let { event.getHistoricalY(i, it) } ?: event.getY(i)
            val pressure = history?.let { event.getHistoricalPressure(i, it) } ?: event.getPressure(i)
            append("id=${event.getPointerId(i)},tool=${event.getToolType(i)},x=${x.roundToInt()},y=${y.roundToInt()},")
            append("rawX=${(x + event.getRawX(i) - event.getX(i)).roundToInt()},rawY=${(y + event.getRawY(i) - event.getY(i)).roundToInt()},p=${(pressure * 100).roundToInt()} ")
        }
    }

    fun finish(reason: String) {
        if (tail.isEmpty()) return
        // Keep all of the last samples, including historical points around lift-off.
        tail.toList().chunked(8).forEach { samples ->
            diagnostics.record("raw_touch_tail", "session=$session gesture=$gesture reason=$reason samples=" +
                samples.joinToString(";") { "${it.time}[${it.detail}]" }, samples.first().time)
        }
        tail.clear()
    }
}
