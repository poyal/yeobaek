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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.DocumentEntry
import com.poyal.yeobaek.data.TxtFont
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
    @Test fun txtQuickSettingsAndDetailsShareTheFontAndReadingPosition() = runBlocking {
        val sample = (1..250).joinToString("\n") { "${it}번째 문단. 검색구절 여백의 독서는 문장을 천천히 따라갑니다." }
        withBook("txt", "text/plain", sample.toByteArray()) { vm ->
            withContext(Dispatchers.Main) { vm.seekPercent(.4f) }
            compose.waitUntil(15_000) { (vm.currentPosition?.offset ?: 0) > 0 }
            val before = vm.currentPosition!!.offset
            compose.onNodeWithTag("reader_txt").performClick()
            compose.onNodeWithTag("scope_BOOK").performScrollTo().performClick()
            compose.onNodeWithTag("quick_font").performScrollTo().performClick()
            compose.onNodeWithTag("font_SYSTEM_SANS_LIGHT").performScrollTo().performClick()
            compose.onNodeWithTag("line_spacing").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.65f) }
            compose.waitUntil(5_000) { vm.txt.value.font == TxtFont.SYSTEM_SANS_LIGHT && vm.txt.value.line == 1.65f }
            compose.waitForIdle()
            delay(400)
            capture("txt-quick-text")
            compose.onNodeWithTag("quick_details").performScrollTo().performClick()
            compose.onNodeWithTag("txt_options").assertIsDisplayed()
            delay(400)
            capture("txt-detail-text")
            compose.onNodeWithTag("quick_font").performScrollTo().assertTextContains("글꼴 · 가는 고딕")
            compose.onNodeWithTag("line_spacing_value").performScrollTo().assertTextContains("줄 간격 · 1.65배")
            compose.onNodeWithTag("page_back").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty() }
            assertTrue("Reflow must keep the same sentence", kotlin.math.abs(vm.currentPosition!!.offset - before) < 40)
        }
    }

    @Test fun pdfAndImageQuickSpacingAndFitKeepTheSelectedPage() = runBlocking {
        val pdfBytes = ByteArrayOutputStream().also { out ->
            val pdf = PdfDocument()
            try {
                for (n in 1..3) pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, n).create()))
                pdf.writeTo(out)
            } finally { pdf.close() }
        }.toByteArray()
        val imageBytes = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            ZipOutputStream(out).use { zip ->
                for (n in 1..3) {
                    zip.putNextEntry(ZipEntry("$n.png"))
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                }
            }
            bitmap.recycle()
        }.toByteArray()
        for ((extension, mime, bytes) in listOf(Triple("pdf", "application/pdf", pdfBytes),
            Triple("cbz", "application/vnd.comicbook+zip", imageBytes))) {
            val pdf = extension == "pdf"
            val prefix = if (pdf) "pdf" else "image"
            withBook(extension, mime, bytes) { vm ->
                compose.waitUntil(15_000) {
                    runCatching {
                        if (pdf) native<PdfDocumentView>().renderStatus().visible > 0
                        else native<ImageDocumentView>().renderStatus().visible > 0
                    }.getOrDefault(false)
                }
                compose.runOnIdle {
                    if (pdf) native<PdfDocumentView>().move(PdfLocator(page = 1))
                    else native<ImageDocumentView>().move(ImageLocator(page = 1), announceEnd = false)
                }
                compose.onNodeWithTag("${prefix}_settings").performClick()
                compose.onNodeWithTag("scope_BOOK").performScrollTo().performClick()
                compose.onNodeWithTag("${prefix}_fit_PAGE").performScrollTo().performClick()
                compose.onNodeWithTag("${prefix}_spacing").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
                if (!pdf) {
                    compose.onNodeWithTag("image_mode_PAGED").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("image_direction_RTL").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("image_direction_RTL").performScrollTo().performClick()
                    compose.onNodeWithTag("image_fit_WIDTH").performScrollTo().performClick()
                    compose.onNodeWithTag("image_mode_VERTICAL").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("image_direction_RTL").fetchSemanticsNodes().isEmpty() }
                    compose.onNodeWithTag("image_fit_PAGE").performScrollTo().assertIsSelected()
                    compose.onNodeWithTag("image_direction_RTL").assertDoesNotExist()
                }
                compose.onNodeWithTag("quick_details").performScrollTo().performClick()
                compose.onNodeWithTag("${prefix}_fit_PAGE").performScrollTo().assertIsSelected()
                compose.onNodeWithTag("${prefix}_spacing_value").performScrollTo().assertTextContains("12 dp", substring = true)
                compose.onNodeWithTag("page_back").performClick()
                compose.waitUntil(15_000) {
                    if (pdf) vm.pdfReader.locator.value.page == 1 else vm.imageReader.locator.value.page == 1
                }
            }
        }
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
