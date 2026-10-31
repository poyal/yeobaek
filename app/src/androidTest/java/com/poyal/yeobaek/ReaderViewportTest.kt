package com.poyal.yeobaek

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.DocumentEntry
import com.poyal.yeobaek.image.*
import com.poyal.yeobaek.pdf.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Full-bleed synthetic pages distinguish a clipped viewport from page-fit margins. */
class ReaderViewportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
    private val pageColor = Color.rgb(35, 129, 173)
    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }
    private inline fun <reified T : View> native(): T = compose.activity.window.decorView.descendants().filterIsInstance<T>().single()
    private suspend fun withBook(extension: String, mime: String, bytes: ByteArray, check: suspend (AppViewModel) -> Unit) {
        val name = "viewport-${UUID.randomUUID()}.$extension"
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/YeobaekViewportTests")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        var id: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(DocumentEntry(uri.toString(), uri.toString(), name, false, null, null, mime)) }
            compose.waitUntil(30_000) { vm.ui.value.hasReader && vm.currentBook != null }
            id = vm.currentBook!!.id
            check(vm)
        } finally {
            withContext(Dispatchers.Main) { vm.closeReader() }
            compose.waitUntil(10_000) { !vm.ui.value.hasReader }
            id?.let { context.database.library().forget(it) }; context.contentResolver.delete(uri, null, null)
        }
    }
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val out = File(context.filesDir, "reader-viewport").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun assertPaintsEdges(view: View) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        for (y in listOf(2, view.height / 2, view.height - 3)) {
            val color = bitmap.getPixel(view.width / 2, y)
            assertTrue("Page must paint at viewport y=$y, color=$color",
                kotlin.math.abs(Color.red(color) - Color.red(pageColor)) < 4 &&
                    kotlin.math.abs(Color.green(color) - Color.green(pageColor)) < 4 &&
                    kotlin.math.abs(Color.blue(color) - Color.blue(pageColor)) < 4)
        }
        bitmap.recycle()
    }
    private fun assertDoesNotPaintOutside(view: View) {
        val location = IntArray(2)
        compose.runOnIdle { view.getLocationOnScreen(location) }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            for (y in listOf(location[1] - 2, location[1] + view.height + 2).filter { it in 0 until screenshot.height }) {
                val color = screenshot.getPixel(location[0] + view.width / 2, y)
                assertFalse("PDF must not paint outside its viewport into a system inset at y=$y",
                    kotlin.math.abs(Color.red(color) - Color.red(pageColor)) < 4 &&
                        kotlin.math.abs(Color.green(color) - Color.green(pageColor)) < 4 &&
                        kotlin.math.abs(Color.blue(color) - Color.blue(pageColor)) < 4)
            }
        } finally { screenshot.recycle() }
    }
    @Test fun pdfHiddenMenuDoesNotClipTheScrolledPage() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out ->
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 1800, 1).create())
                page.canvas.drawColor(pageColor); pdf.finishPage(page); pdf.writeTo(out)
            } finally { pdf.close() }
        }.toByteArray()
        withBook("pdf", "application/pdf", bytes) {
            compose.waitUntil(20_000) { runCatching { native<PdfDocumentView>().renderStatus().let { it.visible > 0 && it.complete == it.visible } }.getOrDefault(false) }
            val view = native<PdfDocumentView>()
            compose.runOnIdle { view.move(PdfLocator(y = .25f)) }
            compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible } }
            val before = compose.runOnIdle { view.locator() }
            compose.onNodeWithTag("pdf_body").performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("pdf_footer").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("pdf_footer").assertDoesNotExist()
            assertEquals(before, compose.runOnIdle { view.locator() })
            assertPaintsEdges(view); capture("pdf-hidden"); assertDoesNotPaintOutside(view)
        }
    }
    @Test fun imageAndComicHiddenMenusDoNotClipTheScrolledPage() = runBlocking {
        val png = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(400, 1800, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(pageColor); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("page.png")); it.write(png); it.closeEntry()
        } }.toByteArray()
        for ((extension, mime, bytes) in listOf(Triple("png", "image/png", png), Triple("cbz", "application/vnd.comicbook+zip", zip))) {
            withBook(extension, mime, bytes) {
                compose.waitUntil(20_000) { runCatching { native<ImageDocumentView>().renderStatus().let { it.visible > 0 && it.complete == it.visible && it.settled } }.getOrDefault(false) }
                val view = native<ImageDocumentView>()
                compose.runOnIdle { view.move(ImageLocator(y = .25f), announceEnd = false) }
                compose.waitUntil(20_000) { view.renderStatus().let { it.visible > 0 && it.complete == it.visible && it.settled } }
                val before = compose.runOnIdle { view.locator() }
                compose.onNodeWithTag("image_body").performTouchInput { click() }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("image_footer").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("image_footer").assertDoesNotExist()
                assertEquals(before, compose.runOnIdle { view.locator() })
                assertPaintsEdges(view); capture("$extension-hidden")
            }
        }
    }
}
