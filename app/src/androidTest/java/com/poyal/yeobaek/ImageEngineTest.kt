package com.poyal.yeobaek

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.image.*
import com.poyal.yeobaek.pdf.PDF_INVERSION
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ImageEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun fixture(name: String, block: suspend (ImageSource) -> Unit) {
        val directory = File(context.cacheDir, "image-test-${UUID.randomUUID()}")
        val store = ImageStore(directory)
        val source = ImageSource.archive(context, store, { instrumentation.context.assets.open("images/$name") })
        try { block(source) } finally { source.close(); directory.deleteRecursively() }
    }
    @Test fun zipRar4Rar5AndSolid7zDecodeAndNaturalSort() = runBlocking {
        for (name in listOf("mixed.cbz", "mixed.cbr", "mixed.cb7", "stored-rar4.cbr")) {
            fixture(name) { source ->
                assertEquals(name, "1.jpg", source.pages.value[0].name)
                assertEquals(name, "2.png", source.pages.value[1].name)
                assertEquals(name, "10.webp", source.pages.value[2].name)
                assertTrue(source.pages.value.none { "escape" in it.path || "__MACOSX" in it.path })
                for (page in listOf(0, 1, 2, 3, 4, 5, source.pages.value.lastIndex, 0)) {
                    val info = source.measure(page)
                    val tile = source.render(page, 240, 320, 0, 0, 240, 320)
                    assertEquals(240, tile.width); assertTrue(info.measured); tile.recycle()
                }
            }
        }
    }
    @Test fun exifOrientationAndTransparencyAreCorrectAfterInversion() = runBlocking {
        fixture("mixed.cbz") { source ->
            for (orientation in 1..8) {
                val index = source.pages.value.indexOfFirst { it.name == "orientation-$orientation.jpg" }
                val page = source.measure(index)
                assertEquals(if (orientation in 5..8) 80 else 120, page.width)
                assertEquals(if (orientation in 5..8) 120 else 80, page.height)
                val bitmap = source.render(index, page.width, page.height, 0, 0, page.width, page.height)
                val x = if (orientation in listOf(2, 3, 6, 7)) page.width - 5 else 5
                val y = if (orientation in listOf(3, 4, 7, 8)) page.height - 5 else 5
                assertTrue("orientation $orientation", Color.red(bitmap.getPixel(x, y)) > 220 && Color.green(bitmap.getPixel(x, y)) < 30)
                bitmap.recycle()
            }
            val index = source.pages.value.indexOfFirst { it.name == "2.png" }
            val bitmap = source.render(index, 240, 320, 0, 0, 240, 320)
            val inverted = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
            Canvas(inverted).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(PDF_INVERSION) })
            val original = bitmap.getPixel(30, 30); val negative = inverted.getPixel(30, 30)
            assertEquals(255 - Color.red(original), Color.red(negative)); assertEquals(255 - Color.green(original), Color.green(negative))
            assertEquals(0, Color.alpha(bitmap.getPixel(230, 310))); assertEquals(0, Color.alpha(inverted.getPixel(230, 310)))
            bitmap.recycle(); inverted.recycle()
        }
    }
    @Test fun invalidArchivesFailAndBadImagesCanBeSkipped() = runBlocking {
        for (name in listOf("locked.7z", "empty.zip", "broken.zip")) {
            try { fixture(name) { fail("$name accepted") }; fail("$name accepted") }
            catch (e: ImageFailure) { if (name == "locked.7z") assertTrue(e.message.orEmpty().contains("암호")) }
        }
        fixture("broken-image.zip") { source ->
            try { source.measure(0); fail("bad image accepted") } catch (_: ImageFailure) { }
            assertEquals(240, source.measure(1).width)
            source.thumbnail(1).recycle()
        }
    }
    @Test fun cancellingOneBookDoesNotKillAnotherAndWorkerDeathIsRecoverable() = runBlocking {
        fixture("mixed.cbz") { first ->
            fixture("mixed.cb7") { second ->
                first.measure(0); second.close()
                first.thumbnail(0).recycle()
            }
        }
        fixture("mixed.cbr") { it.thumbnail(0).recycle() }
    }
    @Test fun cancelledPreparationCleansStagingAndDamagedCacheIsRebuilt() = runBlocking {
        val directory = File(context.cacheDir, "image-cache-test-${UUID.randomUUID()}")
        val store = ImageStore(directory)
        try {
            val cancelled = withTimeoutOrNull(30) {
                store.copy({ object : java.io.InputStream() {
                    override fun read(): Int = 1
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int { Thread.sleep(10); bytes.fill(1, off, off + len); return len }
                } })
            }
            assertNull(cancelled)
            assertTrue(store.directory.listFiles().orEmpty().none { it.extension == "part" })
            val open = { instrumentation.context.assets.open("images/mixed.cbz") }
            val first = ImageSource.archive(context, store, open)
            val version = first.version; first.thumbnail(0).recycle(); first.close()
            File(store.directory, version).writeText("damaged archive cache")
            val repaired = ImageSource.archive(context, store, open, version)
            try { assertEquals(version, repaired.version); repaired.thumbnail(0).recycle() } finally { repaired.close() }
            // Simulate eviction between the existence check and cached-stream verification.
            var evicted = false
            val recovered = ImageSource.archive(context, store, {
                if (!evicted) { File(store.directory, version).delete(); evicted = true }
                open()
            }, version)
            try { assertEquals(version, recovered.version); recovered.thumbnail(0).recycle() } finally { recovered.close() }
        } finally { directory.deleteRecursively() }
    }
}
