@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek

import android.content.ContentValues
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in: am instrument -e class com.poyal.yeobaek.EpubSoakTest -e epubSoakSeconds 1800. */
class EpubSoakTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun repeatedlyNavigateSearchChangeSettingsAndReopen(): Unit = runBlocking {
        val seconds = InstrumentationRegistry.getArguments().getString("epubSoakSeconds")?.toLongOrNull()
        assumeTrue(seconds != null && seconds >= 60)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val settings = app.readerSettings.data.first()
        val name = "epub-soak-${UUID.randomUUID()}.epub"
        val file = EpubFixtures.write(File(app.cacheDir, name), chapters = 40, paragraphs = 150)
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/YeobaekEpubTests")
        })!!
        file.inputStream().use { input -> app.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
        file.delete()
        val entry = DocumentEntry(uri.toString(), uri.toString(), name, false, null, null, "application/epub+zip")
        val samples = JSONArray()
        val report = JSONObject().put("requested_seconds", seconds).put("samples", samples)
        val output = File(app.filesDir, "epub-soak.json")
        var bookId: String? = null
        suspend fun main(block: suspend () -> Unit) = withContext(Dispatchers.Main) { block() }
        fun ready() = compose.waitUntil(30_000) { vm.epubReader.ready.value && vm.epubReader.renderer != null && vm.ui.value.error == null }
        try {
            main { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            ready(); bookId = vm.currentBook!!.id
            val start = SystemClock.elapsedRealtime()
            var cycle = 0
            do {
                val chapter = cycle * 17 % 40
                val doc = vm.ui.value.epubReader!!.document
                main { vm.epubReader.follow(doc.publication.readingOrder[chapter]) }
                try {
                    compose.waitUntil(15_000) { vm.epubReader.locator.value?.href.toString().endsWith("chapter${chapter + 1}.xhtml") }
                } catch (e: Throwable) {
                    throw AssertionError("cycle=$cycle expected=chapter${chapter + 1} actual=${vm.epubReader.locator.value?.href} ready=${vm.epubReader.ready.value} pending=${vm.epubReader.pending.value?.href}", e)
                }
                main { vm.epubReader.renderer!!.advance(true) }
                delay(250)
                main { vm.flushPosition() }
                assertNotNull(app.database.library().epubPosition(bookId, vm.currentBook!!.version))
                if (cycle % 5 == 0) {
                    main { vm.epubReader.startSearch("검색구절") }
                    compose.waitUntil(30_000) { !vm.epubReader.search.value.running }
                    assertNull(vm.epubReader.search.value.error)
                    assertEquals(6000, vm.epubReader.search.value.count)
                    main { vm.epubReader.searchPage(5900) }
                    compose.waitUntil(10_000) { vm.epubReader.search.value.start == 5900 }
                    assertEquals(100, vm.epubReader.search.value.rows.size)
                }
                if (cycle % 10 == 0) {
                    main { vm.openEpubSettings() }
                    compose.waitUntil(10_000) { vm.ui.value.screen == "epub_settings" }
                    val mode = if (cycle % 20 == 0) EpubMode.PAGED else EpubMode.SCROLL
                    app.readerSettings.fields("book:$bookId:EPUB", mapOf("mode" to mode.name, "size" to if (cycle % 20 == 0) "1.3" else "1.0"))
                    compose.waitUntil(10_000) { vm.epub.value.mode == mode }
                    main { vm.closeEpubSettings() }; ready()
                }
                if (cycle % 30 == 29) {
                    val old = vm.epubReader.opened!!.document
                    main { vm.closeReader() }
                    compose.waitUntil(10_000) { !vm.ui.value.hasReader }
                    assertTrue(old.closed.get())
                    main { vm.open(entry) }; ready()
                }
                if (cycle % 5 == 0) {
                    samples.put(JSONObject().put("cycle", cycle).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                        .put("pss_kib", Debug.getPss()).put("fds", File("/proc/self/fd").list()?.size ?: 0))
                    output.writeText(report.toString(2))
                }
                assertNull(vm.ui.value.error)
                cycle++
                delay(500)
            } while (SystemClock.elapsedRealtime() - start < seconds!! * 1000)
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - start).put("cycles", cycle).put("passed", true)
        } catch (e: Throwable) {
            report.put("passed", false).put("failure", e.toString()); throw e
        } finally {
            output.writeText(report.toString(2))
            main { vm.closeReader() }
            compose.waitUntil(10_000) { !vm.ui.value.hasReader }
            bookId?.let { app.database.library().forget(it) }
            app.readerSettings.update { settings }; app.contentResolver.delete(uri, null, null)
        }
    }
}
