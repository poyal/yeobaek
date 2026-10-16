package com.poyal.yeobaek.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** A touch used to stop a fling must not launch another fling without a vertical drag. */
internal class ReaderFlingGuard {
    private val origins = mutableMapOf<Long, Float>()
    private var touchSlop = 0f
    private var dragged = false
    private var tapRelease: Long? = null
    var maxTravel = 0f
        private set

    fun begin(slop: Float) {
        clear()
        touchSlop = slop
    }

    fun sample(pointerId: Long, y: Float) {
        val origin = origins.getOrPut(pointerId) { y }
        maxTravel = maxOf(maxTravel, abs(y - origin))
        if (maxTravel > touchSlop) dragged = true
    }

    fun release(time: Long) {
        tapRelease = time.takeIf { origins.isNotEmpty() && !dragged }
    }

    fun suppressFling(time: Long): Boolean {
        val released = tapRelease
        tapRelease = null
        // Do not apply an old tap to a later non-touch scroll (keyboard, mouse, accessibility).
        return released != null && time - released in 0..250
    }

    fun clear() {
        origins.clear()
        dragged = false
        tapRelease = null
        maxTravel = 0f
    }
}

internal fun Modifier.observeReaderFlingGesture(guard: ReaderFlingGuard): Modifier = pointerInput(guard) {
    try {
        awaitEachGesture {
            // Observe before LazyColumn consumes the event, including a down that stops a fling.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.begin(viewConfiguration.touchSlop)
            val isTouch = down.type == PointerType.Touch || down.type == PointerType.Stylus
            if (isTouch) guard.sample(down.id.value, down.position.y)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (isTouch) event.changes.forEach { change ->
                    change.historical.forEach { guard.sample(change.id.value, it.position.y) }
                    guard.sample(change.id.value, change.position.y)
                }
                val released = event.changes.none { it.pressed }
                if (released && isTouch) guard.release(event.changes.maxOf { it.uptimeMillis })
            } while (!released)
        }
    } finally {
        guard.clear()
    }
}
