package com.poyal.yeobaek.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run after the DARK journey, over-install, then reboot. Never clears app data. */
@RunWith(AndroidJUnit4::class)
class RestoreJourneyTest {
    @Test fun folderThemePositionAndBookmarksSurvive() {
        val args = InstrumentationRegistry.getArguments()
        val pkg = args.getString("targetPackage") ?: "com.poyal.yeobaek"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun node(tag: String) = device.wait(Until.findObject(By.res(tag)), 30000) ?: throw AssertionError(tag)
        fun click(tag: String) { node(tag).click(); device.waitForIdle() }
        device.executeShellCommand("am force-stop $pkg")
        device.executeShellCommand("am start -W -n $pkg/com.poyal.yeobaek.MainActivity")
        node("folder_refresh"); assertFalse(device.hasObject(By.res("resume_accept")))
        click("continue_card"); node("reader_footer")
        check(device.wait(Until.gone(By.res("layout_loading")), 60000))
        assertTrue(node("reader_percent").text.removeSuffix("%").toFloat() > 50f)
        click("reader_bookmarks"); node("bookmark_delete"); click("sheet_close")
        click("reader_txt"); click("quick_details")
        for (attempt in 0 until 12) {
            if (device.hasObject(By.res("font_size"))) break
            node("txt_options").scroll(Direction.DOWN, .5f)
        }
        assertEquals("18", node("font_size").text); click("page_back"); click("reader_back")
        click("nav_settings"); click("settings_general"); assertTrue(node("theme_DARK").isSelected)
        val out = File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "restore").apply { mkdirs() }
        device.takeScreenshot(File(out, "preserved.png")); device.dumpWindowHierarchy(File(out, "preserved.xml"))
    }
}
