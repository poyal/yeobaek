package com.poyal.yeobaek

import com.poyal.yeobaek.ui.ReaderTouchGuard
import org.junit.Assert.*
import org.junit.Test

class ReaderTouchGuardTest {
    @Test fun stoppingFlingDoesNotToggleAndNextIntentionalTapWorks() {
        val guard = ReaderTouchGuard(8f)
        guard.begin(1, 20f, 20f, true)
        assertFalse(guard.isTap(1))
        guard.begin(2, 20f, 20f, false)
        guard.move(22f, 23f)
        assertTrue(guard.isTap(2))
        assertFalse(guard.isTap(1))
    }
    @Test fun dragReturningToOriginAndMultiTouchRemainRejected() {
        val guard = ReaderTouchGuard(8f)
        guard.begin(1, 20f, 20f, false)
        guard.move(40f, 20f); guard.move(20f, 20f)
        assertFalse(guard.isTap(1))
        guard.begin(2, 20f, 20f, false); guard.multiple()
        assertFalse(guard.isTap(2)); assertTrue(guard.multiplePointers)
        guard.begin(3, 20f, 20f, false)
        assertTrue(guard.isTap(3)); assertFalse(guard.multiplePointers)
    }
    @Test fun newDragCancelsPendingTapAndDisposeCancelsCallbacks() {
        val guard = ReaderTouchGuard(8f)
        guard.begin(1, 20f, 20f, false)
        guard.begin(2, 20f, 20f, false)
        assertTrue(guard.isTap(1))
        guard.move(20f, 50f)
        assertFalse(guard.isTap(1)); assertFalse(guard.isTap(2))
        guard.begin(3, 20f, 20f, false); guard.clear()
        assertFalse(guard.isTap(3))
    }
}
