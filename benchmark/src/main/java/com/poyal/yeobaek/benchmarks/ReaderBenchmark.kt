@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
package com.poyal.yeobaek.benchmarks

import android.os.SystemClock
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the .benchmark APK. Setup uses the same SAF UI as a person, never an injected reader. */
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val args = InstrumentationRegistry.getArguments()
    private val iterations get() = args.getString("iterations")?.toInt() ?: 10
    private val duration get() = args.getString("durationSeconds")?.toLong() ?: 30L
    private val pkg = "com.poyal.yeobaek.benchmark"

    @Test fun scroll10MiB() = scroll("010MiB.txt")
    @Test fun scroll50MiB() = scroll("050MiB.txt")
    @Test fun scrollLongParagraph() = scroll("줄바꿈 없는 1MiB.txt")
    @Test fun scrollManyEmptyLines() = scroll("빈 줄 수십만 개.txt")
    private fun scroll(file: String) = benchmark.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        iterations = iterations,
        setupBlock = {
            openFixture(file)
        },
    ) {
        val end = SystemClock.elapsedRealtime() + duration * 1000
        var turn = 0
        while (SystemClock.elapsedRealtime() < end) {
            val bounds = device.findObject(By.res("reader_body")).visibleBounds
            // Mostly forward into unvisited blocks, then reverse to expose reclamation bugs.
            val reverse = turn++ % 10 >= 8
            device.swipe(bounds.centerX(), if (reverse) bounds.top + bounds.height()/4 else bounds.bottom - bounds.height()/4,
                bounds.centerX(), if (reverse) bounds.bottom - bounds.height()/4 else bounds.top + bounds.height()/4, 12)
        }
    }
    @Test fun warmOpen10MiB() = warmOpen(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))
    @Test fun warmOpen10MiBWithoutProfile() = warmOpen(CompilationMode.None())
    private fun warmOpen(compilation: CompilationMode) = benchmark.measureRepeated(
        packageName = pkg, metrics = listOf(TraceSectionMetric("OpenText")),
        compilationMode = compilation, iterations = args.getString("iterations")?.toInt() ?: 30,
        setupBlock = {
            openFixture("010MiB.txt")
            device.findObject(By.res("reader_back")).click()
            device.wait(Until.hasObject(By.res("folder_refresh")), 10000)
        },
    ) {
        clickFile("010MiB.txt")
        awaitReader("010MiB.txt")
    }
    private fun MacrobenchmarkScope.openFixture(file: String) {
        pressHome(); startActivityAndWait()
        device.waitForIdle()
        device.findObject(By.res("resume_decline"))?.click()
        device.findObject(By.res("reader_back"))?.click()
        check(device.wait(Until.hasObject(By.res("nav_folder")), 15000))
        device.findObject(By.res("nav_folder")).click()
        if (device.hasObject(By.res("connect_folder"))) connect()
        check(device.wait(Until.hasObject(By.res("folder_refresh")), 15000))
        device.findObject(By.res("folder_up"))?.let {
            it.click()
            check(device.wait(Until.gone(By.res("folder_up")), 15000))
        }
        find("folder_성능").click()
        check(device.wait(Until.hasObject(By.res("folder_up")), 15000))
        clickFile(file); awaitReader(file)
    }
    private fun MacrobenchmarkScope.find(tag: String): UiObject2 {
        repeat(30) {
            device.findObject(By.res(tag))?.let { return it }
            device.wait(Until.findObject(By.res("library_list")), 15000)?.scroll(Direction.DOWN, .7f)
            device.waitForIdle()
        }
        throw AssertionError("찾지 못한 성능 fixture: $tag")
    }
    private fun MacrobenchmarkScope.clickFile(file: String) {
        find("file_$file"); device.waitForIdle(); SystemClock.sleep(500)
        val bounds = device.findObject(By.res("file_$file")).visibleBounds
        check(device.click(bounds.centerX(), bounds.centerY()))
    }
    private fun MacrobenchmarkScope.awaitReader(file: String) {
        check(device.wait(Until.hasObject(By.res("reader_footer")), 120000))
        check(device.wait(Until.gone(By.res("layout_loading")), 120000))
        check(device.wait(Until.hasObject(By.res(java.util.regex.Pattern.compile("text_\\d+"))), 15000))
        device.waitForIdle()
        check(device.findObject(By.res("reader_header")).hasObject(By.text(file.removeSuffix(".txt")))) { "요청한 파일과 열린 책이 다릅니다: $file" }
    }
    private fun MacrobenchmarkScope.connect() {
        device.findObject(By.res("connect_folder")).click()
        device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 15000)
        device.wait(Until.findObject(By.desc("Show roots")), 5000)?.click()
        device.wait(Until.findObject(By.text("Downloads")), 1000)?.click()
        device.findObject(By.text("Download"))?.click()
        device.wait(Until.findObject(By.text("Yeobaek")), 5000)!!.click()
        (device.wait(Until.findObject(By.res("com.google.android.documentsui:id/action_menu_select")), 1000) ?: device.findObject(By.res("android:id/button1")))!!.click()
        device.wait(Until.findObject(By.res("android:id/button1")), 5000)?.click()
        check(device.wait(Until.hasObject(By.res("folder_refresh")), 10000))
    }
}
