package com.poyal.yeobaek

import com.poyal.yeobaek.ui.ReaderFlingGuard
import org.junit.Assert.*
import org.junit.Test

class ReaderFlingGuardTest {
    @Test fun reportedStopTouchCannotLaunchThe30000PixelPerSecondFling() {
        val guard = ReaderFlingGuard()
        guard.begin(30f)
        // 2026-10-17 01:13:10.882–11.007, gesture 4342: y=1443 until lift-off, then 1444.
        repeat(28) { guard.sample(0, 1443f) }
        repeat(4) { guard.sample(0, 1444f) }
        guard.release(360725095)
        assertTrue(guard.suppressFling(360725097))
        assertEquals(1f, guard.maxTravel, 0f)
        assertFalse(guard.suppressFling(360725098))
    }

    @Test fun genuineSwipesInEitherDirectionKeepTheirFlingEvenAfterReturningToStart() {
        for (direction in listOf(-1, 1)) {
            val guard = ReaderFlingGuard()
            guard.begin(30f)
            guard.sample(0, 1443f)
            guard.sample(0, 1443f + direction * 100)
            guard.sample(0, 1443f)
            guard.release(200)
            assertFalse(guard.suppressFling(202))
        }
    }

    @Test fun newGestureCancellationAndExpiredTapDoNotAffectOtherScrolls() {
        val guard = ReaderFlingGuard()
        fun tap() { guard.begin(30f); guard.sample(0, 1443f); guard.release(200) }
        assertFalse(guard.suppressFling(200))
        tap(); guard.begin(30f)
        assertFalse(guard.suppressFling(202))
        tap(); guard.clear()
        assertFalse(guard.suppressFling(202))
        tap()
        assertFalse(guard.suppressFling(451))
    }

    @Test fun pointerHandoffDoesNotLookLikeAJumpButAnotherFingerCanDrag() {
        val guard = ReaderFlingGuard()
        guard.begin(30f)
        guard.sample(0, 1400f)
        guard.sample(1, 800f)
        guard.release(200)
        assertTrue(guard.suppressFling(202))
        guard.begin(30f)
        guard.sample(0, 1400f)
        guard.sample(1, 800f)
        guard.sample(1, 700f)
        guard.release(300)
        assertFalse(guard.suppressFling(302))
    }
}
