package com.poyal.yeobaek

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.pdf.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun fixture(name: String, block: suspend (PdfEngine, File) -> Unit) {
        val file = File(context.cacheDir, "test-$name")
        instrumentation.context.assets.open("pdf/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        val engine = PdfEngine(context)
        try { block(engine, file) } finally { withContext(Dispatchers.Main) { engine.dispose() }; file.delete() }
    }
    @Test fun isolatedRendererSearchSelectionRotationAndInversion() = runBlocking {
        fixture("mixed.pdf") { engine, file ->
            val before = file.readBytes()
            val doc = engine.open(file)
            try {
                assertEquals(3, doc.pages.size)
                assertEquals(PdfPageSize(800, 600), doc.pages[1])
                for (page in 0..2) {
                    val found = doc.search(page, "Searchable", 0)
                    assertEquals(1, found.matches.size)
                    val range = doc.range(found.matches.single())
                    assertEquals("Searchable", range.text)
                    assertTrue(range.rects.isNotEmpty())
                    val rect = range.rects.first()
                    assertTrue(rect.left in 0f..1f && rect.top in 0f..1f)
                    assertTrue(rect.right > rect.left && rect.bottom > rect.top)
                    val selected = doc.select(page, (rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2, (rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
                    assertTrue(selected.text.contains("Searchable"))
                }
                val tile = doc.render(0, 600, 800, 0, 0, 512, 512)
                assertEquals(Color.WHITE, tile.getPixel(0, 0))
                val inverted = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                Canvas(inverted).drawBitmap(tile, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(PDF_INVERSION) })
                for ((x, y) in listOf(0 to 0, 60 to 330, 80 to 350, 80 to 470)) {
                    val a = tile.getPixel(x, y); val b = inverted.getPixel(x, y)
                    assertEquals(255 - Color.red(a), Color.red(b)); assertEquals(255 - Color.green(a), Color.green(b)); assertEquals(255 - Color.blue(a), Color.blue(b)); assertEquals(Color.alpha(a), Color.alpha(b))
                }
                assertArrayEquals(before, file.readBytes())
                tile.recycle(); inverted.recycle()
            } finally { doc.close() }
        }
    }
    @Test fun scannedPagesHaveNoTextAndLargeDocumentCanJumpToLastPage() = runBlocking {
        fixture("scanned.pdf") { engine, file ->
            val doc = engine.open(file)
            try {
                assertEquals(0, doc.search(0, "story", 0).textCount)
                assertEquals(0, doc.select(0, .2f, .2f, .8f, .8f).count)
                doc.render(0, 600, 800, 0, 0, 512, 512).recycle()
            } finally { doc.close() }
        }
        fixture("large.pdf") { engine, file ->
            val doc = engine.open(file)
            try {
                assertEquals(500, doc.pages.size)
                assertEquals(1, doc.search(499, "page 500", 0).matches.size)
                doc.render(499, 3000, 4000, 1024, 2048, 512, 512).recycle()
            } finally { doc.close() }
        }
    }
    @Test fun cancellingTilesKeepsTheConnectionUsable() = runBlocking {
        fixture("mixed.pdf") { engine, file ->
            val doc = engine.open(file)
            try {
                repeat(30) { withTimeoutOrNull(1) { doc.render(0, 3000, 4000, 0, 0, 512, 512).recycle() } }
                assertEquals(1, doc.search(0, "story", 0).matches.size)
                doc.render(0, 600, 800, 0, 0, 512, 512).recycle()
            } finally { doc.close() }
        }
    }
    @Test fun cachedPagesSurviveEvictionAndOtherDocumentClose() = runBlocking {
        fixture("mixed.pdf") { engine, file ->
            val first = engine.open(file)
            val second = engine.open(file)
            try {
                val expected = first.render(0, 600, 800, 0, 0, 512, 512)
                try {
                    // More document/page pairs than the two-page native cache can retain.
                    for (page in 0..2) second.render(page, 600, 800, 0, 0, 512, 512).recycle()
                    val reloaded = first.render(0, 600, 800, 0, 0, 512, 512)
                    try { assertTrue(expected.sameAs(reloaded)) } finally { reloaded.recycle() }
                    second.close()
                    assertEquals(1, first.search(0, "Searchable", 0).matches.size)
                    val remaining = first.render(0, 600, 800, 0, 0, 512, 512)
                    try { assertTrue(expected.sameAs(remaining)) } finally { remaining.recycle() }
                } finally { expected.recycle() }
            } finally { first.close(); second.close() }
            val reopened = engine.open(file)
            try { reopened.render(0, 600, 800, 0, 0, 512, 512).recycle() } finally { reopened.close() }
        }
    }
    @Test fun passwordDocumentExplainsUnsupportedEncryption() = runBlocking {
        fixture("locked.pdf") { engine, file ->
            try { engine.open(file); fail("Locked PDF accepted") }
            catch (e: PdfFailure) { assertTrue(e.message.orEmpty().contains("암호")) }
        }
    }
    @Test fun brokenDocumentReturnsRecoverableError() = runBlocking {
        fixture("broken.pdf") { engine, file ->
            try { engine.open(file); fail("Broken PDF accepted") } catch (_: PdfFailure) { }
        }
    }
}
