@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    private fun book(fixed: Boolean, paragraphs: Int = 60, nestedOutline: Boolean = false): Pair<DocumentEntry, Uri> {
        val name = "epub-test-${UUID.randomUUID()}.epub"
        val file = EpubFixtures.write(File(context.cacheDir, name), fixed = fixed, paragraphs = paragraphs, nestedOutline = nestedOutline)
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
    private suspend fun script(value: String): String? = withContext(Dispatchers.Main) {
        val host = compose.activity.supportFragmentManager.fragments.filterIsInstance<EpubHostFragment>().single()
        host.childFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().single().evaluateJavascript(value)
    }
    private fun waitChapter(vm: AppViewModel, number: Int) {
        compose.waitUntil(15_000) {
            vm.epubReader.ready.value && !vm.epubReader.navigation.value.moving &&
                vm.epubReader.locator.value?.href.toString().contains("chapter$number.xhtml")
        }
    }
    private fun captureScreen(name: String) {
        val directory = File(context.filesDir, "epub-navigation").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun horizontalSwipe(forward: Boolean, vertical: Boolean = false) {
        // Avoid Readium's edge gutter and use real input timing for its native pager.
        val bounds = compose.onNodeWithTag("epub_body").fetchSemanticsNode().boundsInWindow
        val from = bounds.left + bounds.width * if (forward) .8f else .2f
        val to = bounds.left + bounds.width * if (forward) .2f else .8f
        val start = SystemClock.uptimeMillis()
        for (step in 0..12) {
            if (step > 0) SystemClock.sleep(8)
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val x = if (vertical) bounds.center.x else from + (to - from) * step / 12
            val y = if (vertical) bounds.top + bounds.height * (.8f - .6f * step / 12) else bounds.center.y
            // A horizontal page turn needs a fling; keep its velocity independent of emulator load.
            val event = MotionEvent.obtain(start, start + step * 8L, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    @Test fun chapterButtonSettingsKeepLocationAndEndActionAvailable() = runBlocking<Unit> {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            script("document.getElementById('p20').scrollIntoView(); window.__buttonSettingsProbe = 1")
            delay(400)
            val bounds = compose.onNodeWithTag("epub_body").fetchSemanticsNode().boundsInRoot
            val top = script("document.getElementById('p20').getBoundingClientRect().top")!!.toDouble()
            // A settings-store emission while reading must not recreate the document.
            context.readerSettings.fields("book:$bookId:EPUB", mapOf("chapterButtons" to "WITH_MENU"))
            compose.waitUntil(10_000) { vm.epub.value.chapterButtons == EpubChapterButtons.WITH_MENU }
            compose.onNodeWithTag("epub_next").assertIsDisplayed()
            repeat(2) { toggle ->
                compose.onNodeWithTag("epub_body").performTouchInput { click() }
                compose.waitUntil(5_000) { vm.epubReader.chrome.value == (toggle == 1) }
                if (toggle == 0) compose.onNodeWithTag("epub_next").assertDoesNotExist()
                else compose.onNodeWithTag("epub_next").assertIsDisplayed()
                assertEquals(bounds, compose.onNodeWithTag("epub_body").fetchSemanticsNode().boundsInRoot)
                assertEquals(top, script("document.getElementById('p20').getBoundingClientRect().top")!!.toDouble(), 1.0)
                assertEquals("1", script("window.__buttonSettingsProbe"))
            }
            compose.onNodeWithTag("epub_settings").performClick()
            compose.onNodeWithTag("scope_BOOK").performClick()
            compose.onNodeWithTag("epub_chapter_buttons_WITH_MENU").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("epub_chapter_buttons_HIDDEN").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.epub.value.chapterButtons == EpubChapterButtons.HIDDEN }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("epub_chapter_buttons_HIDDEN") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("장별 스크롤의 좌우 화살표에 적용됩니다. 화살표를 숨겨도 장 끝의 ‘다음 장으로’ 버튼은 표시됩니다.").performScrollTo()
            captureScreen("chapter-button-settings")
            withContext(Dispatchers.Main) { vm.closeEpubSettings() }
            waitReady(vm)
            compose.onNodeWithTag("epub_next").assertDoesNotExist()
            compose.onNodeWithTag("epub_previous").assertDoesNotExist()
            withContext(Dispatchers.Main) { vm.epubReader.follow(vm.epubReader.opened!!.document.publication.readingOrder[1]) }
            waitChapter(vm, 2)
            compose.onNodeWithTag("epub_link_return").assertIsDisplayed().performClick()
            waitChapter(vm, 1)
            compose.onNodeWithTag("epub_body").performTouchInput { click() }
            compose.waitUntil(5_000) { !vm.epubReader.chrome.value }
            withContext(Dispatchers.Main) { vm.epubReader.userNavigation() }
            script("window.scrollTo(0, document.scrollingElement.scrollHeight)")
            compose.waitUntil(10_000) { vm.epubReader.navigation.value.endActionTop != null }
            compose.onNodeWithTag("epub_next_chapter").assertIsDisplayed().performClick()
            waitChapter(vm, 2)
            close(vm)
            withContext(Dispatchers.Main) { vm.open(entry) }
            waitReady(vm)
            assertEquals(EpubChapterButtons.HIDDEN, vm.epub.value.chapterButtons)
            compose.onNodeWithTag("epub_next").assertDoesNotExist()
            compose.onNodeWithTag("epub_settings").performClick()
            compose.onNodeWithTag("scope_BOOK").performScrollTo().performClick()
            compose.onNodeWithTag("epub_reset").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.epub.value.chapterButtons == EpubChapterButtons.ALWAYS }
            withContext(Dispatchers.Main) { vm.closeEpubSettings() }
            waitReady(vm)
            compose.onNodeWithTag("epub_next").assertIsDisplayed()
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun switchingBackToScrollKeepsTouchScrolling() = runBlocking {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false, paragraphs = 12)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            for (mode in listOf(EpubMode.PAGED, EpubMode.SCROLL)) {
                compose.onNodeWithTag("epub_settings").performClick()
                compose.onNodeWithTag("scope_BOOK").performClick()
                withContext(Dispatchers.Main) {
                    vm.changeSetting("EPUB", "font", EpubFont.SERIF)
                    vm.changeSetting("EPUB", "mode", mode)
                }
                compose.waitUntil(10_000) { vm.epub.value.mode == mode }
                withContext(Dispatchers.Main) { vm.closeEpubSettings() }
                waitReady(vm); delay(700)
            }
            val before = script("scrollY")!!.toDouble()
            compose.onNodeWithTag("epub_body").performTouchInput {
                swipe(start = center.copy(y = height * .75f), end = center.copy(y = height * .25f), durationMillis = 400)
            }
            delay(1000)
            val after = script("scrollY")!!.toDouble()
            val geometry = script("({y:scrollY,h:innerHeight,total:document.scrollingElement.scrollHeight,overflow:getComputedStyle(document.documentElement).overflow,body:getComputedStyle(document.body).overflow})")
            assertTrue("Touch must scroll after switching reading modes: before=$before after=$after $geometry", after > before + 10)
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun chapterIconsSurviveHiddenChromeAndEndButtonMovesOnlyOneChapter() = runBlocking {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            compose.onNodeWithTag("epub_previous").assertIsNotEnabled()
            compose.onNodeWithTag("epub_next").assertIsEnabled()
            val bounds = compose.onNodeWithTag("epub_body").fetchSemanticsNode().boundsInRoot
            assertEquals("Scrolling text must use the entire reader viewport", compose.onNodeWithTag("epub_reader").fetchSemanticsNode().boundsInRoot, bounds)
            script("document.getElementById('p20').scrollIntoView()")
            delay(400)
            val topBefore = script("document.getElementById('p20').getBoundingClientRect().top")!!.toDouble()
            repeat(3) { toggle ->
                compose.onNodeWithTag("epub_body").performTouchInput { click() }
                compose.waitUntil(5_000) { vm.epubReader.chrome.value == (toggle % 2 == 1) }
                assertEquals(bounds, compose.onNodeWithTag("epub_body").fetchSemanticsNode().boundsInRoot)
                assertEquals("Menu toggles must not move the reading line", topBefore,
                    script("document.getElementById('p20').getBoundingClientRect().top")!!.toDouble(), 1.0)
            }
            captureScreen("hidden-chrome-full-viewport")
            compose.onNodeWithTag("epub_footer").assertDoesNotExist()
            compose.onNodeWithTag("epub_next").assertIsDisplayed()
            assertEquals(bounds, compose.onNodeWithTag("epub_body").fetchSemanticsNode().boundsInRoot)
            withContext(Dispatchers.Main) {
                assertTrue(vm.epubReader.renderer!!.advance(true))
                assertFalse("A second tap must not skip a chapter", vm.epubReader.renderer!!.advance(true))
            }
            waitChapter(vm, 2)
            compose.onNodeWithTag("epub_previous").assertIsEnabled()
            compose.onNodeWithTag("epub_next").assertIsDisplayed()
            withContext(Dispatchers.Main) { vm.epubReader.userNavigation() }
            script("window.scrollTo(0, document.scrollingElement.scrollHeight)")
            compose.waitUntil(10_000) { vm.epubReader.navigation.value.endActionTop != null }
            compose.onNodeWithTag("epub_next_chapter_title").assertTextContains("3장", substring = true)
            captureScreen("hidden-chrome-end")
            compose.onNodeWithTag("epub_next_chapter").assertIsDisplayed().performClick()
            waitChapter(vm, 3)
            assertFalse("Entering the final chapter is not completion", vm.epubReader.completed.value)
            compose.onNodeWithTag("epub_next").assertIsNotEnabled()
            withContext(Dispatchers.Main) { vm.epubReader.userNavigation() }
            script("window.scrollTo(0, document.scrollingElement.scrollHeight)")
            compose.waitUntil(10_000) { vm.epubReader.navigation.value.endActionTop != null }
            compose.waitUntil(10_000) { vm.epubReader.completed.value }
            withContext(Dispatchers.Main) { vm.dismissReadingEnd() }
            compose.onNodeWithTag("epub_book_end").assertIsDisplayed()
            compose.onNodeWithTag("epub_next_chapter").assertDoesNotExist()
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun shortChapterEndButtonFollowsTextAndScrollReopenPreservesAnchor() = runBlocking {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false, paragraphs = 1)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            compose.waitUntil(10_000) { vm.epubReader.navigation.value.endActionTop != null }
            val viewportHeight = compose.onNodeWithTag("epub_body").fetchSemanticsNode().boundsInRoot.height
            val headingTop = script("document.getElementById('heading').getBoundingClientRect().top / innerHeight")!!.toDouble() * viewportHeight
            assertTrue("The chapter's first heading must clear the header", headingTop >= vm.epubReader.startInsetHeight.value - 1)
            compose.onNodeWithTag("epub_next_chapter").assertIsDisplayed()
            captureScreen("short-chapter-end")
            assertEquals("The end action must start after the last real element", "true", script("""
                (() => {const slot = document.querySelector('[data-yeobaek-navigation]');
                    return slot.getBoundingClientRect().top >= slot.previousElementSibling.getBoundingClientRect().bottom - 1;})()
            """.trimIndent()))
            val anchor = withContext(Dispatchers.Main) { vm.epubReader.capture() }!!
            assertNotNull(anchor.locations.cssSelector)
            compose.onNodeWithTag("epub_bookmark").performClick()
            compose.waitUntil(10_000) { vm.epubReader.marks.value.isNotEmpty() }
            close(vm)
            withContext(Dispatchers.Main) { vm.open(entry) }
            waitReady(vm); delay(700)
            assertVisible(anchor, "scroll reopen")
            compose.onNodeWithTag("epub_next_chapter").assertIsDisplayed()
            compose.onNodeWithTag("epub_next_chapter").performClick()
            waitChapter(vm, 2)
            withContext(Dispatchers.Main) { vm.epubReader.openBookmark(vm.epubReader.marks.value.single()) }
            waitChapter(vm, 1)
            assertVisible(anchor, "scroll bookmark")
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun nestedOutlineHighlightsCurrentAnchorWithoutChangingReadingOrder() = runBlocking {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false, nestedOutline = true)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            compose.onNodeWithTag("epub_next").performClick()
            waitChapter(vm, 2)
            script("document.getElementById('p40').scrollIntoView()")
            compose.waitUntil(10_000) { vm.epubReader.navigation.value.outlineIndex == 5 }
            compose.onNodeWithTag("epub_progress").assertTextContains("2 / 3", substring = true)
            compose.onNodeWithTag("epub_contents").performClick()
            compose.onNodeWithTag("epub_chapter_5").assertIsDisplayed().assertIsSelected()
            withContext(Dispatchers.Main) { vm.sheet(null) }
            compose.onNodeWithTag("epub_next").performClick()
            waitChapter(vm, 3)
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun longChapterScrollAnchorSurvivesReopenRecreationAndFontChange() = runBlocking {
        var vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false)
        var bookId: String? = null
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            script("document.getElementById('p55').scrollIntoView()")
            delay(500)
            val anchor = withContext(Dispatchers.Main) { vm.epubReader.capture() }!!
            val selector = JSONObject.quote(requireNotNull(anchor.locations.cssSelector))
            val topBefore = script("document.querySelector($selector).getBoundingClientRect().top")!!.toDouble()
            close(vm)
            withContext(Dispatchers.Main) { vm.open(entry) }
            waitReady(vm); delay(700)
            assertVisible(anchor, "long scroll reopen")
            val topAfter = script("document.querySelector($selector).getBoundingClientRect().top")!!.toDouble()
            assertEquals("Unchanged scroll layout must restore its viewport", topBefore, topAfter, 2.0)
            withContext(Dispatchers.Main) { vm.flushPosition() }
            compose.activityRule.scenario.recreate()
            vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            waitReady(vm); delay(700)
            assertVisible(anchor, "long scroll recreation")
            compose.onNodeWithTag("epub_settings").performClick()
            compose.onNodeWithTag("scope_BOOK").performClick()
            withContext(Dispatchers.Main) { vm.changeSetting("EPUB", "size", 1.6f) }
            compose.waitUntil(10_000) { vm.epub.value.size == 1.6f }
            withContext(Dispatchers.Main) { vm.closeEpubSettings() }
            waitReady(vm); delay(700)
            assertVisible(anchor, "long scroll larger font")
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
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
            compose.onNodeWithTag("epub_body").performTouchInput {
                swipe(start = center.copy(y = height * .75f), end = center.copy(y = height * .25f), durationMillis = 400)
            }
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

    @Test fun disabledSwipesKeepTextPositionButAllowVerticalScrollAndButtons() = runBlocking<Unit> {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(false)
        var bookId: String? = null
        suspend fun settings(mode: EpubMode, enabled: Boolean) {
            if (!vm.epubReader.chrome.value) {
                compose.onNodeWithTag("epub_body").performTouchInput { click(center) }
                compose.waitUntil(3000) { vm.epubReader.chrome.value }
            }
            compose.onNodeWithTag("epub_settings").performClick()
            compose.onNodeWithTag("scope_BOOK").performClick()
            withContext(Dispatchers.Main) { vm.changeSetting("EPUB", "mode", mode) }
            compose.waitUntil(5000) { vm.epub.value.mode == mode }
            compose.onNodeWithTag("epub_pinch_zoom").assertDoesNotExist()
            if (vm.epub.value.swipeToTurn != enabled) compose.onNodeWithTag("epub_swipe_to_turn").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.epub.value.swipeToTurn == enabled }
            withContext(Dispatchers.Main) { vm.closeEpubSettings() }
            waitReady(vm); delay(400)
        }
        fun position() = vm.epubReader.locator.value!!.let { it.href to it.locations.progression }
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            for (mode in EpubMode.entries) {
                settings(mode, false)
                val before = position()
                horizontalSwipe(true)
                delay(700)
                assertEquals("Disabled swipe must preserve $mode location", before, position())
                if (mode == EpubMode.SCROLL) {
                    val y = script("scrollY")!!.toDouble()
                    horizontalSwipe(true, vertical = true)
                    delay(700)
                    assertTrue("Vertical scroll must move from $y; now ${script("scrollY")}", script("scrollY")!!.toDouble() > y + 10)
                }
                val beforeButton = position()
                compose.onNodeWithTag("epub_next").performClick()
                compose.waitUntil(10_000) { position() != beforeButton }
                settings(mode, true)
                val enabledBefore = position()
                horizontalSwipe(true)
                try { compose.waitUntil(10_000) { position() != enabledBefore } }
                catch (error: AssertionError) { throw AssertionError("Enabled $mode swipe did not move from $enabledBefore", error) }
            }
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
            context.readerSettings.update { original }; context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun fixedLayoutPinchOptionKeepsButtonsAndZoomedPanWorking() = runBlocking<Unit> {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val original = context.readerSettings.data.first()
        val (entry, uri) = book(true)
        var bookId: String? = null
        fun fixedValue(method: String): Float {
            fun collect(view: View): List<Float> {
                if (view.javaClass.name.endsWith(".R2FXLLayout")) return listOf(view.javaClass.getMethod(method).invoke(view) as Float)
                return if (view is ViewGroup) (0 until view.childCount).flatMap { collect(view.getChildAt(it)) } else emptyList()
            }
            return compose.runOnIdle { collect(compose.activity.window.decorView).maxOrNull() ?: error("Missing fixed layout") }
        }
        suspend fun settings(pinch: Boolean) {
            compose.onNodeWithTag("epub_settings").performClick()
            compose.onNodeWithTag("scope_BOOK").performClick()
            compose.onNodeWithTag("epub_swipe_to_turn").assertDoesNotExist()
            if (vm.epub.value.pinchZoom != pinch) compose.onNodeWithTag("epub_pinch_zoom").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.epub.value.pinchZoom == pinch }
            withContext(Dispatchers.Main) { vm.closeEpubSettings() }
            waitReady(vm); delay(400)
        }
        try {
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.screen("folder"); vm.open(entry) }
            waitReady(vm); bookId = vm.currentBook!!.id
            settings(false)
            val before = vm.epubReader.locator.value!!.href
            compose.onNodeWithTag("epub_body").performTouchInput { pinch(center * .8f, center * .4f, center * 1.2f, center * 1.6f) }
            delay(400); assertEquals(1f, fixedValue("getScale"), .02f)
            assertEquals(before, vm.epubReader.locator.value!!.href)
            compose.onNodeWithTag("epub_next").performClick(); waitChapter(vm, 2)
            settings(true)
            compose.onNodeWithTag("epub_body").performTouchInput { pinch(center * .8f, center * .4f, center * 1.2f, center * 1.6f) }
            delay(500); assertTrue("Pinch must actually enlarge fixed content", fixedValue("getScale") > 1.2f)
            val pan = fixedValue("getPosX")
            horizontalSwipe(true)
            assertTrue("Zoomed content must still pan", kotlin.math.abs(fixedValue("getPosX") - pan) > 1f)
            delay(600); assertTrue(vm.epubReader.locator.value!!.href.toString().contains("chapter2"))
        } finally {
            close(vm); bookId?.let { context.database.library().forget(it) }
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
