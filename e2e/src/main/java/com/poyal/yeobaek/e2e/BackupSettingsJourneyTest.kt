package com.poyal.yeobaek.e2e

import androidx.test.uiautomator.*
import com.poyal.yeobaek.testing.JourneyDevice
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.regex.Pattern

class BackupSettingsJourneyTest {
    private val j = JourneyDevice()
    private val exported = mutableListOf<String>()
    @get:Rule val failures = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) { j.capture("FAIL-${description.methodName}") }
    }
    @Before fun setup() { j.reset() }
    @After fun cleanupExports() {
        exported.forEach { j.shell("rm -f /sdcard/Download/YeobaekQA/$it") }
    }
    private fun fontSize() = j.find("font_size", "txt_options").text.toInt()
    @Test fun S09_commonFolderBookInheritanceAndFormatReset(): Unit = j.step("S09") {
        j.connect(); j.open("01-short.txt", "reader"); j.moveText(.4f)
        j.settings("reader"); j.click("scope_COMMON")
        val common = fontSize()
        j.click("font_larger"); j.await("Common size change") { fontSize() == common + 1 }
        j.click("scope_FOLDER"); j.click("font_larger")
        j.await("Folder size change") { fontSize() == common + 2 }
        j.click("scope_BOOK"); j.click("font_larger")
        j.await("Book size change") { fontSize() == common + 3 }
        j.endSettings("reader"); j.close("reader")
        j.open("02-story.txt", "reader"); j.settings("reader"); j.click("scope_BOOK")
        assertEquals("Sibling book inherits folder size", common + 2, fontSize())
        j.endSettings("reader"); j.close("reader")
        j.open("01-short.txt", "reader"); j.settings("reader"); j.click("scope_BOOK")
        assertEquals(common + 3, fontSize())
        j.find("inherit_size", "txt_options").click()
        j.find("font_size", "txt_options", Direction.UP)
        j.await("Inheriting size must remove the book override") { fontSize() == common + 2 }
        j.endSettings("reader"); j.close("reader")
        j.open("03-book.pdf", "pdf"); j.click("pdf_invert"); j.settings("pdf")
        j.find("pdf_reset", "pdf_options").click(); j.endSettings("pdf"); j.close("pdf")
        j.open("01-short.txt", "reader"); j.settings("reader"); j.click("scope_BOOK")
        assertEquals("PDF reset must preserve TXT inheritance", common + 2, fontSize())
    }
    private fun general() { j.click("nav_settings"); j.click("settings_general") }
    private fun selectDocument(name: String) {
        for (attempt in 0 until 35) {
            val list = j.device.wait(Until.findObject(By.res(Pattern.compile(".*:id/dir_list"))), 10_000) ?: error("No document list")
            val item = list.findObject(By.text(name)); val bounds = item?.visibleBounds; val area = list.visibleBounds
            if (bounds != null && bounds.height() >= 24 && bounds.bottom <= area.bottom - 24) { item.click(); return }
            list.scroll(Direction.DOWN, .5f); j.device.waitForIdle()
        }
        error("Missing backup document: $name")
    }
    private fun export(name: String): JSONObject {
        require(name.matches(Regex("(?:four-format|merged)-[0-9]+\\.json")))
        exported += name
        j.find("backup_export", "general_settings_options").click(); j.pickerFolder()
        j.device.findObject(By.clazz("android.widget.EditText"))!!.text = name
        (j.device.findObject(By.res(Pattern.compile(".*:id/action_menu_save"))) ?: j.node("android:id/button1")).click()
        j.await("Backup export did not complete") { j.node("backup_status").text.contains("저장했습니다") }
        return JSONObject(j.shell("cat /sdcard/Download/YeobaekQA/$name"))
    }
    private fun import(name: String, cancel: Boolean = false) {
        j.find("backup_import", "general_settings_options").click(); j.pickerFolder(); selectDocument(name)
        j.node("backup_restore_confirm")
        if (cancel) j.device.pressBack() else {
            j.click("backup_restore_confirm")
            j.await("Backup restore did not complete") { j.node("backup_status").text.contains("복원했습니다") }
        }
    }
    @Test fun S11_fourFormatFreshRestoreAndDuplicateImportKeepCurrentPosition(): Unit = j.step("S11") {
        j.connect(); j.open("01-short.txt", "reader"); j.moveText(.6f)
        j.await("TXT seek before backup") { j.percent() in 55f..65f }
        val txt = j.percent(); j.click("reader_bookmark"); j.close("reader")
        j.open("03-book.pdf", "pdf"); j.movePage("pdf", 40); j.click("pdf_bookmark"); j.close("pdf")
        j.open("04-comic.cbz", "image"); j.movePage("image", 9); j.click("image_bookmark"); j.close("image")
        j.open("05-reflow.epub", "epub"); j.click("epub_next")
        j.await("EPUB chapter before backup") { j.node("epub_current_chapter").text.startsWith("2장") }
        j.click("epub_bookmark"); j.close("epub")
        general(); val name = "four-format-${System.currentTimeMillis()}.json"; val original = export(name)
        for (key in listOf("bookmarks", "pdfBookmarks", "imageBookmarks", "epubBookmarks"))
            assertEquals(key, 1, original.getJSONObject("library").getJSONArray(key).length())
        File(j.out, "four-format-backup.json").writeText(original.toString(2))
        j.reset(); general(); import(name, cancel = true)
        j.click("page_back"); j.click("nav_recent")
        assertFalse(j.exists("recent_01-short.txt"))
        general(); import(name)
        j.click("page_back"); j.click("nav_folder"); j.connect()
        j.open("01-short.txt", "reader"); assertEquals(txt, j.percent(), .2f)
        j.click("reader_bookmarks"); j.node("bookmark_delete"); j.click("sheet_close"); j.close("reader")
        j.open("03-book.pdf", "pdf"); assertEquals(40, j.page("pdf"))
        j.movePage("pdf", 60); j.close("pdf")
        j.open("04-comic.cbz", "image"); assertEquals(9, j.page("image")); j.close("image")
        j.open("05-reflow.epub", "epub"); assertTrue(j.node("epub_current_chapter").text.startsWith("2장")); j.close("epub")
        general(); import(name); val merged = export("merged-${System.currentTimeMillis()}.json")
        val library = merged.getJSONObject("library")
        assertEquals(4, library.getJSONArray("books").length())
        for (key in listOf("bookmarks", "pdfBookmarks", "imageBookmarks", "epubBookmarks")) assertEquals(key, 1, library.getJSONArray(key).length())
        assertEquals("A newer PDF position must win", 59, library.getJSONArray("pdfPositions").getJSONObject(0).getInt("page"))
        j.click("page_back"); j.click("nav_folder"); j.open("03-book.pdf", "pdf"); assertEquals(60, j.page("pdf"))
    }
}
