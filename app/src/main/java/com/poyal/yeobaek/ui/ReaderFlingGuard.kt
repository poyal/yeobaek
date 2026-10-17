package com.poyal.yeobaek.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** Neither a stopping touch nor a drag held still before release should launch a fling. */
internal class ReaderFlingGuard {
    private data class Sample(val time: Long, val y: Float)
    private val origins = mutableMapOf<Long, Float>()
    private val tails = mutableMapOf<Long, ArrayDeque<Sample>>()
    private var touchSlop = 0f
    private var dragged = false
    private var stoppedRelease: Long? = null
    var maxTravel = 0f
        private set
    var releaseTravel: Float? = null
        private set
    var releaseDuration = 0L
        private set

    fun begin(slop: Float) {
        clear()
        touchSlop = slop
    }

    fun sample(pointerId: Long, y: Float, time: Long) {
        val origin = origins.getOrPut(pointerId) { y }
        maxTravel = maxOf(maxTravel, abs(y - origin))
        if (maxTravel > touchSlop) dragged = true
        val tail = tails.getOrPut(pointerId) { ArrayDeque() }
        val previous = tail.lastOrNull()
        // Batched touch history can contain duplicate milliseconds. Keep time monotonic and
        // retain the latest position for a duplicate, without confusing sample count with time.
        if (previous != null && time < previous.time) return
        if (previous?.time == time) tail.removeLast()
        tail.addLast(Sample(time, y))
        // Retain one boundary sample, also covering a hold with no MOVE events at all.
        while (tail.size > 1 && time - tail[1].time >= 100) tail.removeFirst()
    }

    fun release(time: Long, pointerId: Long, receivedAt: Long = time) {
        val tail = tails[pointerId]
        val recent = tail.orEmpty()
        releaseDuration = recent.firstOrNull()?.let { (time - it.time).coerceIn(0, 100) } ?: 0L
        releaseTravel = recent.takeIf { it.isNotEmpty() }?.let { samples ->
            samples.maxOf { it.y } - samples.minOf { it.y }
        }
        // Use the whole range, not net displacement: a genuine reversal may return to its
        // starting point. A quarter of touch slop tolerates lift-off jitter without blocking
        // a deliberate slow swipe. Require elapsed time, including on sparse input streams.
        val stopped = releaseDuration >= 80 && releaseTravel?.let { it <= touchSlop / 4 } == true
        // Input can sit in the event queue before delivery. Expire from observation time,
        // while measuring motion with the original event timestamps.
        stoppedRelease = receivedAt.takeIf { origins.isNotEmpty() && (!dragged || stopped) }
    }

    fun suppressFling(time: Long): Boolean {
        val released = stoppedRelease
        stoppedRelease = null
        // Do not apply an old tap to a later non-touch scroll (keyboard, mouse, accessibility).
        return released != null && time - released in 0..250
    }

    fun clear() {
        origins.clear()
        tails.clear()
        dragged = false
        stoppedRelease = null
        maxTravel = 0f
        releaseTravel = null
        releaseDuration = 0L
    }
}

internal fun Modifier.observeReaderFlingGesture(guard: ReaderFlingGuard): Modifier = pointerInput(guard) {
    try {
        awaitEachGesture {
            // Observe before LazyColumn consumes the event, including a down that stops a fling.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.begin(viewConfiguration.touchSlop)
            val isTouch = down.type == PointerType.Touch || down.type == PointerType.Stylus
            if (isTouch) guard.sample(down.id.value, down.position.y, down.uptimeMillis)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (isTouch) event.changes.forEach { change ->
                    change.historical.forEach { guard.sample(change.id.value, it.position.y, it.uptimeMillis) }
                    guard.sample(change.id.value, change.position.y, change.uptimeMillis)
                }
                val released = event.changes.none { it.pressed }
                if (released && isTouch) {
                    val up = event.changes.first { it.previousPressed }
                    guard.release(up.uptimeMillis, up.id.value, android.os.SystemClock.uptimeMillis())
                }
            } while (!released)
        }
    } finally {
        guard.clear()
    }
}
