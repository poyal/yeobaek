package com.poyal.yeobaek.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Only synthetic fixture files are changed, with a backup restored in finally. */
@RunWith(AndroidJUnit4::class)
class RecoveryJourneyTest {
    private val args = InstrumentationRegistry.getArguments()
    private val pkg = args.getString("targetPackage") ?: "com.poyal.yeobaek.debug"
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val out = File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "captures/RECOVERY").apply { mkdirs() }
    @get:Rule val failures = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description) { capture("FAIL-${description.methodName}") }
    }
    private fun node(tag: String, timeout: Long = 30000) = device.wait(Until.findObject(By.res(tag)), timeout) ?: throw AssertionError("요소 없음: $tag")
    private fun click(tag: String) { node(tag).click(); device.waitForIdle(); if (tag in setOf("reader_txt", "pdf_settings", "image_settings")) { node("quick_details").click(); device.waitForIdle() } }
    private fun capture(id: String) { device.waitForIdle(); SystemClock.sleep(1200); check(device.takeScreenshot(File(out, "$id.png"))); device.dumpWindowHierarchy(File(out, "$id.xml")) }
    private fun find(tag: String, list: String = "library_list") { repeat(30) { if (device.hasObject(By.res(tag))) return; node(list).scroll(Direction.DOWN, .7f) }; throw AssertionError(tag) }
    private fun start() { device.executeShellCommand("am start -W -n $pkg/com.poyal.yeobaek.MainActivity"); device.waitForIdle() }
    private fun ready() { node("reader_footer", 90000); check(device.wait(Until.gone(By.res("layout_loading")), 90000)); SystemClock.sleep(700) }
    private fun percent() = node("reader_percent").text.removeSuffix("%").toFloat()
    @Before fun setup() {
        require(pkg in setOf("com.poyal.yeobaek", "com.poyal.yeobaek.debug", "com.poyal.yeobaek.benchmark"))
        device.executeShellCommand("pm clear $pkg"); start(); click("connect_folder")
        device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.documentsui"))), 10000)
        device.findObject(By.desc("Show roots"))?.click()
        device.findObject(By.text("Downloads"))?.click(); device.findObject(By.text("Download"))?.click()
        device.wait(Until.findObject(By.text("Yeobaek")), 10000)!!.click()
        (device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.documentsui:id/action_menu_select"))), 1000) ?: device.findObject(By.res("android:id/button1")))!!.click()
        device.wait(Until.findObject(By.res("android:id/button1")), 5000)?.click(); node("folder_refresh")
    }
    @Test fun cacheSourceChangesAndRecordDeletion() {
        val source = "/sdcard/Download/Yeobaek/Recovery/Original.txt"
        val backup = "/sdcard/Download/Yeobaek/Recovery/Original.qa-backup"
        val hash = device.executeShellCommand("sha256sum $source").substringBefore(' ')
        Assert.assertTrue("합성 원본 SHA-256 확인", hash.matches(Regex("[0-9a-f]{64}")))
        device.executeShellCommand("cp $source $backup")
        try {
            click("folder_Recovery"); find("file_Original.txt"); click("file_Original.txt"); ready()
            click("reader_move"); val slider = node("move_slider").visibleBounds
            device.click(slider.centerX(), slider.centerY()); click("move_apply"); ready()
            val before = percent(); click("reader_bookmark"); click("reader_back")
            click("nav_settings"); click("settings_general"); find("clear_cache", "general_settings_options"); click("clear_cache"); click("page_back")
            click("nav_recent"); click("recent_Original.txt"); ready()
            Assert.assertEquals(before, percent(), 1f); capture("39-cache-rebuilt")
            click("reader_bookmarks"); node("bookmark_delete"); click("sheet_close"); click("reader_back")
            Assert.assertEquals("합성 원본 이동", "", device.executeShellCommand("mv $source $source.missing").trim())
            try {
                click("nav_recent"); click("recent_Original.txt"); node("find_missing_book"); capture("40-missing-source"); click("error_close")
            } finally { device.executeShellCommand("mv $source.missing $source") }
            device.executeShellCommand("cp /sdcard/Download/Yeobaek/Recovery/Replacement.txt $source")
            click("recent_Original.txt"); node("change_accept"); capture("41-changed-source"); click("change_accept"); ready()
            click("reader_bookmarks"); node("bookmark_delete"); capture("42-old-bookmark-preserved"); click("sheet_close"); click("reader_back")
            click("nav_recent"); click("manage_Original.txt")
            // DropdownMenu uses a separate popup root, so select its visible action label.
            checkNotNull(device.wait(Until.findObject(By.text("독서 기록 관리")), 15000)).click()
            device.waitForIdle(); click("forget_confirm")
            Assert.assertFalse(device.hasObject(By.res("recent_Original.txt"))); capture("43-record-deleted")
        } finally {
            device.executeShellCommand("cp $backup $source"); device.executeShellCommand("rm $backup")
            Assert.assertEquals("원본 fixture 복원", hash, device.executeShellCommand("sha256sum $source").substringBefore(' '))
        }
    }
    @Test fun largeSystemFontKeyboardAndThousandFiles() {
        click("folder_천개 폴더"); node("file_책 0000.txt"); node("library_list").scroll(Direction.DOWN, .9f); capture("44-thousand-files")
        repeat(5) { if (!device.hasObject(By.res("folder_up"))) node("library_list").scroll(Direction.UP, .9f) }
        click("folder_up")
        find("file_비가 그친 오후.txt"); click("file_비가 그친 오후.txt"); ready()
        val originalFontScale = device.executeShellCommand("settings get system font_scale").trim()
        val originalShowIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            device.executeShellCommand("settings put system font_scale 2.0"); SystemClock.sleep(1500)
            ready(); capture("45-system-font-200"); click("reader_txt"); capture("46-large-txt-options"); click("page_back")
            click("reader_search"); node("search_input").click(); node("search_input").text = "골목"
            node("search_input").click()
            check(device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.inputmethod\\.latin"))), 15000)) { "검색 키보드가 표시되지 않았습니다" }
            capture("47-keyboard-search"); device.pressBack()
            node("search_submit"); click("search_submit"); node("search_next"); capture("48-large-search-results")
        } finally {
            if (originalFontScale == "null") device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $originalFontScale")
            if (originalShowIme == "null") device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            else device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $originalShowIme")
        }
    }

    @Test fun returningToFolderRefreshesExternalChanges() {
        click("folder_Recovery")
        val name = "Resume-${java.util.UUID.randomUUID()}.txt"
        val source = "/sdcard/Download/Yeobaek/Recovery/$name"
        try {
            device.pressHome()
            val original = "/sdcard/Download/Yeobaek/Recovery/Original.txt"
            val hash = device.executeShellCommand("sha256sum $original").substringBefore(' ')
            Assert.assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
            device.executeShellCommand("cp $original $source")
            Assert.assertEquals("합성 파일 생성 확인", hash, device.executeShellCommand("sha256sum $source").substringBefore(' '))
            start()
            node("file_$name")
            capture("51-folder-refreshed-on-resume")
        } finally { device.executeShellCommand("rm $source") }
    }

    @Test fun movedFileReconnectsWithoutLosingPositionOrBookmark() {
        val source = "/sdcard/Download/Yeobaek/Recovery/Original.txt"
        val moved = "/sdcard/Download/Yeobaek/Recovery/Moved.txt"
        val hash = device.executeShellCommand("sha256sum $source").substringBefore(' ')
        Assert.assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        click("folder_Recovery"); find("file_Original.txt"); click("file_Original.txt"); ready()
        click("reader_move"); val slider = node("move_slider").visibleBounds
        device.click(slider.centerX(), slider.centerY()); click("move_apply"); ready()
        val before = percent(); click("reader_bookmark"); click("reader_back")
        Assert.assertEquals("", device.executeShellCommand("mv $source $moved").trim())
        try {
            click("nav_recent"); click("recent_Original.txt"); click("find_missing_book")
            device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.documentsui"))), 10000)
            device.findObject(By.desc("Show roots"))?.click()
            device.waitForIdle()
            // Downloads in ACTION_OPEN_DOCUMENT can be the virtual download-manager root.
            // Use this dedicated emulator's physical storage root, then its Download directory.
            SystemClock.sleep(500)
            fun tapDocument(label: String) {
                check(device.findObject(UiSelector().textContains(label)).waitForExists(10000)) { "문서 선택기 항목 없음: $label" }
                // Read a fresh hierarchy: DocumentsUI can invalidate cached accessibility nodes
                // while rebuilding its drawer. Use the same displayed bounds as a manual tap.
                val hierarchy = File(out, "picker-step.xml")
                device.dumpWindowHierarchy(hierarchy)
                val parser = android.util.Xml.newPullParser()
                var bounds: List<Int>? = null
                hierarchy.reader().use { input ->
                    parser.setInput(input)
                    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node") {
                            val text = parser.getAttributeValue(null, "text").orEmpty()
                            if (text == label || (label == android.os.Build.MODEL && text.contains(label))) {
                                bounds = Regex("\\d+").findAll(parser.getAttributeValue(null, "bounds")).map { it.value.toInt() }.toList()
                            }
                        }
                        parser.next()
                    }
                }
                val b = checkNotNull(bounds) { "화면에서 항목을 찾지 못했습니다: $label" }
                check(device.click((b[0] + b[2]) / 2, (b[1] + b[3]) / 2))
                device.waitForIdle(); SystemClock.sleep(400)
            }
            tapDocument(android.os.Build.MODEL)
            tapDocument("Download")
            tapDocument("Yeobaek")
            tapDocument("Recovery")
            check(device.findObject(UiSelector().text("Moved.txt")).waitForExists(10000))
            capture("49-reconnect-picker"); tapDocument("Moved.txt"); ready()
            Assert.assertEquals("읽기 위치 보존", before, percent(), 1f)
            click("reader_bookmarks"); node("bookmark_delete"); capture("50-reconnected-bookmark"); click("sheet_close")
            click("reader_back"); click("nav_recent"); node("recent_Moved.txt")
            Assert.assertFalse("기존 책을 중복 등록하지 않음", device.hasObject(By.res("recent_Original.txt")))
        } finally {
            device.executeShellCommand("mv $moved $source")
            Assert.assertEquals(hash, device.executeShellCommand("sha256sum $source").substringBefore(' '))
        }
    }
}
