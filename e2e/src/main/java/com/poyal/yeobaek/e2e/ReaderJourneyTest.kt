package com.poyal.yeobaek.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/** Out-of-process journeys: the reader process can die while this runner continues. */
@RunWith(AndroidJUnit4::class)
class ReaderJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = args.getString("targetPackage") ?: "com.poyal.yeobaek.debug"
    private val mode = args.getString("theme") ?: "LIGHT"
    private val folder = args.getString("fixtureFolder") ?: "Yeobaek"
    private val out = File(instrumentation.context.getExternalFilesDir(null), "captures/$mode").apply { mkdirs() }
    @get:Rule val failures = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) { capture("FAIL-${description.methodName}") }
    }
    @Before fun setup() {
        require(pkg in setOf("com.poyal.yeobaek.debug", "com.poyal.yeobaek.benchmark", "com.poyal.yeobaek"))
        device.executeShellCommand("am force-stop $pkg")
        // This test is run only on the dedicated yeobaek AVD, enforced by the host script.
        device.executeShellCommand("pm clear $pkg")
        launch()
    }
    private fun launch() { device.executeShellCommand("am start -W -n $pkg/com.poyal.yeobaek.MainActivity"); device.waitForIdle() }
    private fun node(tag: String, timeout: Long = 15000) = device.wait(Until.findObject(By.res(tag)), timeout)
        ?: throw AssertionError("요소가 없습니다: $tag")
    private fun click(tag: String) { node(tag).click(); device.waitForIdle() }
    private fun text(text: String, timeout: Long = 15000) = device.wait(Until.findObject(By.text(text)), timeout)
        ?: throw AssertionError("문구가 없습니다: $text")
    private fun capture(id: String) {
        if (!id.startsWith("FAIL")) check(device.wait(Until.gone(By.res("layout_loading")), 60000))
        device.waitForIdle(); SystemClock.sleep(1200)
        check(device.takeScreenshot(File(out, "$id.png")))
        device.dumpWindowHierarchy(File(out, "$id.xml"))
        File(out, "manifest.tsv").appendText("$id\t$pkg\t${android.os.Build.VERSION.SDK_INT}\t$mode\n")
    }
    private fun scrollTo(tag: String, container: String = "library_list") {
        repeat(30) {
            if (device.hasObject(By.res(tag))) return
            node(container).scroll(Direction.DOWN, .7f); device.waitForIdle()
        }
        throw AssertionError("스크롤 후에도 요소 없음: $tag")
    }
    private fun chooseTheme() {
        click("nav_settings"); click("settings_general"); click("theme_$mode"); click("page_back"); click("nav_folder")
    }
    private fun connect() {
        click("connect_folder")
        device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.documentsui"))), 15000)
        capture("02-system-folder-picker")
        val roots = device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("표시 위치"))
        roots?.click()
        val downloads = device.wait(Until.findObject(By.text("Downloads")), 3000) ?: device.findObject(By.text("다운로드"))
        downloads?.click()
        device.findObject(By.text("Download"))?.click()
        text(folder).click()
        val select = device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.documentsui:id/action_menu_select"))), 5000)
            ?: device.findObject(By.text("USE THIS FOLDER")) ?: device.findObject(By.text("이 폴더 사용"))
            ?: throw AssertionError("폴더 선택 버튼 없음")
        select.click()
        val allow = device.wait(Until.findObject(By.res("android:id/button1")), 5000)
        allow?.click()
        node("folder_refresh", 30000)
    }
    private fun openStory() {
        scrollTo("file_비가 그친 오후.txt")
        click("file_비가 그친 오후.txt")
        node("reader_footer", 60000)
        check(device.wait(Until.gone(By.res("layout_loading")), 60000))
        SystemClock.sleep(1000)
    }

    @Test fun completeReadingJourneyAndScreens() {
        chooseTheme(); capture("01-first-folder")
        connect(); capture("03-library")
        click("folder_빈 폴더"); capture("04-empty-folder"); click("folder_up")
        openStory(); capture("05-reading")
        val before = node("reader_percent").text
        val body = node("reader_body").visibleBounds
        device.click(body.centerX(), body.centerY())
        check(device.wait(Until.gone(By.res("reader_header")), 3000)); capture("06-reading-hidden")
        device.click(body.centerX(), body.centerY()); node("reader_header")
        Assert.assertEquals("메뉴 토글이 위치를 바꿨습니다", before, node("reader_percent").text)
        node("reader_body").scroll(Direction.DOWN, .75f); node("reader_header")
        capture("07-reading-scrolled")
        click("reader_move"); capture("08-move")
        val slider = node("move_slider").visibleBounds
        device.click(slider.left + slider.width() * 3 / 4, slider.centerY()); capture("09-move-preview")
        click("move_cancel"); val old = node("reader_percent").text
        click("reader_move"); val slider2 = node("move_slider").visibleBounds
        device.click(slider2.left + slider2.width() * 3 / 4, slider2.centerY()); click("move_apply")
        check(device.wait(Until.gone(By.res("layout_loading")), 60000)); SystemClock.sleep(500)
        Assert.assertNotEquals(old, node("reader_percent").text); capture("10-move-applied")
        click("reader_bookmarks"); capture("11-bookmarks-empty"); click("sheet_close")
        click("reader_bookmark"); SystemClock.sleep(500); click("reader_bookmarks"); node("bookmark_delete"); capture("12-bookmarks-added"); click("sheet_close")
        click("reader_txt"); capture("13-txt-settings")
        click("font_larger"); Assert.assertEquals("19", node("font_size").text)
        val options = node("txt_options"); options.scroll(Direction.DOWN, .5f)
        click("paper_SEPIA"); capture("14-txt-paper"); click("page_back"); capture("15-sepia-reader")
        click("reader_txt"); node("txt_options").scroll(Direction.DOWN, .7f); click("txt_reset"); click("page_back")
        click("reader_search"); capture("16-search-empty")
        node("search_input").text = "골목"
        click("search_submit")
        check(device.wait(Until.hasObject(By.res("search_next")), 30000)); capture("17-search-results")
        click("search_next"); check(device.wait(Until.gone(By.res("layout_loading")), 30000)); capture("18-search-reading")
        click("reader_search"); node("search_input").text = "없는문장xyz123"; click("search_submit")
        text("검색 결과가 없습니다.", 30000); capture("19-search-no-results"); click("search_return")
        click("reader_info"); capture("20-book-info"); click("info_encoding"); capture("21-encoding-preview"); click("sheet_close")
        // Save enough time for both the idle and periodic durable writes before killing the process.
        SystemClock.sleep(1500)
        val pid = device.executeShellCommand("pidof $pkg").trim()
        device.pressHome(); SystemClock.sleep(1000); device.executeShellCommand("am kill $pkg"); SystemClock.sleep(1000)
        var termination = "am kill"
        if (device.executeShellCommand("pidof $pkg").trim() == pid) {
            // Some OS versions retain the process despite am kill. Enforce a new process explicitly.
            device.executeShellCommand("am force-stop $pkg"); termination = "am force-stop (am kill retained process)"
        }
        File(out, "process-death.txt").writeText("before=$pid\ntermination=$termination\n")
        Assert.assertTrue("종료 후 프로세스가 남아 있습니다", device.executeShellCommand("pidof $pkg").trim().isEmpty())
        launch(); node("resume_accept"); capture("22-resume")
        val newPid = device.executeShellCommand("pidof $pkg").trim()
        File(out, "process-death.txt").appendText("after=$newPid\n")
        Assert.assertTrue("새 프로세스에서 검사해야 합니다", pid.isNotEmpty() && newPid.isNotEmpty() && pid != newPid)
        click("resume_accept"); node("reader_footer", 60000); capture("23-resumed-reader")
        val restoredPercent = node("reader_percent").text.removeSuffix("%").toFloat()
        device.setOrientationLeft()
        check(device.wait(Until.gone(By.res("layout_loading")), 30000)); SystemClock.sleep(1500)
        Assert.assertEquals("회전 후에도 문장 위치가 유지되어야 합니다", restoredPercent, node("reader_percent").text.removeSuffix("%").toFloat(), 1f)
        capture("38-landscape-reader")
        device.setOrientationNatural(); device.unfreezeRotation(); SystemClock.sleep(1000)
        click("reader_back"); click("nav_recent"); capture("24-recent")
        click("manage_비가 그친 오후.txt"); capture("25-delete-confirm"); click("sheet_close")
        click("nav_settings"); capture("26-app-settings")
        click("settings_general"); scrollTo("disconnect", "general_settings_options"); capture("27-storage"); click("disconnect"); capture("28-disconnect-confirm"); click("disconnect_cancel"); click("page_back")
        click("nav_folder"); capture("29-library-continue")
        device.executeShellCommand("am force-stop $pkg"); launch(); node("folder_refresh")
        Assert.assertFalse("명시적으로 나왔을 때는 확인하지 않아야 합니다", device.hasObject(By.res("resume_accept")))
        capture("30-start-folder")
    }

    @Test fun sourceFailureEmptyEncodingAndSettingIsolation() {
        chooseTheme(); connect(); click("folder_메모"); capture("31-subfolder")
        click("file_빈 파일.txt"); node("reader_footer", 30000); text("빈 문서입니다."); capture("32-empty-text"); click("reader_back")
        scrollTo("file_UTF16BE.txt"); click("file_UTF16BE.txt"); node("reader_footer", 30000); capture("33-utf16"); click("reader_back")
        scrollTo("file_손상 UTF8.txt"); click("file_손상 UTF8.txt"); node("error_encoding", 30000); capture("34-invalid-text")
        click("error_encoding"); click("encoding_UTF-8"); capture("35-manual-encoding"); click("encoding_apply"); node("error_close", 30000); click("error_close")
        click("folder_up"); click("folder_완결"); click("file_한글 CP949.txt"); node("reader_footer", 30000); capture("36-cp949")
        click("reader_info"); Assert.assertTrue(device.wait(Until.hasObject(By.textContains("x-windows-949")), 3000)); capture("37-cp949-info")
    }
}
