package com.poyal.yeobaek.e2e

import com.poyal.yeobaek.testing.JourneyDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class RecoveryFormatsJourneyTest {
    private val j = JourneyDevice()
    @get:Rule val failures = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) { j.capture("FAIL-${description.methodName}") }
    }
    @Before fun setup() { j.reset(); j.connect() }
    @Test fun S07_decliningResumeConsumesOnlyThePromptAcrossAllFormats() {
        for ((kind, file) in listOf("reader" to "01-short.txt", "pdf" to "03-book.pdf", "image" to "04-comic.cbz", "epub" to "05-reflow.epub")) {
            j.open(file, kind)
            when (kind) {
                "reader" -> { j.moveText(.6f); j.await("TXT seek") { j.percent() in 55f..65f } }
                "pdf", "image" -> j.movePage(kind, 8)
                else -> { j.click("epub_next"); j.await("EPUB second chapter") { j.node("epub_current_chapter").text.startsWith("2장") } }
            }
            j.click("${kind}_bookmark"); j.killAndResume(kind, accept = false)
            j.shell("am force-stop ${j.pkg}"); j.launch(); assertFalse(j.exists("resume_accept"))
            j.open(file, kind)
            when (kind) {
                "reader" -> assertTrue(j.percent() in 55f..65f)
                "pdf", "image" -> assertEquals(8, j.page(kind))
                else -> assertTrue(j.node("epub_current_chapter").text.startsWith("2장"))
            }
            j.close(kind); j.shell("am force-stop ${j.pkg}"); j.launch()
            assertFalse("Explicit close must not resurrect a prompt for $kind", j.exists("resume_accept"))
        }
    }
    @Test fun S10_changedPdfCancelAndFirstPageKeepOldBookmark() {
        j.open("03-book.pdf", "pdf"); j.movePage("pdf", 40); j.click("pdf_bookmark"); j.close("pdf")
        val source = "/sdcard/Download/YeobaekQA/03-book.pdf"
        j.shell("cp $source $source.qa-original")
        try {
            j.shell("cp /sdcard/Download/YeobaekQA/Next/02.pdf $source")
            j.find("file_03-book.pdf").click(); j.text("PDF가 변경되었어요"); j.text("취소").click()
            assertFalse(j.exists("pdf_footer"))
            j.find("file_03-book.pdf").click(); j.text("PDF가 변경되었어요"); j.text("처음부터").click(); j.ready("pdf")
            if (j.exists("reading_end_dialog")) j.device.pressBack()
            assertEquals(1, j.page("pdf")); j.click("pdf_bookmarks"); j.node("pdf_bookmark_delete")
            assertTrue(j.device.hasObject(androidx.test.uiautomator.By.textContains("변경 전")))
        } finally { j.shell("cp $source.qa-original $source"); j.shell("rm $source.qa-original") }
    }
    @Test fun S10_imageInsertionAndDeletionPreserveTheOriginalPageKey() {
        j.find("folder_Images").click(); j.open("2.png", "image"); j.movePage("image", 10)
        j.click("image_bookmark"); j.close("image")
        val folder = "/sdcard/Download/YeobaekQA/Images"
        j.shell("mv $folder/1.png $folder/1.png.qa-original")
        try {
            j.click("nav_recent"); j.find("recent_Images", "recent_list").click(); j.ready("image")
            j.await("Deleted preceding page must preserve the named image") { j.page("image") == 9 }
            j.close("image"); j.shell("cp $folder/24.png $folder/0.png")
            j.click("nav_recent"); j.find("recent_Images", "recent_list").click(); j.ready("image")
            j.await("Inserted preceding page must preserve the named image") { j.page("image") == 10 }
            j.click("image_bookmarks"); j.node("image_bookmark_delete")
        } finally { j.shell("rm -f $folder/0.png"); j.shell("mv $folder/1.png.qa-original $folder/1.png") }
    }
}
