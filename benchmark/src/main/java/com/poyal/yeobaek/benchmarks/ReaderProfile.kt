package com.poyal.yeobaek.benchmarks

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Profile variant has no obfuscation so AGP can map these rules into each optimized APK. */
@RunWith(AndroidJUnit4::class)
class ReaderProfile {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun reading() = profile.collect(
        packageName = "com.poyal.yeobaek.profile",
        includeInStartupProfile = false,
        filterPredicate = { it.contains("Lcom/poyal/yeobaek/") },
    ) {
        pressHome(); startActivityAndWait()
        fun node(tag: String) = device.wait(Until.findObject(By.res(tag)), 30000) ?: throw AssertionError(tag)
        fun click(tag: String) { node(tag).click(); device.waitForIdle() }
        device.findObject(By.res("resume_decline"))?.click()
        if (device.hasObject(By.res("connect_folder"))) {
            click("connect_folder")
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10000)
            device.findObject(By.desc("Show roots"))?.click()
            device.findObject(By.text("Downloads"))?.click(); device.findObject(By.text("Download"))?.click()
            device.wait(Until.findObject(By.text("Yeobaek")), 10000)!!.click()
            (device.wait(Until.findObject(By.res("com.google.android.documentsui:id/action_menu_select")), 1000) ?: device.findObject(By.res("android:id/button1")))!!.click()
            device.wait(Until.findObject(By.res("android:id/button1")), 5000)?.click()
        }
        node("folder_refresh")
        repeat(10) { if (!device.hasObject(By.res("file_비가 그친 오후.txt"))) node("library_list").scroll(Direction.DOWN, .6f) }
        click("file_비가 그친 오후.txt"); node("reader_footer")
        check(device.wait(Until.gone(By.res("layout_loading")), 30000))
        repeat(5) { node("reader_body").scroll(Direction.DOWN, .8f) }
        click("reader_txt"); click("font_larger"); click("sheet_close")
        click("reader_search"); node("search_input").text = "골목"; click("search_submit"); node("search_next")
        click("search_next"); click("reader_back")
    }
}
