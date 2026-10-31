package com.poyal.yeobaek.e2e

import com.poyal.yeobaek.testing.JourneyDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Host runs prepare on the published APK, then verify after over-install and again after reboot. */
class UpgradeJourneyTest {
    private val j = JourneyDevice()
    private val state = File(j.instrumentation.context.getExternalFilesDir(null), "upgrade-state.json")
    @Test fun prepareFourFormatRecords() {
        j.reset(); j.connect()
        j.open("03-book.pdf", "pdf"); j.movePage("pdf", 40); j.click("pdf_bookmark"); j.close("pdf")
        j.open("04-comic.cbz", "image"); j.movePage("image", 9); j.click("image_bookmark"); j.close("image")
        j.open("05-reflow.epub", "epub"); j.click("epub_next")
        j.await("EPUB chapter") { j.node("epub_current_chapter").text.startsWith("2장") }
        j.click("epub_bookmark"); j.close("epub")
        j.open("01-short.txt", "reader"); j.moveText(.6f)
        j.await("TXT position") { j.percent() in 55f..65f }
        j.click("reader_bookmark"); j.settings("reader"); j.click("scope_BOOK")
        j.find("font_larger", "txt_options").click(); j.endSettings("reader")
        state.writeText(JSONObject().put("percent", j.percent()).put("theme", j.theme)
            .put("pid", j.shell("pidof ${j.pkg}")).toString())
        j.device.pressHome(); android.os.SystemClock.sleep(1500)
    }
    @Test fun verifyFourFormatRecordsAndLeaveResumeCandidate() {
        val before = JSONObject(state.readText())
        j.launch(); j.node("resume_accept")
        assertNotEquals(before.getString("pid"), j.shell("pidof ${j.pkg}"))
        j.click("resume_accept"); j.ready("reader")
        assertEquals(before.getDouble("percent"), j.percent().toDouble(), .2)
        j.click("reader_bookmarks"); j.node("bookmark_delete"); j.click("sheet_close")
        j.settings("reader"); j.click("scope_BOOK"); assertEquals("19", j.find("font_size", "txt_options").text)
        j.endSettings("reader"); j.close("reader")
        j.open("03-book.pdf", "pdf"); assertEquals(40, j.page("pdf"))
        j.click("pdf_bookmarks"); j.node("pdf_bookmark_delete"); j.click("sheet_close"); j.close("pdf")
        j.open("04-comic.cbz", "image"); assertEquals(9, j.page("image"))
        j.click("image_bookmarks"); j.node("image_bookmark_delete"); j.click("sheet_close"); j.close("image")
        j.open("05-reflow.epub", "epub"); assertTrue(j.node("epub_current_chapter").text.startsWith("2장"))
        j.click("epub_bookmarks"); j.node("epub_mark_0"); j.click("sheet_close"); j.close("epub")
        j.click("nav_settings"); j.click("settings_general")
        assertTrue(j.node("theme_${before.getString("theme")}").isChecked)
        j.click("page_back"); j.click("nav_folder"); j.open("01-short.txt", "reader")
        before.put("pid", j.shell("pidof ${j.pkg}")); state.writeText(before.toString())
        j.device.pressHome(); android.os.SystemClock.sleep(1500)
    }
}
