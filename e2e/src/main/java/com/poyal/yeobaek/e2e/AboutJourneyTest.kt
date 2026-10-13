package com.poyal.yeobaek.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real release routes, both palettes and accessibility font sizes without clearing records. */
@RunWith(AndroidJUnit4::class)
class AboutJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = InstrumentationRegistry.getArguments().getString("targetPackage") ?: "com.poyal.yeobaek"
    private val out = File(instrumentation.context.getExternalFilesDir(null), "about").apply { mkdirs() }
    private fun node(tag: String) = device.wait(Until.findObject(By.res(tag)), 15000) ?: error("요소가 없습니다: $tag")
    private fun click(tag: String) { node(tag).click(); device.waitForIdle() }
    private fun scrollTo(tag: String, container: String) {
        repeat(20) {
            val current = device.findObject(By.res(tag))
            if (current != null && current.visibleBounds.height() >= 40) return
            node(container).scroll(Direction.DOWN, .6f); device.waitForIdle()
        }
        error("스크롤 후에도 요소 없음: $tag")
    }
    private fun capture(name: String) {
        device.waitForIdle(); SystemClock.sleep(300)
        check(device.takeScreenshot(File(out, "$name.png")))
        device.dumpWindowHierarchy(File(out, "$name.xml"))
    }
    @Test fun aboutAndUpdatesRespectThemeLargeTextAndBackNavigation() {
        require(pkg in setOf("com.poyal.yeobaek", "com.poyal.yeobaek.debug"))
        val previousScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            for (scale in listOf("1.0", "2.0")) for (theme in listOf("LIGHT", "DARK")) {
                device.executeShellCommand("settings put system font_scale $scale")
                device.executeShellCommand("am force-stop $pkg")
                device.executeShellCommand("am start -W -n $pkg/com.poyal.yeobaek.MainActivity")
                node("nav_settings")
                if (device.wait(Until.hasObject(By.res("resume_decline")), 1000)) click("resume_decline")
                if (device.hasObject(By.res("update_prompt_later"))) click("update_prompt_later")
                click("nav_settings"); scrollTo("theme_$theme", "app_settings"); click("theme_$theme")
                scrollTo("settings_about", "app_settings"); capture("$theme-$scale-settings")
                click("settings_about"); node("about_screen")
                assertTrue(device.hasObject(By.text("앱 정보·문의")))
                capture("$theme-$scale-about-top")
                for (tag in listOf("about_email", "about_github", "about_issues", "about_releases")) {
                    scrollTo(tag, "about_screen"); assertTrue(node(tag).isClickable)
                }
                capture("$theme-$scale-about-links")
                device.pressBack(); node("app_settings")
                scrollTo("settings_updates", "app_settings"); click("settings_updates")
                node("updates_screen"); scrollTo("update_check", "updates_screen")
                check(device.wait(Until.findObject(By.res("update_check").enabled(true)), 30000) != null)
                click("update_check")
                check(device.wait(Until.findObject(By.res("update_check").enabled(true)), 30000) != null)
                capture("$theme-$scale-updates-top")
                scrollTo("update_folder", "updates_screen"); assertTrue(node("update_folder").isEnabled)
                capture("$theme-$scale-updates-bottom")
                click("page_back"); node("app_settings")
            }
        } catch (e: Throwable) { capture("FAIL-about-updates"); throw e }
        finally { device.executeShellCommand("settings put system font_scale $previousScale") }
    }
}
