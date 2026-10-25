package com.poyal.yeobaek

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.PdfPreferences
import com.poyal.yeobaek.image.*
import com.poyal.yeobaek.pdf.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderGestureTest {
    @get:Rule val ui = createComposeRule()

    @Test fun imageGesturesRespectSettingsAndStoppingTouchDoesNotToggle() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val directory = File(context.cacheDir, "image-gesture-test")
        val source = ImageSource.archive(context, ImageStore(directory), { inst.context.assets.open("images/mixed.cbz") })
        lateinit var view: ImageDocumentView
        var taps = 0
        try {
            ui.setContent { AndroidView(factory = { ImageDocumentView(it).also { v -> view = v; v.onTap = { taps++ }; v.open(source, ImageLocator()) } }, modifier = Modifier.fillMaxSize().testTag("canvas")) }
            ui.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            exercise(view, { view.locator().zoom }, { view.resetZoom() }, { double, pinch ->
                view.configure(ImagePreferences(doubleTapZoom = double, pinchZoom = pinch), android.graphics.Color.WHITE)
            }, { taps }, { view.renderStatus().settled })
            ui.runOnIdle {
                view.configure(ImagePreferences(mode = ImageMode.PAGED, tapToTurn = false), android.graphics.Color.WHITE)
                view.move(ImageLocator())
            }
            ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(600); click(centerRight - androidx.compose.ui.geometry.Offset(10f, 0f)) }
            ui.waitUntil(3000) { taps >= 3 }
            ui.runOnIdle { assertEquals(0, view.locator().page); view.configure(ImagePreferences(mode = ImageMode.PAGED), android.graphics.Color.WHITE) }
            ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(600); click(centerRight - androidx.compose.ui.geometry.Offset(10f, 0f)) }
            ui.waitUntil(3000) { view.locator().page == 1 }
            val tapCount = taps
            ui.runOnIdle { view.configure(ImagePreferences(mode = ImageMode.PAGED, swipeToTurn = false), android.graphics.Color.WHITE) }
            ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(1000); swipeLeft() }
            ui.runOnIdle { assertEquals(1, view.locator().page); assertEquals(tapCount, taps) }
            ui.runOnIdle { view.configure(ImagePreferences(mode = ImageMode.PAGED), android.graphics.Color.WHITE) }
            ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(1000); swipeRight() }
            ui.waitUntil(3000) { view.locator().page == 0 }
            ui.runOnIdle {
                view.configure(ImagePreferences(mode = ImageMode.PAGED, swipeToTurn = false), android.graphics.Color.WHITE)
                view.move(ImageLocator(page = 0, zoom = 2f, x = .1f, y = .1f))
            }
            val pan = view.locator().x
            ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(1000); swipeLeft() }
            ui.runOnIdle { assertEquals(0, view.locator().page); assertTrue("Disabling page swipes must preserve zoomed pan", view.locator().x > pan) }
        } finally { ui.runOnIdle { view.dispose() }; source.close(); directory.deleteRecursively() }
    }

    @Test fun pdfGesturesRespectSettingsAndStoppingTouchDoesNotToggle() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val file = File(context.cacheDir, "pdf-gesture-test.pdf")
        inst.context.assets.open("pdf/mixed.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val engine = PdfEngine(context)
        val document = engine.open(file)
        lateinit var view: PdfDocumentView
        var taps = 0
        try {
            ui.setContent { AndroidView(factory = { PdfDocumentView(it).also { v -> view = v; v.onTap = { taps++ }; v.open(document, PdfLocator()) } }, modifier = Modifier.fillMaxSize().testTag("canvas")) }
            ui.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            exercise(view, { view.locator().zoom }, { view.resetZoom() }, { double, pinch ->
                view.configure(PdfPreferences(doubleTapZoom = double, pinchZoom = pinch), android.graphics.Color.WHITE)
            }, { taps }, { view.renderStatus().settled })
        } finally { ui.runOnIdle { view.dispose() }; document.close(); withContext(Dispatchers.Main) { engine.dispose() }; file.delete() }
    }

    private fun exercise(view: View, zoom: () -> Float, reset: () -> Unit, configure: (Boolean, Boolean) -> Unit,
        taps: () -> Int, settled: () -> Boolean) {
        ui.onNodeWithTag("canvas").performTouchInput { doubleClick(center) }
        ui.runOnIdle { assertEquals(1f, zoom(), .01f); assertEquals(1, taps()); configure(true, true) }
        ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(600); doubleClick(center) }
        ui.runOnIdle { assertEquals(2f, zoom(), .01f); assertEquals(1, taps()); reset(); configure(false, false) }
        ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(600); pinch(start0 = center * .7f, end0 = center * .3f, start1 = center * 1.3f, end1 = center * 1.7f) }
        ui.runOnIdle { assertEquals(1f, zoom(), .01f); assertEquals(1, taps()); configure(false, true) }
        ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(600); pinch(start0 = center * .7f, end0 = center * .3f, start1 = center * 1.3f, end1 = center * 1.7f) }
        ui.runOnIdle { assertTrue(zoom() > 1.2f); reset() }
        ui.runOnIdle {
            val down = SystemClock.uptimeMillis()
            val x = view.width / 2f
            val y = view.height * .8f
            fun event(start: Long, time: Long, action: Int, at: Float) {
                MotionEvent.obtain(start, time, action, x, at, 0).also { view.dispatchTouchEvent(it); it.recycle() }
            }
            event(down, down, MotionEvent.ACTION_DOWN, y)
            event(down, down + 20, MotionEvent.ACTION_MOVE, y - 80)
            event(down, down + 40, MotionEvent.ACTION_MOVE, y - 200)
            event(down, down + 60, MotionEvent.ACTION_UP, y - 300)
            assertFalse("The test must actually start a fling", settled())
            event(down + 90, down + 90, MotionEvent.ACTION_DOWN, view.height / 2f)
            event(down + 90, down + 130, MotionEvent.ACTION_UP, view.height / 2f)
            assertTrue(settled())
        }
        // GestureDetector uses the real main looper for single-tap confirmation.
        Thread.sleep(600)
        ui.runOnIdle { assertEquals("Stopping the fling must not toggle menus", 1, taps()) }
        ui.onNodeWithTag("canvas").performTouchInput { advanceEventTime(1000); click(center) }
        ui.waitUntil(3000) { taps() == 2 }
    }
}
