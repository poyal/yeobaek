@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.epub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.json.JSONObject
import java.io.File
import java.util.UUID

class EpubReaderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
    private fun book(fixed: Boolean): Pair<DocumentEntry, Uri> {
        val name = "epub-test-${UUID.randomUUID()}.epub"
        val file = EpubFixtures.write(File(context.cacheDir, name), fixed = fixed)
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/YeobaekEpubTests")
        })!!
        file.inputStream().use { input -> context.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) } }; file.delete()
        return DocumentEntry(uri.toString(), uri.toString(), name, false, null, null, "application/epub+zip") to uri
    }
    private fun waitReady(vm: AppViewModel) { compose.waitUntil(30_000) { vm.epubReader.ready.value && vm.epubReader.renderer != null && vm.ui.value.error == null } }
    private suspend fun close(vm: AppViewModel) {
        withContext(Dispatchers.Main) { vm.closeReader() }
        compose.waitUntil(10_000) { !vm.ui.value.hasReader }
    }
    private suspend fun assertVisible(anchor: Locator, stage: String) = withContext(Dispatchers.Main) {
        val host = compose.activity.supportFragmentManager.fragments.filterIsInstance<EpubHostFragment>().single()
        val native = host.childFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().single()
        val selector = JSONObject.quote(requireNotNull(anchor.locations.cssSelector))
        // Pagination may put an earlier paragraph at the top. The saved paragraph must be on this page.
        val result = native.evaluateJavascript("""
            (() => { const e = document.querySelector($selector); if (!e) return false;
                return Array.from(e.getClientRects()).some(r => r.bottom > 1 && r.top < innerHeight - 1 && r.right > 1 && r.left < innerWidth - 1); })()
        """.trimIndent())
        val details = native.evaluateJavascript("""JSON.stringify({rects: Array.from(document.querySelector($selector).getClientRects()).map(r=>({x:r.x,y:r.y,w:r.width,h:r.height})), w:innerWidth, h:innerHeight, x:scrollX, y:scrollY})""")
        assertEquals("$stage: anchor ${anchor.toJSON()} current ${native.currentLocator.value.toJSON()} bounds $details", "true", result)
    }

    @Test fun reflowChapterSearchBookmarkSettingsAndRecreationKeepLocation() = runBlocking {
        var vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            compose.onNodeWithTag("epub_next").performClick()
            compose.waitUntil(15_000) { vm.epubReader.locator.value?.href.toString().contains("chapter2") }
            compose.onNodeWithTag("epub_body").performTouchInput { swipeUp(durationMillis = 400) }
            // Let the native WebView fling settle before measuring the saved reading anchor.
            delay(2500)
            val anchor = withContext(Dispatchers.Main) { vm.epubReader.capture() }!!
            assertNotNull(anchor.locations.cssSelector)
            compose.onNodeWithTag("epub_bookmark").performClick()
            compose.waitUntil(10_000) { vm.epubReader.marks.value.isNotEmpty() }
            compose.onNodeWithTag("epub_settings").performClick()
            compose.waitUntil(10_000) { vm.ui.value.screen == "epub_settings" }
            assertFalse(vm.epubReader.ready.value)
            val settingsAnchor = vm.epubReader.locator.value!!
            compose.onNodeWithTag("scope_BOOK").performClick()
            compose.onNodeWithTag("epub_mode_paged").performClick()
            compose.waitUntil(10_000) { vm.epub.value.mode == EpubMode.PAGED }
            withContext(Dispatchers.Main) { vm.closeEpubSettings(); assertFalse(vm.epubReader.ready.value) }
            waitReady(vm); delay(700)
            val after = withContext(Dispatchers.Main) { vm.epubReader.capture() }!!
            assertEquals(anchor.href, after.href)
            assertVisible(settingsAnchor, "settings")
            withContext(Dispatchers.Main) { vm.epubReader.startSearch("검색구절") }
            compose.waitUntil(20_000) { !vm.epubReader.search.value.running && vm.epubReader.search.value.count == 180 }
            withContext(Dispatchers.Main) { vm.epubReader.searchPage(100) }
            compose.waitUntil(10_000) { vm.epubReader.search.value.start == 100 }
            assertEquals(80, vm.epubReader.search.value.rows.size)
            withContext(Dispatchers.Main) { vm.epubReader.openBookmark(vm.epubReader.marks.value.single()) }
            delay(700)
            assertVisible(anchor, "bookmark before recreation")
            withContext(Dispatchers.Main) { vm.flushPosition() }
            compose.activityRule.scenario.recreate()
            vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            waitReady(vm); delay(700)
            val recreated = withContext(Dispatchers.Main) { vm.epubReader.capture() }!!
            assertEquals(anchor.href, recreated.href)
            assertVisible(anchor, "recreation")
            close(vm)
            withContext(Dispatchers.Main) { vm.open(entry) }
            waitReady(vm); delay(700)
            val reopened = withContext(Dispatchers.Main) { vm.epubReader.capture() }!!
            assertEquals(anchor.href, reopened.href)
            assertVisible(anchor, "reopen")
        } finally {
            close(vm)
            bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun fixedLayoutPagesAndSettingsRemainIndependentFromReflow() = runBlocking {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val (entry, uri) = book(true)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            compose.onNodeWithTag("epub_progress").assertTextContains("1 / 3 페이지", substring = true)
            compose.onNodeWithTag("epub_next").performClick()
            compose.waitUntil(10_000) { vm.epubReader.locator.value?.href.toString().contains("chapter2") }
            compose.onNodeWithTag("epub_body").performTouchInput { pinch(center * .8f, center * .4f, center * 1.2f, center * 1.6f) }
            compose.onNodeWithTag("epub_settings").performClick()
            compose.waitUntil(10_000) { vm.ui.value.screen == "epub_settings" }
            compose.onNodeWithTag("epub_size").assertDoesNotExist()
            compose.onNodeWithTag("epub_canvas_AUTO").assertExists()
            withContext(Dispatchers.Main) { vm.closeEpubSettings() }
            waitReady(vm)
            compose.waitUntil(10_000) { vm.epubReader.locator.value?.href.toString().contains("chapter2") }
            assertTrue(vm.ui.value.epubReader!!.document.fixed)
        } finally {
            close(vm)
            bookId?.let { context.database.library().forget(it) }; context.contentResolver.delete(uri, null, null)
        }
    }
}
