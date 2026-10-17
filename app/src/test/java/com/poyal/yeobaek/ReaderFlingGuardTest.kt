package com.poyal.yeobaek

import com.poyal.yeobaek.ui.ReaderFlingGuard
import org.junit.Assert.*
import org.junit.Test

class ReaderFlingGuardTest {
    @Test fun reportedStopTouchCannotLaunchThe30000PixelPerSecondFling() {
        val guard = ReaderFlingGuard()
        guard.begin(30f)
        // 2026-10-17 01:13:10.882–11.007, gesture 4342: y=1443 until lift-off, then 1444.
        repeat(28) { guard.sample(0, 1443f, it * 4L) }
        repeat(4) { guard.sample(0, 1444f, 112 + it * 4L) }
        guard.release(125, 0)
        assertTrue(guard.suppressFling(127))
        assertEquals(1f, guard.maxTravel, 0f)
        assertFalse(guard.suppressFling(128))
    }

    @Test fun reportedSwipeThenHoldCannotLaunchTheReverseFling() {
        for (direction in listOf(-1, 1)) {
            val guard = ReaderFlingGuard()
            guard.begin(30f)
            // Replay the rounded coordinates, including duplicate timestamps, from 12:53:16
            // gesture 125. Compose supplied +30000 px/s despite a 1 px release tail.
            val points = javaClass.getResourceAsStream("/scroll/swipe-hold-125.csv")!!
                .bufferedReader().use { reader -> reader.readLines().map { line ->
                    val (time, y) = line.split(',')
                    time.toLong() to y.toFloat() * direction
                } }
            points.forEach { (time, y) -> guard.sample(0, y, time) }
            guard.release(points.last().first, 0)
            assertEquals(444f, guard.maxTravel, 0f)
            assertEquals(1f, guard.releaseTravel!!, 0f)
            assertEquals(100L, guard.releaseDuration)
            assertTrue(guard.suppressFling(points.last().first + 2))
        }
    }

    @Test fun genuineSwipesInEitherDirectionKeepTheirFlingEvenAfterReturningToStart() {
        for (direction in listOf(-1, 1)) {
            val guard = ReaderFlingGuard()
            guard.begin(30f)
            guard.sample(0, 1443f, 0)
            guard.sample(0, 1443f + direction * 100, 50)
            guard.sample(0, 1443f, 100)
            guard.release(100, 0)
            assertFalse(guard.suppressFling(102))
        }
    }

    @Test fun fastAndSlowContinuingSwipesKeepTheirFlingAtDifferentSampleRates() {
        for (interval in listOf(4, 8, 16, 33)) for (speed in listOf(120f, 12000f)) for (direction in listOf(-1, 1)) {
            val guard = ReaderFlingGuard()
            guard.begin(30f)
            for (i in 0..100) guard.sample(0, direction * speed * i * interval / 1000, i * interval.toLong())
            guard.release(100L * interval, 0)
            assertFalse("interval=$interval speed=$speed direction=$direction", guard.suppressFling(100L * interval + 2))
        }
    }

    @Test fun holdWithoutMoveEventsStopsButShortPauseDoesNot() {
        fun gesture(pause: Long): ReaderFlingGuard = ReaderFlingGuard().apply {
            begin(30f)
            sample(0, 1000f, 0)
            sample(0, 500f, 100)
            sample(0, 500f, 100 + pause)
            release(100 + pause, 0)
        }
        assertTrue(gesture(500).suppressFling(602))
        assertFalse(gesture(20).suppressFling(122))
    }

    @Test fun newGestureCancellationAndExpiredTapDoNotAffectOtherScrolls() {
        val guard = ReaderFlingGuard()
        fun tap() { guard.begin(30f); guard.sample(0, 1443f, 100); guard.release(200, 0) }
        assertFalse(guard.suppressFling(200))
        tap(); guard.begin(30f)
        assertFalse(guard.suppressFling(202))
        tap(); guard.clear()
        assertFalse(guard.suppressFling(202))
        tap()
        assertFalse(guard.suppressFling(451))
    }

    @Test fun queuedTouchUsesEventTimeForMotionAndDeliveryTimeForExpiry() {
        val guard = ReaderFlingGuard()
        guard.begin(30f)
        guard.sample(0, 1000f, 0)
        guard.sample(0, 500f, 100)
        guard.sample(0, 500f, 300)
        guard.release(300, 0, receivedAt = 1000)
        assertEquals(100L, guard.releaseDuration)
        assertTrue(guard.suppressFling(1002))
        assertFalse(guard.suppressFling(1003))
    }

    @Test fun pointerHandoffDoesNotLookLikeAJumpButAnotherFingerCanDrag() {
        val guard = ReaderFlingGuard()
        guard.begin(30f)
        guard.sample(0, 1400f, 0)
        guard.sample(1, 800f, 0)
        guard.release(20, 1)
        assertTrue(guard.suppressFling(22))
        guard.begin(30f)
        guard.sample(0, 1400f, 100)
        guard.sample(1, 800f, 100)
        guard.sample(1, 700f, 200)
        guard.release(200, 1)
        assertFalse(guard.suppressFling(202))
        guard.sample(1, 700f, 400)
        guard.release(400, 1)
        assertTrue("Lift-off uses the remaining finger's history", guard.suppressFling(402))
    }
}
