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
    private fun find(tag: String, list: String) {
        repeat(20) { if (device.hasObject(By.res(tag))) return; node(list).scroll(Direction.DOWN, .65f) }
        throw AssertionError("Missing $tag after scrolling")
    }
    private fun percent() = node("epub_progress").text.substringAfterLast('·').trim().removeSuffix("%").toInt()
    private fun openReflow() { find("file_Yeobaek-Reflow.epub", "library_list"); click("file_Yeobaek-Reflow.epub"); ready() }
    private fun connect() {
        click("connect_folder")
        device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.(google\\.android|android)\\.documentsui"))), 15_000)
        pickerFolder()
        val select = device.wait(Until.findObject(By.res(Pattern.compile("com\\.(google\\.android|android)\\.documentsui:id/action_menu_select"))), 5000)
            ?: device.findObject(By.text("USE THIS FOLDER")) ?: error("No folder select button")
        select.click(); device.wait(Until.findObject(By.res("android:id/button1")), 5000)?.click(); node("folder_refresh")
    }
    @Before fun setup() {
        require(pkg in setOf("com.poyal.yeobaek", "com.poyal.yeobaek.debug"))
        require(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        device.executeShellCommand("pm clear $pkg"); launch()
        click("nav_settings"); click("settings_general"); click("theme_$theme"); click("page_back"); click("nav_folder")
    }
    @Test fun localReadingSearchBookmarksSettingsAndProcessDeath() {
        connect(); openReflow(); shot("01-reflow")
        check(clickOptional(By.text("다음 장의 각주로 이동")))
        SystemClock.sleep(700); Assert.assertTrue(node("epub_progress").text.startsWith("2장"))
        shot("01-internal-link"); click("epub_link_return")
        ready(); Assert.assertTrue(node("epub_progress").text.startsWith("1장"))
        val selection = device.wait(Until.findObject(By.textStartsWith("1. 창문")), 10_000)!!.visibleBounds
        // Press an actual character in the first line, not a gap in a paragraph's line box.
        val sx = selection.left + 60
        val sy = selection.top + 30
        device.swipe(sx, sy, sx, sy, 120)
        val copy = device.wait(Until.findObject(By.text(Pattern.compile("Copy|COPY|복사"))), 10_000)
            ?: throw AssertionError("EPUB text selection did not offer Copy")
        shot("01-selection-copy"); copy.click(); device.waitForIdle()
        click("epub_next"); SystemClock.sleep(600)
        Assert.assertTrue(node("epub_progress").text.startsWith("2장"))
        val body = body().visibleBounds
        device.swipe(body.centerX(), body.bottom - 100, body.centerX(), body.centerY(), 55); SystemClock.sleep(1500)
        click("epub_bookmark"); click("epub_bookmarks"); node("epub_mark_0"); shot("02-bookmark"); click("sheet_close")
        click("epub_contents"); node("epub_chapter_2"); shot("03-contents"); click("epub_chapter_2")
        SystemClock.sleep(700); Assert.assertTrue(node("epub_progress").text.startsWith("3장"))
        click("epub_link_return"); SystemClock.sleep(700); Assert.assertTrue(node("epub_progress").text.startsWith("2장"))
        click("epub_search"); node("epub_search_input").text = "아침"; click("epub_search_submit")
        node("epub_search_next", 30_000); shot("04-search"); click("epub_search_next"); node("epub_search_previous")
        click("epub_result_0"); ready(); click("epub_search"); click("epub_search_return"); ready()
        Assert.assertTrue(node("epub_progress").text.startsWith("2장"))
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
        ready(); Assert.assertTrue(node("epub_progress").text.startsWith("2장")); shot("09-landscape")
        device.setOrientationNatural(); device.unfreezeRotation(); ready()
        val fontScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 2.0"); SystemClock.sleep(1000); ready()
            click("epub_settings"); node("epub_settings_screen"); shot("10-large-settings"); click("page_back"); ready()
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
        openExternal("external-fixed.epub"); ready(); Assert.assertEquals("1 / 3 페이지", node("epub_progress").text)
        click("epub_next"); SystemClock.sleep(800); Assert.assertEquals("2 / 3 페이지", node("epub_progress").text)
        body().pinchOpen(.65f); shot("12-fixed-zoom")
        click("epub_settings"); Assert.assertFalse(device.hasObject(By.res("epub_size"))); click("epub_canvas_DARK"); shot("13-fixed-settings")
        click("page_back"); ready(); Assert.assertEquals("2 / 3 페이지", node("epub_progress").text)
        click("epub_next"); SystemClock.sleep(700); Assert.assertEquals("3 / 3 페이지", node("epub_progress").text)
        click("epub_next"); node("reading_end_dialog"); shot("13-fixed-complete"); click("reading_end_exit")
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
            val item = device.findObject(By.text(name))
            if (item != null && item.visibleBounds.height() > 0) { item.click(); device.waitForIdle(); return }
            val list = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/dir_list"))), 5000)
                ?: error("No document list")
            list.scroll(Direction.DOWN, .65f)
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
            check(clickOptional(By.desc("Show roots")))
            check(clickOptional(By.text(Pattern.compile("Downloads?"))))
        }
        if (device.hasObject(By.text("Files in YeobaekEPUB"))) return
        check(clickOptional(By.text("YeobaekEPUB")))
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
        device.executeShellCommand("pm clear $pkg"); launch()
        click("nav_settings"); click("settings_general"); find("backup_import", "general_settings_options"); click("backup_import")
        documentsFolder(); selectDocument(name)
        val restore = device.wait(Until.findObject(By.text("없는 기록 추가")), 10_000) ?: node("backup_restore_confirm")
        shot("17-restore-preview"); restore.click(); node("backup_status")
        click("page_back"); click("nav_folder"); connect(); openReflow()
        Assert.assertEquals(progress.toDouble(), percent().toDouble(), 1.0)
        click("epub_bookmarks"); node("epub_mark_0"); shot("18-restored-bookmark"); click("sheet_close")
        click("epub_settings"); click("scope_BOOK"); node("epub_mode_paged")
        Assert.assertTrue(device.hasObject(By.text("페이지 넘김 ✓"))); click("page_back"); click("epub_back")
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
            Assert.assertEquals("1 / 3 페이지", node("epub_progress").text)
            click("epub_bookmarks"); node("epub_mark_0"); Assert.assertTrue(device.hasObject(By.text("변경 전 EPUB"))); shot("20-old-bookmark")
        } finally { device.executeShellCommand("cp $original $source"); device.executeShellCommand("rm $original") }
    }
}
