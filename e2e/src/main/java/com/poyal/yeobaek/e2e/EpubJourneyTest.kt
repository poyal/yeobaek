package com.poyal.yeobaek.e2e

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Runs outside the release app, including real SAF grants and process replacement. */
class EpubJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val args = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = args.getString("targetPackage") ?: "com.poyal.yeobaek.debug"
    private val theme = args.getString("theme") ?: "LIGHT"
    private val out = File(context.getExternalFilesDir(null), "captures/EPUB-$theme").apply { mkdirs() }
    @get:Rule val failures = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) { shot("FAIL-${description.methodName}") }
    }
    private fun node(tag: String, timeout: Long = 20_000): UiObject2 = device.wait(Until.findObject(By.res(tag)), timeout) ?: throw AssertionError("Missing $tag")
    private fun click(tag: String) { node(tag).click(); device.waitForIdle() }
    private fun body() = node("$pkg:id/epub_host_container")
    private fun readingArea() = body().visibleBounds.apply {
        device.findObject(By.res("epub_header"))?.visibleBounds?.let { top = maxOf(top, it.bottom) }
        for (tag in listOf("epub_footer", "epub_previous")) {
            device.findObject(By.res(tag))?.visibleBounds?.let { bottom = minOf(bottom, it.top) }
        }
    }
    private fun clickOptional(selector: BySelector): Boolean {
        repeat(8) {
            try {
                val target = device.wait(Until.findObject(selector), 10_000) ?: return false
                target.click(); device.waitForIdle(); return true
            } catch (_: StaleObjectException) { SystemClock.sleep(250) }
        }
        throw AssertionError("Unstable system picker: $selector")
    }
    private fun launch() { device.executeShellCommand("am start -W -n $pkg/com.poyal.yeobaek.MainActivity"); device.waitForIdle() }
    private fun ready() { node("epub_footer", 45_000); Assert.assertTrue(device.wait(Until.gone(By.res("epub_loading")), 45_000)); SystemClock.sleep(500) }
    private fun shot(name: String) {
        device.waitForIdle(); check(device.takeScreenshot(File(out, "$name.png")))
        device.dumpWindowHierarchy(File(out, "$name.xml"))
    }
    private fun find(tag: String, list: String, direction: Direction = Direction.DOWN) {
        repeat(20) { if (device.hasObject(By.res(tag))) return; node(list).scroll(direction, .65f) }
        throw AssertionError("Missing $tag after scrolling")
    }
    private fun percent() = node("epub_progress").text.substringAfterLast('·').trim().removeSuffix("%").toFloat().toInt()
    private fun fixedPage() = node("epub_progress").text.substringBefore(" · ")
    private fun scrollToChapterEnd() {
        for (attempt in 0 until 25) {
            val action = device.findObject(By.res("epub_next_chapter"))
            if (action != null && action.visibleBounds.height() >= 48 * context.resources.displayMetrics.density) return
            val bounds = readingArea()
            device.swipe(bounds.centerX(), bounds.bottom - 30, bounds.centerX(), bounds.top + 30, 10)
            SystemClock.sleep(300)
        }
        shot("FAIL-chapter-scroll")
        throw AssertionError("The end action must be reachable with vertical scrolling")
    }
    private fun openReflow() { find("file_Yeobaek-Reflow.epub", "library_list"); click("file_Yeobaek-Reflow.epub"); ready() }
    private fun connect() {
        click("connect_folder")
        device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.(google\\.android|android)\\.documentsui"))), 15_000)
        pickerFolder()
        check(clickOptional(By.res(Pattern.compile("com\\.(google\\.android|android)\\.documentsui:id/action_menu_select")))
            || clickOptional(By.text("USE THIS FOLDER")))
        clickOptional(By.res("android:id/button1")); node("folder_refresh")
    }
    @Before fun setup() {
        require(pkg in setOf("com.poyal.yeobaek", "com.poyal.yeobaek.debug"))
        require(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        resetApp(); launch()
        click("nav_settings"); click("settings_general"); click("theme_$theme"); click("page_back"); click("nav_folder")
    }
    private fun resetApp() {
        device.executeShellCommand("pm clear $pkg")
        // Fresh-app journeys also need fresh picker history on the dedicated AVD.
        // API 30 can restore a zero-size action button; API 36 can restore its tree after navigation starts.
        device.executeShellCommand("pm clear com.android.documentsui")
    }
    @Test fun localReadingSearchBookmarksSettingsAndProcessDeath() {
        connect(); openReflow(); shot("01-reflow")
        body().visibleBounds.let { device.click(it.centerX(), it.centerY()) }
        Assert.assertTrue(device.wait(Until.gone(By.res("epub_footer")), 5000))
        Assert.assertFalse(node("epub_previous").isEnabled)
        Assert.assertTrue(node("epub_next").isEnabled)
        scrollToChapterEnd()
        shot("01-hidden-chrome-end"); click("epub_next_chapter")
        SystemClock.sleep(700)
        body().visibleBounds.let { device.click(it.centerX(), it.centerY()) }
        ready(); Assert.assertTrue(node("epub_current_chapter").text.startsWith("2장"))
        click("epub_previous"); ready()
        check(clickOptional(By.text("다음 장의 각주로 이동")))
        SystemClock.sleep(700); Assert.assertTrue(node("epub_current_chapter").text.startsWith("2장"))
        shot("01-internal-link"); click("epub_link_return")
        ready(); Assert.assertTrue(node("epub_current_chapter").text.startsWith("1장"))
        val selection = device.wait(Until.findObject(By.textStartsWith("1. 창문")), 10_000)!!.visibleBounds
        // Press an actual character in the first line, not a gap in a paragraph's line box.
        val sx = selection.left + 60
        val sy = selection.top + 30
        device.swipe(sx, sy, sx, sy, 120)
        val copy = device.wait(Until.findObject(By.text(Pattern.compile("Copy|COPY|복사"))), 10_000)
            ?: throw AssertionError("EPUB text selection did not offer Copy")
        shot("01-selection-copy"); copy.click(); device.waitForIdle()
        click("epub_next"); SystemClock.sleep(600)
        Assert.assertTrue(node("epub_current_chapter").text.startsWith("2장"))
        val body = readingArea()
        device.swipe(body.centerX(), body.bottom - 100, body.centerX(), body.centerY(), 55); SystemClock.sleep(1500)
        click("epub_bookmark"); click("epub_bookmarks"); node("epub_mark_0"); shot("02-bookmark"); click("sheet_close")
        click("epub_contents"); node("epub_chapter_2"); shot("03-contents"); click("epub_chapter_2")
        SystemClock.sleep(700); Assert.assertTrue(node("epub_current_chapter").text.startsWith("3장"))
        click("epub_link_return"); SystemClock.sleep(700); Assert.assertTrue(node("epub_current_chapter").text.startsWith("2장"))
        click("epub_search"); node("epub_search_input").text = "아침"; click("epub_search_submit")
        node("epub_search_next", 30_000); shot("04-search"); click("epub_search_next"); node("epub_search_previous")
        click("epub_result_0"); ready(); click("epub_search"); click("epub_search_return"); ready()
        Assert.assertTrue(node("epub_current_chapter").text.startsWith("2장"))
        click("epub_settings"); click("scope_BOOK"); click("epub_mode_paged"); shot("05-settings")
        find("epub_font_SERIF", "epub_options"); click("epub_font_SERIF"); click("page_back"); ready()
        val before = percent(); Assert.assertTrue(before in 33..66); shot("06-paged")
        SystemClock.sleep(1500)
        val oldPid = device.executeShellCommand("pidof $pkg").trim()
        device.pressHome(); SystemClock.sleep(1000); device.executeShellCommand("am kill $pkg"); SystemClock.sleep(1000)
        if (device.executeShellCommand("pidof $pkg").trim().isNotEmpty()) device.executeShellCommand("am force-stop $pkg")
        Assert.assertTrue(device.executeShellCommand("pidof $pkg").trim().isEmpty())
        launch(); node("resume_accept"); shot("07-resume"); click("resume_accept"); ready()
        val newPid = device.executeShellCommand("pidof $pkg").trim()
        Assert.assertNotEquals(oldPid, newPid); Assert.assertEquals(before.toDouble(), percent().toDouble(), 1.0)
        File(out, "process-death.txt").writeText("before_pid=$oldPid\nafter_pid=$newPid\nbefore_percent=$before\nafter_percent=${percent()}\n")
        shot("08-restored")
        body().visibleBounds.let { device.click(it.centerX(), it.centerY()) }
        Assert.assertTrue(device.wait(Until.gone(By.res("epub_footer")), 5000))
        device.setOrientationLeft(); body(); SystemClock.sleep(1000)
        Assert.assertTrue(device.wait(Until.gone(By.res("epub_loading")), 30_000))
        Assert.assertFalse(device.hasObject(By.res("epub_footer"))); shot("09-landscape-focus")
        body().visibleBounds.let { device.click(it.centerX(), it.centerY()) }
        ready(); Assert.assertTrue(node("epub_current_chapter").text.startsWith("2장")); shot("09-landscape")
        device.setOrientationNatural(); device.unfreezeRotation(); ready()
        val fontScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 2.0"); SystemClock.sleep(1000); ready()
            click("epub_settings"); node("epub_settings_screen")
            find("scope_BOOK", "epub_options", Direction.UP); click("scope_BOOK")
            find("epub_mode_scroll", "epub_options"); click("epub_mode_scroll")
            find("epub_chapter_buttons_HIDDEN", "epub_options"); click("epub_chapter_buttons_HIDDEN")
            shot("10-large-settings"); click("page_back"); ready()
            Assert.assertFalse(device.hasObject(By.res("epub_next")))
            scrollToChapterEnd(); shot("10-large-chapter-end")
            val scrollPercent = percent()
            SystemClock.sleep(1500)
            val scrollPid = device.executeShellCommand("pidof $pkg").trim()
            device.pressHome(); SystemClock.sleep(1000); device.executeShellCommand("am force-stop $pkg")
            Assert.assertTrue(device.executeShellCommand("pidof $pkg").trim().isEmpty())
            launch(); click("resume_accept"); ready()
            val restoredPid = device.executeShellCommand("pidof $pkg").trim()
            Assert.assertNotEquals(scrollPid, restoredPid)
            Assert.assertEquals(scrollPercent.toDouble(), percent().toDouble(), 1.0)
            Assert.assertFalse(device.hasObject(By.res("epub_next")))
            node("epub_next_chapter"); shot("10-restored-chapter-end")
            File(out, "scroll-process-death.txt").writeText("before_pid=$scrollPid\nafter_pid=$restoredPid\nbefore_percent=$scrollPercent\nafter_percent=${percent()}\n")
        } finally { device.executeShellCommand("settings put system font_scale $fontScale") }
        click("epub_back"); click("nav_settings"); find("settings_epub", "app_settings"); click("settings_epub")
        node("epub_settings_screen"); Assert.assertFalse(device.hasObject(By.res("scope_BOOK"))); shot("11-main-settings-entry")
    }
    private fun openExternal(name: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.poyal.yeobaek.e2e.inputs/$name"), "application/epub+zip")
            .setClassName(pkg, "com.poyal.yeobaek.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    @Test fun externalFixedLayoutZoomDrmAndPermissionRecovery() {
        context.assets.open("epub/Yeobaek-Fixed.epub").use { input -> File(context.cacheDir, "external-fixed.epub").outputStream().use { input.copyTo(it) } }
        context.assets.open("epub/Yeobaek-Reflow.epub").use { input -> File(context.cacheDir, "external-reflow.epub").outputStream().use { input.copyTo(it) } }
        openExternal("external-fixed.epub"); ready(); Assert.assertEquals("1 / 3 페이지", fixedPage())
        click("epub_next"); SystemClock.sleep(800); Assert.assertEquals("2 / 3 페이지", fixedPage())
        body().pinchOpen(.65f); shot("12-fixed-zoom")
        click("epub_settings"); Assert.assertFalse(device.hasObject(By.res("epub_size"))); click("epub_canvas_DARK"); shot("13-fixed-settings")
        click("page_back"); ready(); Assert.assertEquals("2 / 3 페이지", fixedPage())
        click("epub_next"); node("reading_end_dialog"); shot("13-fixed-complete")
        // A fixed-layout page is fully visible on arrival, so the last page announces completion.
        device.pressBack(); ready(); Assert.assertEquals("3 / 3 페이지", fixedPage()); Assert.assertEquals(100, percent())
        click("epub_back"); openExternal("external-fixed.epub"); ready()
        Assert.assertEquals("3 / 3 페이지", fixedPage()); Assert.assertEquals(100, percent())
        Assert.assertFalse(device.hasObject(By.res("reading_end_dialog")))
        click("epub_previous"); SystemClock.sleep(700)
        Assert.assertEquals("2 / 3 페이지", fixedPage()); Assert.assertTrue(percent() < 100); click("epub_back")
        openExternal("external-reflow.epub"); ready(); click("epub_next"); SystemClock.sleep(700); click("epub_bookmark"); click("epub_back")
        context.revokeUriPermission(Uri.parse("content://com.poyal.yeobaek.e2e.inputs/external-reflow.epub"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        click("nav_recent"); click("recent_external-reflow.epub"); node("find_missing_book"); shot("14-permission-recovery"); click("error_close")
        ZipOutputStream(File(context.cacheDir, "external-drm.epub").outputStream()).use { zip ->
            ZipInputStream(context.assets.open("epub/Yeobaek-Reflow.epub")).use { input ->
                while (true) { val entry = input.nextEntry ?: break; zip.putNextEntry(ZipEntry(entry.name)); input.copyTo(zip); zip.closeEntry() }
            }
            zip.putNextEntry(ZipEntry("META-INF/rights.xml")); zip.write("<rights/>".toByteArray()); zip.closeEntry()
        }
        openExternal("external-drm.epub"); node("error_close")
        Assert.assertTrue(device.hasObject(By.textContains("DRM"))); shot("15-drm")
    }
    private fun documentsFolder() {
        device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.(google\\.android|android)\\.documentsui"))), 10_000)
        pickerFolder()
    }
    private fun selectDocument(name: String) {
        repeat(30) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val list = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/dir_list"))), 5000)
                ?: error("No document list")
            val area = list.visibleBounds
            val item = list.findObject(By.text(name))
            val bounds = item?.visibleBounds
            // A clipped last-row title can extend under the system navigation bar. Clicking
            // it can press Back or Recents instead of opening the backup. Scroll it fully into
            // the document list and query again after the system picker updates its layout.
            if (bounds != null && bounds.height() >= 24 && bounds.top >= area.top + 8 && bounds.bottom <= area.bottom - 24) {
                item.click(); device.waitForIdle(); return
            }
            list.scroll(if (bounds != null && bounds.height() > 0 && bounds.top < area.top + 8) Direction.UP else Direction.DOWN, .5f)
            device.waitForIdle()
        }
        throw AssertionError("Backup document not found: $name")
    }
    private fun pickerFolder() {
        SystemClock.sleep(500); device.waitForIdle()
        // ACTION_OPEN_DOCUMENT_TREE can restore this exact folder and omit the drawer button.
        if (device.hasObject(By.text("Files in YeobaekEPUB"))) return
        if (device.hasObject(By.res(Pattern.compile(".*:id/breadcrumb_text")).text("YeobaekEPUB"))) return
        val download = By.res(Pattern.compile(".*:id/breadcrumb_text")).text(Pattern.compile("Downloads?"))
        if (device.hasObject(download)) check(clickOptional(download)) else {
            // Android 11's tree picker can start at storage root without a drawer button.
            val downloads = By.text(Pattern.compile("Downloads?"))
            if (!device.hasObject(downloads)) check(clickOptional(By.desc("Show roots")))
            check(clickOptional(downloads))
        }
        if (device.hasObject(By.text("Files in YeobaekEPUB"))) return
        selectDocument("YeobaekEPUB")
    }
    @Test fun backupFreshRestoreAndChangedOriginalKeepRecords() {
        connect(); openReflow(); click("epub_next"); SystemClock.sleep(700)
        click("epub_bookmark"); click("epub_settings"); click("scope_BOOK"); click("epub_mode_paged"); click("page_back"); ready()
        val progress = percent(); click("epub_back")
        click("nav_settings"); click("settings_general"); find("backup_export", "general_settings_options"); click("backup_export")
        documentsFolder()
        val name = "epub-backup-${System.currentTimeMillis()}.json"
        device.findObject(By.clazz("android.widget.EditText"))!!.text = name
        (device.findObject(By.res(Pattern.compile(".*:id/action_menu_save"))) ?: node("android:id/button1")).click()
        node("backup_status"); shot("16-export")
        val backup = JSONObject(device.executeShellCommand("cat /sdcard/Download/YeobaekEPUB/$name"))
        Assert.assertEquals(2, backup.getInt("version"))
        Assert.assertEquals(1, backup.getJSONObject("library").getJSONArray("epubBookmarks").length())
        Assert.assertEquals(1, backup.getJSONObject("library").getJSONArray("epubPositions").length())
        File(out, "epub-backup.json").writeText(backup.toString(2))
        resetApp(); launch()
        click("nav_settings"); click("settings_general"); find("backup_import", "general_settings_options"); click("backup_import")
        documentsFolder(); selectDocument(name)
        val restore = device.wait(Until.findObject(By.text("없는 기록 추가")), 10_000) ?: node("backup_restore_confirm")
        shot("17-restore-preview"); restore.click(); node("backup_status")
        click("page_back"); click("nav_folder"); connect(); openReflow()
        Assert.assertEquals(progress.toDouble(), percent().toDouble(), 1.0)
        click("epub_bookmarks"); node("epub_mark_0"); shot("18-restored-bookmark"); click("sheet_close")
        click("epub_settings"); click("scope_BOOK"); node("epub_mode_paged")
        Assert.assertTrue(device.wait(Until.hasObject(By.res("epub_mode_paged").checked(true)), 5000)); click("page_back"); click("epub_back")
        val source = "/sdcard/Download/YeobaekEPUB/Yeobaek-Reflow.epub"
        val original = "$source.qa-original"
        device.executeShellCommand("cp $source $original")
        try {
            device.executeShellCommand("cp /sdcard/Download/YeobaekEPUB/Yeobaek-Fixed.epub $source")
            click("nav_recent"); click("recent_Yeobaek-Reflow.epub")
            check(device.wait(Until.hasObject(By.text("EPUB이 변경되었어요")), 20_000))
            shot("19-source-change"); check(clickOptional(By.text("취소")))
            check(device.wait(Until.gone(By.text("EPUB이 변경되었어요")), 5000))
            Assert.assertFalse(device.hasObject(By.res("epub_footer")))
            click("recent_Yeobaek-Reflow.epub"); check(clickOptional(By.text("처음부터 열기"))); ready()
            Assert.assertEquals("1 / 3 페이지", fixedPage())
            click("epub_bookmarks"); node("epub_mark_0"); Assert.assertTrue(device.hasObject(By.text("변경 전 EPUB"))); shot("20-old-bookmark")
        } finally { device.executeShellCommand("cp $original $source"); device.executeShellCommand("rm $original") }
    }
}
