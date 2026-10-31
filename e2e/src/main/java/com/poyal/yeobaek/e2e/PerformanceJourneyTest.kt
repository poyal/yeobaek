package com.poyal.yeobaek.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.poyal.yeobaek.testing.JourneyDevice
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Stability/load journeys. Emulator durations are evidence of regressions, not physical-device budgets. */
@RunWith(AndroidJUnit4::class)
class PerformanceJourneyTest {
    private val args = InstrumentationRegistry.getArguments()
    private val pkg = args.getString("targetPackage") ?: "com.poyal.yeobaek.benchmark"
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val out = File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "performance/${System.currentTimeMillis()}").apply { mkdirs() }
    private fun node(id: String, wait: Long = 15000) = device.wait(Until.findObject(By.res(id)), wait) ?: throw AssertionError("요소 없음: $id")
    private fun click(id: String) {
        if (id.startsWith("folder_") && !device.hasObject(By.res(id))) scrollFind(id)
        node(id).click(); device.waitForIdle()
    }
    @Before fun prepare() {
        JourneyDevice(pkg).apply { reset(); connect("Yeobaek") }
    }
    private fun scrollFind(tag: String) {
        repeat(25) {
            if (device.hasObject(By.res(tag))) return
            node("library_list").scroll(Direction.DOWN, .65f); device.waitForIdle()
        }
        throw AssertionError(tag)
    }
    private fun open(file: String): Long {
        scrollFind("file_$file")
        device.waitForIdle(); SystemClock.sleep(500)
        val bounds = node("file_$file").visibleBounds
        val start = SystemClock.elapsedRealtime()
        check(device.click(bounds.centerX(), bounds.centerY()))
        node("reader_footer", 120000)
        check(device.wait(Until.gone(By.res("layout_loading")), 120000))
        check(device.wait(Until.hasObject(By.res(java.util.regex.Pattern.compile("text_\\d+"))), 15000))
        Assert.assertTrue("요청한 파일과 열린 책이 다릅니다: $file", node("reader_header").hasObject(By.text(file.removeSuffix(".txt"))))
        return SystemClock.elapsedRealtime() - start
    }
    private fun memory(label: String) {
        File(out, "$label-memory.txt").writeText(device.executeShellCommand("dumpsys meminfo $pkg"))
        File(out, "$label-frames.txt").writeText(device.executeShellCommand("dumpsys gfxinfo $pkg"))
        File(out, "$label-pid.txt").writeText(device.executeShellCommand("pidof $pkg"))
    }
    private fun screen(label: String) { device.takeScreenshot(File(out, "$label.png")); device.dumpWindowHierarchy(File(out, "$label.xml")) }
    private fun waitForSettledText() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15000
        fun settled(): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.viewIdResourceName == "reader_body") return node.stateDescription?.toString() == "정지"
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            }
            return false
        }
        SystemClock.sleep(100)
        while (!settled() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        check(settled()) { "TXT 스크롤이 15초 안에 정지하지 않았습니다." }
    }
    private fun firstVisibleTextBlock(): Long {
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            try {
                device.findObjects(By.res(java.util.regex.Pattern.compile("text_\\d+")))
                    .filter { it.visibleBounds.height() > 0 }
                    .map { it.resourceName.substringAfter("text_").toLong() }.minOrNull()?.let { return it }
            } catch (_: StaleObjectException) {
                // Prefetch can replace accessibility nodes between lookup and bounds access.
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("보이는 TXT 본문 블록이 없습니다")
    }

    @Test fun largeFilesAndLongParagraph() {
        memory("library-baseline")
        click("folder_성능")
        for (name in listOf("001MiB.txt", "010MiB.txt", "050MiB.txt", "100MiB.txt", "줄바꿈 없는 1MiB.txt", "빈 줄 수십만 개.txt", "결합문자.txt")) {
            val elapsed = open(name)
            File(out, "open-times.tsv").appendText("$name\t$elapsed\n")
            screen(name.removeSuffix(".txt")); memory(name.removeSuffix(".txt"))
            repeat(5) { node("reader_body").fling(Direction.DOWN) }
            click("reader_move")
            val slider = node("move_slider").visibleBounds
            device.click(slider.left + slider.width() * 8 / 10, slider.centerY()); click("move_apply")
            // The seek effect may start after the sheet closes. Absence of a spinner alone can
            // still mean the old page: wait for the requested position as well as layout completion.
            val deadline = SystemClock.elapsedRealtime() + 120000
            fun arrived(): Boolean {
                val percent = device.findObject(By.res("reader_percent"))?.text?.removeSuffix("%")?.toFloatOrNull()
                return percent != null && percent in 75f..90f && !device.hasObject(By.res("layout_loading"))
            }
            while (!arrived() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            check(arrived()) { "$name: 목표 위치의 본문 배치가 완료되지 않았습니다." }
            screen(name.removeSuffix(".txt") + "-80percent")
            repeat(5) { node("reader_body").fling(Direction.UP) }
            click("reader_back")
        }
        scrollFind("file_101MiB.txt"); click("file_101MiB.txt"); node("error_close", 120000); screen("source-limit"); click("error_close")
    }
    @Test fun continuousReading30Minutes() {
        click("folder_성능"); open("050MiB.txt")
        val duration = (args.getString("soakMinutes")?.toLong() ?: 30L) * 60000
        screen("soak-start"); memory("soak-start")
        val originalPid = device.executeShellCommand("pidof $pkg").trim()
        device.executeShellCommand("dumpsys gfxinfo $pkg reset")
        val start = SystemClock.elapsedRealtime()
        var iterations = 0; var nextSample = 5L * 60000
        val bounds = node("reader_body").visibleBounds
        waitForSettledText()
        var previousBlock = firstVisibleTextBlock()
        while (SystemClock.elapsedRealtime() - start < duration) {
            val reverse = iterations % 10 >= 8
            device.swipe(bounds.centerX(), if (reverse) bounds.top + bounds.height()/4 else bounds.bottom - bounds.height()/4,
                bounds.centerX(), if (reverse) bounds.bottom - bounds.height()/4 else bounds.top + bounds.height()/4, 18)
            waitForSettledText()
            val currentBlock = firstVisibleTextBlock()
            Assert.assertTrue("문장이 스크롤 반대 방향으로 이동: $previousBlock -> $currentBlock reverse=$reverse",
                if (reverse) currentBlock <= previousBlock else currentBlock >= previousBlock)
            previousBlock = currentBlock
            iterations++
            if (iterations % 20 == 0) {
                Assert.assertTrue("독서 화면 이탈 또는 오류", device.hasObject(By.res("reader_body")) && !device.hasObject(By.res("error_close")) && !device.hasObject(By.res("layout_retry")))
                Assert.assertEquals("프로세스가 재시작되었습니다", originalPid, device.executeShellCommand("pidof $pkg").trim())
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed >= nextSample) { memory("soak-${nextSample / 60000}min"); nextSample += 5L * 60000 }
        }
        memory("soak-end"); screen("soak-end")
        File(out, "soak.tsv").writeText("durationMs\t${SystemClock.elapsedRealtime()-start}\nswipes\t$iterations\npid\t$originalPid\n")
    }
    @Test fun repeatedOpen100Times() {
        click("folder_성능")
        val count = args.getString("repetitions")?.toInt() ?: 100
        memory("repeat-before")
        repeat(count) { i ->
            val ms = open("001MiB.txt")
            node("reader_body").scroll(Direction.DOWN, .8f)
            click("reader_back")
            File(out, "reopen.tsv").appendText("$i\t$ms\n")
            if (i % 25 == 24) memory("repeat-${i+1}")
        }
        memory("repeat-after"); screen("repeat-after")
    }
}
