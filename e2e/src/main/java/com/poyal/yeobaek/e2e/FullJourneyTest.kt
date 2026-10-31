package com.poyal.yeobaek.e2e

import androidx.test.uiautomator.*
import com.poyal.yeobaek.testing.JourneyDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/** S01-S11: real activities, real SAF, actual process replacement, no ViewModel shortcuts. */
class FullJourneyTest {
    private val j = JourneyDevice()
    @get:Rule val failures = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) { j.capture("FAIL-${description.methodName}") }
    }
    @Before fun setup() { j.reset() }
    @After fun finish() { j.device.unfreezeRotation() }

    @Test fun S01_cancelConnectBrowseEmptyFolderAndRestart(): Unit = j.step("S01") {
        j.click("connect_folder")
        assertTrue(j.device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui"))), 10_000))
        j.device.pressBack(); j.node("connect_folder")
        j.connect()
        j.find("folder_Empty").click()
        assertFalse(j.exists("file_01-short.txt"))
        j.click("folder_up"); j.find("file_01-short.txt")
        j.find("folder_Images", direction = Direction.UP).click(); j.find("file_1.png")
        j.shell("am force-stop ${j.pkg}"); j.launch(); j.node("folder_refresh")
        assertFalse(j.exists("connect_folder")); assertFalse(j.exists("resume_accept"))
        j.find("file_01-short.txt")
    }

    @Test fun S01_filenameSearchAndSortStayWithinTheConnectedFolder(): Unit = j.step("S01-search-sort") {
        j.connect(); j.find("folder_search").text = ".txt"
        j.find("file_01-short.txt"); j.find("file_02-story.txt")
        j.click("folder_sort"); j.text("큰 파일순").click()
        j.await("File size order did not change") {
            j.node("file_02-story.txt").visibleBounds.top < j.node("file_01-short.txt").visibleBounds.top
        }
        j.click("folder_sort"); j.text("이름순").click()
        j.await("File name order did not change") {
            j.node("file_01-short.txt").visibleBounds.top < j.node("file_02-story.txt").visibleBounds.top
        }
        j.node("folder_search").text = "absent-synthetic-book"
        j.text("검색 결과가 없어요.")
        assertFalse(j.exists("file_01-short.txt") || j.exists("folder_Images"))
        j.node("folder_search").text = ""
        j.find("folder_Images"); j.open("01-short.txt", "reader")
    }

    @Test fun S01_enteringAnotherFolderStartsAtItsBeginning(): Unit = j.step("S01-folder-position") {
        j.connect("Yeobaek")
        j.find("folder_천개 폴더")
        val area = j.node("library_list").visibleBounds
        j.device.swipe(area.centerX(), area.bottom - 100, area.centerX(), area.top + 200, 50)
        j.find("folder_천개 폴더", direction = Direction.UP).click()
        // Wait for the new listing without scrolling it: the parent's offset must not hide its beginning.
        j.await("Child folder did not open") { j.exists("folder_up") && !j.exists("folder_천개 폴더") }
        assertTrue("New folder must expose its first file", j.node("file_책 0000.txt").visibleBounds.height() > 60)
        j.click("folder_up")
        j.await("Parent folder listing did not replace the child listing") {
            !j.exists("file_책 0000.txt") && !j.exists("folder_up") && j.exists("folder_refresh")
        }
        j.text("Yeobaek")
        j.node("folder_search")
    }

    @Test fun S02_txtMoveCancelReverseToBeginningAndRestore(): Unit = j.step("S02") {
        j.connect(); j.open("01-short.txt", "reader")
        val initial = j.percent()
        j.moveText(.8f, apply = false)
        assertEquals(initial, j.percent(), .1f)
        j.moveText(.8f)
        j.await("TXT did not reach the requested section") { j.percent() in 75f..85f }
        var unchanged = 0
        var swipes = 0
        while (j.percent() > .1f && swipes++ < 160) {
            val before = j.percent()
            j.swipe("reader", false)
            val after = j.percent()
            File(j.out, "txt-reverse.ndjson").appendText(JSONObject().put("swipe", swipes)
                .put("before", before).put("after", after).toString() + "\n")
            unchanged = if (after >= before) unchanged + 1 else 0
            assertTrue("Reverse scroll stopped making progress at $after%", unchanged < 8)
        }
        assertEquals("Reverse swipes must reach the real beginning", 0f, j.percent(), .1f)
        assertTrue(j.device.hasObject(By.textContains("LINE 0000")))
        j.moveText(.55f)
        j.await("TXT seek did not complete") { j.percent() in 50f..60f }
        j.click("reader_bookmark")
        val percent = j.percent()
        val before = j.settledViewport("reader")
        j.killAndResume("reader")
        assertEquals(percent, j.percent(), .2f)
        j.assertViewportRestored("reader", before)
        j.click("reader_bookmarks"); j.node("bookmark_delete"); j.click("sheet_close")
        j.close("reader"); j.shell("am force-stop ${j.pkg}"); j.launch()
        assertFalse(j.exists("resume_accept"))
    }

    @Test fun S03_txtSearchAcrossHundredResultsReturnsToOrigin(): Unit = j.step("S03") {
        j.connect(); j.open("02-story.txt", "reader"); j.moveText(.7f)
        j.await("Seek before search") { j.percent() in 65f..75f }
        val origin = j.percent()
        j.click("reader_search"); j.node("search_input").text = "needle"; j.click("search_submit")
        j.node("search_next"); j.find("search_more", "search_results").click()
        j.await("Second search page did not appear") { j.device.hasObject(By.textContains("101번째")) }
        val row = j.node("search_result_100")
        assertTrue("The next result page must start at result 101", row.visibleBounds.height() > 24)
        row.click(); j.ready("reader")
        j.click("reader_search"); j.click("search_previous"); j.ready("reader")
        j.click("reader_search"); j.click("search_next"); j.ready("reader")
        j.click("reader_search"); j.click("search_return"); j.ready("reader")
        assertEquals("Search return must restore the original sentence", origin, j.percent(), .2f)
        j.settings("reader"); j.click("scope_BOOK")
        j.find("font_larger", "txt_options").click(); j.endSettings("reader")
        assertEquals(origin, j.percent(), .5f)
        j.device.setOrientationLeft(); j.ready("reader")
        assertEquals(origin, j.percent(), .5f)
        j.device.setOrientationNatural(); j.ready("reader")
    }

    @Test fun S04_pdfPageInputSearchBookmarkAndActualProcessRestore(): Unit = j.step("S04") {
        j.connect(); j.open("03-book.pdf", "pdf")
        j.click("pdf_move"); j.node("pdf_page_input").text = "0"
        j.await("Page zero must be rejected") { !j.node("pdf_move_apply").isEnabled }
        j.node("pdf_page_input").text = "999999"
        j.await("Out-of-range PDF page must be rejected") { !j.node("pdf_move_apply").isEnabled }
        j.click("sheet_close")
        j.movePage("pdf", 60); j.click("pdf_bookmark")
        j.click("pdf_search"); j.node("pdf_search_input").text = "Searchable"; j.click("pdf_search_submit")
        j.await("PDF search did not finish", 60_000) { !j.exists("pdf_search_cancel") && j.node("pdf_search_next").isEnabled }
        j.find("pdf_search_more", "pdf_search_results").click()
        val result = j.text("101 페이지")
        assertTrue("The next PDF result page must start at result 101", result.visibleBounds.height() > 24)
        result.click(); j.ready("pdf"); j.await("Wrong PDF search page") { j.page("pdf") == 101 }
        j.click("pdf_search"); j.click("pdf_search_return"); j.ready("pdf")
        j.await("PDF search origin was lost") { j.page("pdf") == 60 }
        j.click("pdf_invert"); j.settledViewport("pdf")
        val before = j.settledViewport("pdf")
        j.killAndResume("pdf"); assertEquals(60, j.page("pdf")); j.assertViewportRestored("pdf", before)
        j.movePage("pdf", 2)
        j.click("pdf_bookmarks"); j.text("60 페이지").click(); j.ready("pdf")
        j.await("PDF bookmark did not restore page") { j.page("pdf") == 60 }
        j.click("pdf_bookmarks"); j.click("pdf_bookmark_delete")
        j.await("PDF bookmark deletion did not finish") { !j.exists("pdf_bookmark_delete") }
    }

    @Test fun S04_scannedAndBrokenPdfLeaveTheLibraryUsable(): Unit = j.step("S04-errors") {
        j.connect(); j.open("08-scanned.pdf", "pdf")
        j.click("pdf_search"); j.node("pdf_search_input").text = "Searchable"; j.click("pdf_search_submit")
        j.await("Scanned PDF must explain absence of text") { j.device.hasObject(By.textContains("검색할 텍스트가 없는 PDF")) }
        j.click("sheet_close"); j.close("pdf")
        j.find("folder_Errors").click()
        for (file in listOf("broken.pdf", "locked.pdf")) {
            j.find("file_$file").click(); j.node("error_close"); j.click("error_close")
        }
        j.click("folder_up"); j.open("01-short.txt", "reader")
    }

    @Test fun S04_pdfSelectionZoomRotationAndProcessRestore(): Unit = j.step("S04-gestures") {
        j.connect(); j.open("03-book.pdf", "pdf"); j.movePage("pdf", 10); j.settledViewport("pdf")
        val density = j.instrumentation.context.resources.displayMetrics.density
        val area = j.body("pdf").visibleBounds
        val scale = (area.width() - 16 * density) / 600f
        // The numbered fixture has a Helvetica line at PDF x=40, baseline y=720 on a 600x800 page.
        val x = area.left + (8 * density + 120 * scale).toInt()
        val y = j.node("pdf_back").visibleBounds.bottom + (5 * density + 72 * scale).toInt()
        j.device.swipe(x, y, x, y, 120)
        j.node("pdf_copy"); j.click("pdf_copy")
        j.await("Copy must close the selection action") { !j.exists("pdf_copy") }
        j.body("pdf").pinchOpen(.55f)
        fun zoom() = j.body("pdf").contentDescription.substringAfterLast("· ").removeSuffix("%").toInt()
        j.await("Pinch did not enlarge the PDF") { zoom() > 100 }
        j.device.setOrientationLeft(); j.ready("pdf"); assertEquals(10, j.page("pdf"))
        j.device.setOrientationNatural(); j.ready("pdf"); assertEquals(10, j.page("pdf"))
        val before = j.settledViewport("pdf"); val zoom = zoom()
        j.killAndResume("pdf"); assertEquals(10, j.page("pdf")); assertEquals(zoom, zoom())
        j.assertViewportRestored("pdf", before)
    }

    @Test fun S05_imageThumbnailsSplitCropAndActualProcessRestore(): Unit = j.step("S05") {
        j.connect(); j.open("04-comic.cbz", "image")
        j.click("image_move"); j.node("image_page_input").text = "0"
        j.await("Image page zero must be rejected") { !j.node("image_move_apply").isEnabled }
        j.node("image_page_input").text = "3"; j.click("image_move_apply"); j.ready("image")
        j.settings("image"); j.click("scope_BOOK")
        j.find("image_mode_PAGED", "image_options").click()
        j.find("image_split", "image_options").click()
        j.endSettings("image"); j.movePage("image", 3)
        j.await("Wide page should expose its left half") { j.node("image_page").text.contains("왼쪽") }
        j.swipe("image", true, horizontal = true)
        j.await("Turning a split page must show its second half") { j.node("image_page").text.contains("오른쪽") }
        assertEquals(3, j.page("image"))
        j.click("image_bookmark")
        val before = j.settledViewport("image")
        j.killAndResume("image"); assertEquals(3, j.page("image"))
        assertTrue(j.node("image_page").text.contains("오른쪽"))
        j.assertViewportRestored("image", before)
        j.movePage("image", 12)
        j.click("image_bookmarks"); j.node("image_bookmark_list").findObject(By.textContains("3 페이지"))!!.click()
        j.ready("image"); j.await("Image bookmark lost its original page") { j.page("image") == 3 }
        assertTrue(j.node("image_page").text.contains("오른쪽"))
        j.settings("image"); j.find("image_auto_crop", "image_options").click(); j.endSettings("image")
        assertEquals(3, j.page("image"))
        j.click("image_move"); j.node("image_page_input").text = "24"; j.click("image_move_apply"); j.ready("image")
        assertTrue(j.node("image_page").text.contains("왼쪽"))
        assertFalse("The first half is not the book end", j.exists("reading_end_dialog"))
    }

    @Test fun S05_archiveFormatsAndImageFolderUseActualPages(): Unit = j.step("S05-formats") {
        j.connect(); j.find("folder_Archives").click()
        for (file in listOf("mixed.cb7", "mixed.cbr", "stored-rar4.cbr")) {
            j.open(file, "image"); j.movePage("image", 3); j.settledViewport("image"); j.close("image")
        }
        j.click("folder_up"); j.find("folder_Images").click(); j.open("2.png", "image")
        assertEquals(2, j.page("image")); j.movePage("image", 10)
        j.click("image_move"); j.find("image_thumbnail_0", "image_thumbnails", Direction.UP).click()
        j.ready("image"); j.await("Thumbnail did not navigate to the first image") { j.page("image") == 1 }
    }

    @Test fun S06_epubSearchPagesStartAtTheirFirstResultAndReturnToOrigin(): Unit = j.step("S06-search-pages") {
        j.connect(); j.open("05-reflow.epub", "epub"); j.click("epub_next")
        j.await("EPUB origin") { j.node("epub_current_chapter").text.startsWith("2장") }
        j.click("epub_search"); j.node("epub_search_input").text = "아침"; j.click("epub_search_submit")
        j.node("epub_search_next", 60_000)
        j.find("epub_result_30", "epub_search_results")
        j.click("epub_search_next"); j.node("epub_search_previous")
        assertTrue("Next EPUB results must start at row zero", j.node("epub_result_0").visibleBounds.height() > 24)
        j.find("epub_result_30", "epub_search_results")
        j.click("epub_search_previous")
        assertTrue("Previous EPUB results must start at row zero", j.node("epub_result_0").visibleBounds.height() > 24)
        j.click("epub_result_0"); j.ready("epub")
        j.click("epub_search"); j.click("epub_search_return"); j.ready("epub")
        j.await("EPUB search origin was lost") { j.node("epub_current_chapter").text.startsWith("2장") }
    }

    @Test fun S08_readingEndMovesAcrossFormatsOnceAndNeverWraps(): Unit = j.step("S08") {
        j.connect(); j.find("folder_Next").click(); j.open("01.txt", "reader")
        j.swipe("reader", true)
        j.node("reading_end_next"); j.click("reading_end_next"); j.ready("pdf")
        if (!j.exists("reading_end_dialog")) j.swipe("pdf", true)
        j.node("reading_end_next"); j.click("reading_end_next"); j.ready("image")
        if (!j.exists("reading_end_dialog")) j.swipe("image", true)
        j.node("reading_end_next"); j.click("reading_end_next"); j.ready("epub")
        j.click("epub_next")
        j.await("Fixed EPUB did not reach its second page") { j.node("epub_progress").text.startsWith("2 / 3") }
        j.click("epub_next")
        j.node("reading_end_exit")
        assertFalse(j.exists("reading_end_next"))
        j.click("reading_end_exit"); j.node("folder_refresh")
    }

    @Test fun S09_scrollingQuickSettingsWhileOpeningDoesNotDismissIt(): Unit = j.step("S09-opening-scroll") {
        j.connect()
        for ((kind, file) in listOf("image" to "04-comic.cbz", "reader" to "01-short.txt", "pdf" to "03-book.pdf")) {
            j.open(file, kind)
            repeat(3) {
                // Deliberately start scrolling as soon as accessibility exposes the sheet,
                // before its show animation has reached the Expanded state.
                j.node(if (kind == "reader") "reader_txt" else "${kind}_settings").click()
                val area = j.node("quick_options").visibleBounds
                j.device.swipe(area.centerX(), area.top + area.height() * 4 / 5,
                    area.centerX(), area.top + area.height() / 5, 50)
                android.os.SystemClock.sleep(1500)
                assertTrue("Opening $kind settings must survive an upward content fling", j.exists("quick_options"))
                j.click("sheet_close")
                j.await("Quick settings did not close") { !j.exists("quick_options") }
            }
            j.close(kind)
        }
    }
}
