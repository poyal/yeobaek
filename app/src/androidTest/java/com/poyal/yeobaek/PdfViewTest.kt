package com.poyal.yeobaek

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.pdf.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PdfViewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun restoredEndDoesNotPromptUntilAdvanceAndSeekWaitsForRenderedEnd() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "pdf-end-test.pdf")
        instrumentation.context.assets.open("pdf/mixed.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val engine = PdfEngine(context)
        val doc = engine.open(file)
        lateinit var view: PdfDocumentView
        var ends = 0
        try {
            compose.setContent { AndroidView(factory = { ctx -> PdfDocumentView(ctx).also {
                view = it; it.onEndReached = { ends++ }; it.open(doc, PdfLocator(doc.pages.lastIndex, completed = true))
            } }, modifier = Modifier.fillMaxSize()) }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            compose.runOnIdle {
                assertEquals(0, ends)
                view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
            }
            compose.waitUntil(20_000) { ends == 1 }
            compose.runOnIdle { view.move(PdfLocator()); view.move(PdfLocator(doc.pages.lastIndex, completed = true)) }
            compose.waitUntil(20_000) { ends == 2 }
            compose.runOnIdle {
                assertTrue(view.locator().completed)
                assertEquals(view.renderStatus().visible, view.renderStatus().complete)
            }
        } finally { compose.runOnIdle { view.dispose() }; doc.close(); withContext(Dispatchers.Main) { engine.dispose() }; file.delete() }
    }
    @Test fun lastPageZoomAndSettingsKeepTheReadingAnchor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "pdf-view-test.pdf")
        instrumentation.context.assets.open("pdf/mixed.pdf").use { source -> file.outputStream().use { source.copyTo(it) } }
        val engine = PdfEngine(context)
        val doc = engine.open(file)
        lateinit var view: PdfDocumentView
        var ready = false
        try {
            compose.setContent {
                AndroidView(factory = { ctx -> PdfDocumentView(ctx).also {
                    view = it; it.onReady = { ready = true }; it.open(doc, PdfLocator())
                } }, modifier = Modifier.fillMaxSize().testTag("pdf_canvas"))
            }
            compose.waitUntil(10_000) { ready }
            compose.runOnIdle {
                view.move(PdfLocator(2))
                assertEquals(2, view.locator().page)
                view.move(PdfLocator(0, .12f, .25f, 2f))
                assertEquals(.25f, view.locator().y, .01f)
                assertEquals(.12f, view.locator().x, .01f)
                view.configure(PdfPreferences(inverted = true, spacing = 24f), android.graphics.Color.BLACK)
                assertEquals(.25f, view.locator().y, .01f)
                assertEquals(2f, view.locator().zoom, .001f)
            }
            compose.onNodeWithTag("pdf_canvas").performTouchInput { doubleClick(center) }
            compose.runOnIdle { assertEquals(1f, view.locator().zoom, .001f) }
            compose.onNodeWithTag("pdf_canvas").performTouchInput { advanceEventTime(500); pinch(start0 = center * .7f, end0 = center * .3f, start1 = center * 1.3f, end1 = center * 1.7f) }
            compose.runOnIdle { assertTrue("pinch zoom=${view.locator().zoom}", view.locator().zoom > 1.2f) }
        } finally {
            compose.runOnIdle { view.dispose() }
            doc.close(); withContext(Dispatchers.Main) { engine.dispose() }; file.delete()
        }
    }
}
