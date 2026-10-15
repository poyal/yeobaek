package com.poyal.yeobaek

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.image.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImageViewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun splitWidePageKeepsOriginalCoordinatesAndOnlyCompletesAfterSecondHalf() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "image-split-test")
        // One wide page isolates the half-page completion and restore boundary.
        val zip = File(directory.apply { mkdirs() }, "wide.cbz")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("wide.png"))
            val bitmap = android.graphics.Bitmap.createBitmap(1200, 600, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                drawColor(android.graphics.Color.WHITE)
                drawRect(60f, 60f, 580f, 540f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
                drawRect(620f, 60f, 1140f, 540f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
            }
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.closeEntry()
        }
        val source = ImageSource.archive(context, ImageStore(directory), { zip.inputStream() })
        source.measure(0)
        lateinit var view: ImageDocumentView
        val prefs = ImagePreferences(mode = ImageMode.PAGED, splitWide = true)
        try {
            compose.setContent { AndroidView(factory = { ctx -> ImageDocumentView(ctx).also {
                view = it; it.configure(prefs, android.graphics.Color.WHITE); it.open(source, ImageLocator())
            } }, modifier = Modifier.fillMaxSize()) }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            compose.runOnIdle {
                assertEquals(0, view.locator().segment); assertFalse(view.locator().completed)
                view.turn(1)
                assertEquals(0, view.locator().page); assertEquals(1, view.locator().segment)
                assertTrue(view.locator().x >= .5f); assertTrue(view.locator().completed)
                val saved = view.locator()
                view.configure(prefs.copy(splitWide = false), android.graphics.Color.WHITE)
                view.configure(prefs, android.graphics.Color.WHITE)
                assertEquals(1, view.locator().segment)
                view.move(saved); assertEquals(1, view.locator().segment)
                view.turn(-1); assertEquals(0, view.locator().segment); assertFalse(view.locator().completed)
                view.configure(prefs.copy(direction = ImageDirection.RTL), android.graphics.Color.WHITE)
                view.move(ImageLocator())
                assertEquals(1, view.locator().segment); assertFalse(view.locator().completed)
                view.turn(1); assertEquals(0, view.locator().segment); assertTrue(view.locator().completed)
                view.configure(prefs.copy(cropMode = CropMode.MANUAL, crop = CropRect(.1f, .1f, .9f, .9f)), android.graphics.Color.WHITE)
                view.move(ImageLocator(segment = 1, x = .55f, y = .25f, zoom = 3f))
                assertEquals(.25f, view.locator().y, .02f)
                assertTrue(view.locator().x >= .5f)
            }
            compose.runOnIdle {
                view.configure(prefs.copy(cropMode = CropMode.AUTO), android.graphics.Color.WHITE)
                view.move(ImageLocator(segment = 0), announceEnd = false)
            }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible && it.settled } }
            compose.runOnIdle {
                assertEquals(0, view.locator().page)
                assertEquals(0, view.locator().segment)
                assertTrue("Automatic crop should remove only the uniform border", view.locator().x in .01f.. .1f)
                assertFalse(view.locator().completed)
            }
        } finally { compose.runOnIdle { view.dispose() }; source.close(); directory.deleteRecursively() }
    }
    @Test fun endDoesNotWaitForUnseenPagesAcrossLargeSpacing() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "image-end-spacing-test")
        val source = ImageSource.archive(context, ImageStore(directory), { instrumentation.context.assets.open("images/mixed.cbz") })
        lateinit var view: ImageDocumentView
        var ended = false
        try {
            compose.setContent { AndroidView(factory = { ctx -> ImageDocumentView(ctx).also {
                view = it; it.onEndReached = { ended = true }
                it.configure(ImagePreferences(verticalFit = ImageFit.PAGE, spacing = 96f), android.graphics.Color.WHITE)
                it.open(source, ImageLocator(source.pages.value.lastIndex, completed = true))
            } }, modifier = Modifier.fillMaxSize()) }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            compose.runOnIdle {
                assertFalse(ended)
                assertFalse(source.pages.value[source.pages.value.lastIndex - 1].measured)
                view.turn(1)
            }
            compose.waitUntil(20_000) { ended }
        } finally { compose.runOnIdle { view.dispose() }; source.close(); directory.deleteRecursively() }
    }
    @Test fun restoredLastSpreadWaitsForAnotherTurnAndVerticalEndWaitsForRendering() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "image-end-test")
        val source = ImageSource.archive(context, ImageStore(directory), { instrumentation.context.assets.open("images/mixed.cbz") })
        lateinit var view: ImageDocumentView
        var ends = 0
        val renderedAtEnd = mutableListOf<Boolean>()
        try {
            compose.setContent { AndroidView(factory = { ctx -> ImageDocumentView(ctx).also {
                view = it; it.onEndReached = { renderedAtEnd += it.renderStatus().let { s -> s.visible > 0 && s.complete == s.visible }; ends++ }
                it.configure(ImagePreferences(mode = ImageMode.PAGED, pagedFit = ImageFit.PAGE), android.graphics.Color.WHITE)
                it.open(source, ImageLocator(source.pages.value.lastIndex))
            } }, modifier = Modifier.fillMaxSize()) }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            compose.runOnIdle { assertEquals(0, ends); view.turn(-1); view.turn(1) }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            compose.runOnIdle { assertEquals("Arriving on the last spread must leave time to read it", 0, ends); view.turn(1) }
            compose.waitUntil(20_000) { ends == 1 }
            compose.runOnIdle {
                assertTrue(view.locator().completed)
                assertTrue(renderedAtEnd.all { it })
                view.configure(ImagePreferences(mode = ImageMode.VERTICAL, verticalFit = ImageFit.PAGE, inverted = true), android.graphics.Color.BLACK)
                view.move(ImageLocator(0), announceEnd = false)
                view.move(ImageLocator(source.pages.value.lastIndex, completed = true))
            }
            compose.waitUntil(20_000) { ends == 2 }
            compose.runOnIdle {
                assertTrue(view.locator().completed)
                assertTrue(renderedAtEnd.all { it })
            }
        } finally { compose.runOnIdle { view.dispose() }; source.close(); directory.deleteRecursively() }
    }
    @Test fun modesSpreadDirectionZoomAndInversionKeepTheAnchor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "image-view-test")
        val source = ImageSource.archive(context, ImageStore(directory), { instrumentation.context.assets.open("images/mixed.cbz") })
        lateinit var view: ImageDocumentView
        var ready = false
        try {
            compose.setContent { AndroidView(factory = { ctx -> ImageDocumentView(ctx).also {
                view = it; it.onReady = { ready = true }; it.open(source, ImageLocator())
            } }, modifier = Modifier.fillMaxSize().testTag("image_canvas")) }
            compose.waitUntil(20_000) { ready }
            compose.runOnIdle {
                view.move(ImageLocator(1, x = .1f, y = .25f, zoom = 2f))
            }
            compose.waitUntil(20_000) { source.pages.value[1].measured }
            compose.runOnIdle {
                assertEquals(1, view.locator().page); assertEquals(.25f, view.locator().y, .02f)
                view.configure(ImagePreferences(inverted = true, spacing = 24f), android.graphics.Color.BLACK)
                assertEquals(1, view.locator().page); assertEquals(.25f, view.locator().y, .02f)
                view.configure(ImagePreferences(mode = ImageMode.PAGED, spread = ImageSpread.DOUBLE), android.graphics.Color.WHITE)
                assertEquals(1, view.locator().page)
                view.move(ImageLocator(1)); view.turn(1); assertEquals(3, view.locator().page)
                view.configure(ImagePreferences(mode = ImageMode.PAGED, direction = ImageDirection.RTL), android.graphics.Color.WHITE)
                view.move(ImageLocator(1))
            }
            compose.onNodeWithTag("image_canvas").performTouchInput { swipeRight() }
            compose.runOnIdle { assertEquals(2, view.locator().page) }
            compose.onNodeWithTag("image_canvas").performTouchInput { doubleClick(center) }
            compose.runOnIdle { assertEquals(2f, view.locator().zoom, .01f) }
            compose.onNodeWithTag("image_canvas").performTouchInput { swipeRight() }
            compose.runOnIdle {
                assertEquals(2, view.locator().page)
                view.move(ImageLocator(2))
            }
            compose.onNodeWithTag("image_canvas").performTouchInput {
                advanceEventTime(500)
                pinch(start0 = center * .7f, end0 = center * .3f, start1 = center * 1.3f, end1 = center * 1.7f)
            }
            compose.runOnIdle {
                assertEquals(2, view.locator().page)
                assertTrue("pinch zoom=${view.locator().zoom}", view.locator().zoom > 1.2f)
                view.move(ImageLocator(source.pages.value.lastIndex))
                assertEquals(source.pages.value.lastIndex, view.locator().page)
            }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible && it.settled } }
            compose.runOnIdle {
                assertTrue(view.renderStatus().cacheBytes <= view.renderStatus().cacheLimit)
                assertEquals(0, view.renderStatus().failures)
            }
        } finally { compose.runOnIdle { view.dispose() }; source.close(); directory.deleteRecursively() }
    }
}
