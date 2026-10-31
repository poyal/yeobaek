package com.poyal.yeobaek.testing

import android.graphics.Bitmap
import android.graphics.Point
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs

/** UI/OS only: shared by external E2E and optimized macrobenchmarks. */
class JourneyDevice(val pkg: String = InstrumentationRegistry.getArguments().getString("targetPackage") ?: "com.poyal.yeobaek.debug") {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val args = InstrumentationRegistry.getArguments()
    val theme = args.getString("theme") ?: "LIGHT"
    val out = File(instrumentation.context.getExternalFilesDir(null), "journeys/$theme").apply { mkdirs() }
    private val metrics = File(out, "steps.ndjson")

    fun shell(command: String) = device.executeShellCommand(command).trim()
    fun node(tag: String, timeout: Long = 20_000): UiObject2 = device.wait(Until.findObject(By.res(tag)), timeout)
        ?: error("Missing UI control: $tag")
    fun exists(tag: String) = device.hasObject(By.res(tag))
    fun body(kind: String): UiObject2 = when (kind) {
        "pdf" -> device.wait(Until.findObject(By.descStartsWith("PDF ")), 20_000)
        "image" -> device.wait(Until.findObject(By.descStartsWith("이미지 ")), 20_000)
        "epub" -> device.wait(Until.findObject(By.res("$pkg:id/epub_host_container")), 20_000)
        else -> node("${kind}_body")
    } ?: error("Missing $kind reading surface")
    fun click(tag: String) {
        if (tag in setOf("folder_up", "folder_refresh") && !exists(tag) && exists("library_list")) find(tag, direction = Direction.UP)
        if (!exists(tag)) listOf("txt_options", "pdf_options", "image_options", "epub_options", "quick_options", "general_settings_options")
            .firstOrNull(::exists)?.let { find(tag, it) }
        var bounds = node(tag).visibleBounds
        await("UI control is still moving: $tag", 10_000) {
            SystemClock.sleep(250)
            val current = node(tag).visibleBounds
            (current == bounds).also { bounds = current }
        }
        node(tag).click(); device.waitForIdle()
    }
    fun text(value: String) = device.wait(Until.findObject(By.text(value)), 20_000) ?: error("Missing text: $value")
    fun await(message: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (condition()) return } catch (_: StaleObjectException) { }
            SystemClock.sleep(100)
        }
        error(message)
    }
    fun <T> step(id: String, action: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            val result = action()
            metrics.appendText(JSONObject().put("step", id).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                .put("status", "PASS").toString() + "\n")
            return result
        } catch (e: Throwable) {
            metrics.appendText(JSONObject().put("step", id).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                .put("status", "FAIL").put("error", e.toString()).toString() + "\n")
            capture("FAIL-$id")
            throw e
        }
    }
    fun capture(name: String) {
        device.takeScreenshot(File(out, "$name.png"))
        device.dumpWindowHierarchy(File(out, "$name.xml"))
    }
    fun launch() {
        shell("am start -W -n $pkg/com.poyal.yeobaek.MainActivity")
        await("App launch did not reach the library or resume prompt", 45_000) { exists("nav_folder") || exists("resume_accept") }
        device.waitForIdle()
    }
    fun reset() {
        require(pkg in setOf("com.poyal.yeobaek", "com.poyal.yeobaek.debug", "com.poyal.yeobaek.benchmark"))
        require(shell("getprop ro.kernel.qemu") == "1")
        // Host must have checked the exact name; the guest name is checked as well when exposed.
        val avd = shell("getprop ro.boot.qemu.avd_name").ifBlank { shell("getprop ro.kernel.qemu.avd_name") }
            .ifBlank { args.getString("verifiedAvd").orEmpty() }
        require(avd in setOf("yeobaek-release-040", "yeobaek-release-api30-040", "yeobaek-soak-040")) { "Protected AVD: $avd" }
        check(shell("pm clear $pkg").contains("Success"))
        shell("pm clear com.android.documentsui")
        launch()
        click("nav_settings"); click("settings_general"); click("theme_$theme"); click("page_back"); click("nav_folder")
    }
    fun find(tag: String, container: String = "library_list", direction: Direction = Direction.DOWN): UiObject2 =
        findElement(By.res(tag), tag, container, direction)
    fun findText(value: String, container: String, direction: Direction = Direction.DOWN): UiObject2 =
        findElement(By.text(value), "text:$value", container, direction)
    private fun findElement(selector: BySelector, tag: String, container: String, direction: Direction): UiObject2 {
        // Accessibility becomes available before a bottom sheet finishes opening. A scroll
        // injected during that transition can drag the sheet instead of its content.
        var containerBounds = node(container).visibleBounds
        await("Container is still moving: $container", 10_000) {
            SystemClock.sleep(250)
            val current = node(container).visibleBounds
            (current == containerBounds).also { containerBounds = current }
        }
        fun visible(): UiObject2? {
            try {
                val item = device.findObject(selector) ?: return null
                val bounds = item.visibleBounds; val area = node(container).visibleBounds
                val minimum = if (tag.endsWith("_more")) (48 * instrumentation.context.resources.displayMetrics.density).toInt()
                    else if (tag.startsWith("file_") || tag.startsWith("folder_") || tag.startsWith("recent_")) 60 else 12
                return item.takeIf { bounds.height() >= minimum && bounds.top >= area.top && bounds.bottom <= area.bottom }
            } catch (_: StaleObjectException) { return null }
        }
        fun stable(): UiObject2? {
            try {
                var bounds = visible()?.visibleBounds ?: return null
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(250)
                    val item = visible() ?: return null
                    if (item.visibleBounds == bounds) return item
                    bounds = item.visibleBounds
                }
                return null
            } catch (_: StaleObjectException) { return null }
        }
        for (travel in listOf(direction, if (direction == Direction.DOWN) Direction.UP else Direction.DOWN)) {
            var previous = ""
            var unchanged = 0
            for (attempt in 0 until 140) {
                stable()?.let { return it }
                val area = node(container).visibleBounds
                if (attempt % 10 == 0) File(out, "scroll.ndjson").appendText(JSONObject()
                    .put("target", tag).put("container", container).put("direction", travel.name)
                    .put("attempt", attempt).put("bounds", area.toShortString())
                    .put("visible", runCatching { node(container).findObjects(By.text(Pattern.compile(".+"))).take(5).joinToString { it.text } }.getOrDefault("<layout updating>"))
                    .toString() + "\n")
                val from = if (travel == Direction.DOWN) .8f else .2f
                // Keep the gesture inside the visible list. Insets and nested Compose scrolls can
                // make UiObject2.scroll's edge gesture land in the system navigation area.
                val fromPoint = Point(area.left + area.width() / 3, area.top + (area.height() * from).toInt())
                val toPoint = Point(fromPoint.x, area.top + (area.height() * (1 - from)).toInt())
                if (tag.endsWith("_more")) {
                    // Hold at the end before lifting: a tap during a fling only stops scrolling
                    // and must not be mistaken for activating the next-results button.
                    device.swipe(arrayOf(fromPoint, toPoint, toPoint), 40)
                } else device.swipe(fromPoint.x, fromPoint.y, toPoint.x, toPoint.y, 50)
                device.waitForIdle(); SystemClock.sleep(250)
                stable()?.let { return it }
                // Compose can return false despite having moved; verify visible content instead.
                val visible = runCatching { node(container).children.joinToString { "${it.text}:${it.resourceName}:${it.visibleBounds}" } }.getOrDefault("")
                unchanged = if (visible.isNotEmpty() && visible == previous) unchanged + 1 else 0
                previous = visible
                if (unchanged >= 3) break
            }
        }
        error("Missing $tag in $container")
    }
    private fun systemClick(selector: BySelector, optional: Boolean = false, waitMillis: Long = if (optional) 1000 else 5000): Boolean {
        repeat(8) {
            try {
                val target = device.wait(Until.findObject(selector), waitMillis) ?: return false
                target.click(); device.waitForIdle(); return true
            } catch (_: StaleObjectException) { SystemClock.sleep(200) }
        }
        error("Unstable document picker: $selector")
    }
    fun pickerFolder(folder: String = "YeobaekQA", navigateFromRoot: Boolean = false) {
        check(device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.(google\\.android|android)\\.documentsui"))), 20_000))
        SystemClock.sleep(500); device.waitForIdle()
        if (!navigateFromRoot && (device.hasObject(By.text("Files in $folder")) || device.hasObject(By.res(Pattern.compile(".*:id/breadcrumb_text")).text(folder)))) return
        if (!systemClick(By.desc("Show roots"), true)) systemClick(By.desc("표시 위치"), true)
        // Physical storage avoids the virtual Downloads root in ACTION_OPEN_DOCUMENT.
        val storage = device.wait(Until.findObject(By.textContains(android.os.Build.MODEL)), 3000)
        if (storage != null) {
            check(systemClick(By.textContains(android.os.Build.MODEL)))
            check(systemClick(By.text("Download")))
        } else {
            if (!systemClick(By.text("Downloads"), true)) systemClick(By.text("다운로드"), true)
            systemClick(By.text("Download"), true)
        }
        check(systemClick(By.text(folder))) { "Missing document folder: $folder" }
    }
    fun connect(folder: String = "YeobaekQA") {
        click("connect_folder"); pickerFolder(folder, navigateFromRoot = true)
        check(systemClick(By.res(Pattern.compile(".*:id/action_menu_select")), optional = true) ||
            systemClick(By.text("USE THIS FOLDER"), optional = true) ||
            systemClick(By.text("이 폴더 사용"), optional = true)) { "Folder selection action missing" }
        // DocumentsUI can replace the permission dialog's accessibility nodes while it opens.
        systemClick(By.res("android:id/button1"), waitMillis = 5000)
        node("folder_refresh")
    }
    fun open(file: String, kind: String) = step("open-$kind-$file") {
        find("file_$file").click(); ready(kind)
    }
    fun ready(kind: String) {
        node("${kind}_footer", 120_000)
        val loading = when (kind) { "reader" -> "layout_loading"; "epub" -> "epub_loading"; else -> "unused_loading" }
        check(device.wait(Until.gone(By.res(loading)), 120_000))
        // The footer remains accessible behind a closing modal sheet. Wait for the sheet
        // to leave before the next toolbar tap, otherwise that tap only hits its scrim.
        check(device.wait(Until.gone(By.res(Pattern.compile("sheet_.+"))), 20_000))
        assertFalse("Reader failed to prepare", exists("layout_retry") || exists("error_close"))
        device.waitForIdle()
    }
    fun close(kind: String) { chrome(kind); click("${kind}_back"); node("nav_folder") }
    fun chrome(kind: String) {
        if (!exists("${kind}_footer")) {
            val bounds = body(kind).visibleBounds
            device.click(bounds.centerX(), bounds.centerY()); node("${kind}_footer")
        }
    }
    fun percent(): Float = node("reader_percent").text.removeSuffix("%").toFloat()
    fun page(kind: String): Int = node("${kind}_page").text.substringBefore(" / ").toInt()
    fun moveText(fraction: Float, apply: Boolean = true) {
        chrome("reader"); click("reader_move")
        val bounds = node("move_slider").visibleBounds
        device.click(bounds.left + (bounds.width() * fraction).toInt(), bounds.centerY())
        click(if (apply) "move_apply" else "move_cancel")
        ready("reader")
    }
    fun movePage(kind: String, page: Int) {
        chrome(kind); click("${kind}_move")
        node("${kind}_page_input").text = page.toString()
        if (device.hasObject(By.pkg(Pattern.compile(".*inputmethod.*")))) device.pressBack()
        click("${kind}_move_apply"); ready(kind)
        await("$kind did not reach page $page") { this.page(kind) == page }
    }
    fun swipe(kind: String, forward: Boolean, horizontal: Boolean = false) {
        val bounds = body(kind).visibleBounds
        val start = if (forward) .75f else .25f
        if (horizontal) device.swipe(bounds.left + (bounds.width() * start).toInt(), bounds.centerY(),
            bounds.left + (bounds.width() * (1 - start)).toInt(), bounds.centerY(), 20)
        else device.swipe(bounds.centerX(), bounds.top + (bounds.height() * start).toInt(), bounds.centerX(),
            bounds.top + (bounds.height() * (1 - start)).toInt(), 20)
        device.waitForIdle()
    }
    fun settings(kind: String) {
        chrome(kind)
        click(if (kind == "reader") "reader_txt" else "${kind}_settings")
        if (kind != "epub") find("quick_details", "quick_options").click()
        node(if (kind == "reader") "txt_options" else "${kind}_options")
    }
    fun endSettings(kind: String) { click("page_back"); ready(kind) }
    fun killAndResume(kind: String, accept: Boolean = true) {
        chrome(kind)
        val before = shell("pidof $pkg")
        check(before.isNotBlank())
        device.pressHome()
        // Background save is asynchronous. Poll durable storage modification before termination in host tests;
        // this user journey gives onStop and the one-second periodic saver a full interval.
        SystemClock.sleep(1500)
        shell("am kill $pkg")
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (shell("pidof $pkg").isNotEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        var method = "am kill"
        if (shell("pidof $pkg").isNotEmpty()) { shell("am force-stop $pkg"); method = "am force-stop" }
        assertEquals("", shell("pidof $pkg"))
        launch(); node("resume_accept")
        val after = shell("pidof $pkg")
        assertTrue(after.isNotBlank() && before != after)
        File(out, "process-death.ndjson").appendText(JSONObject().put("kind", kind).put("before", before)
            .put("after", after).put("method", method).put("accepted", accept).toString() + "\n")
        click(if (accept) "resume_accept" else "resume_decline")
        if (accept) ready(kind) else node("library_list")
    }
    fun viewport(kind: String): IntArray {
        val body = body(kind).visibleBounds
        (device.findObject(By.res("${kind}_header")) ?: device.findObject(By.res("${kind}_back")))
            ?.visibleBounds?.let { body.top = maxOf(body.top, it.bottom + 8) }
        device.findObject(By.res("${kind}_footer"))?.visibleBounds?.let { body.bottom = minOf(body.bottom, it.top - 8) }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val cropped = Bitmap.createBitmap(screenshot, body.left, body.top, body.width(), body.height())
        val small = Bitmap.createScaledBitmap(cropped, 64, 64, true)
        val pixels = IntArray(4096); small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        small.recycle(); cropped.recycle(); screenshot.recycle()
        return pixels
    }
    fun assertViewportRestored(kind: String, before: IntArray) {
        await("$kind restored a different visible page", 20_000) {
            val after = viewport(kind)
            before.indices.count { i ->
                (0..2).sumOf { channel -> abs((before[i] shr (channel * 8) and 255) - (after[i] shr (channel * 8) and 255)) } < 45
            } >= before.size * .94
        }
    }
    fun settledViewport(kind: String): IntArray {
        var previous = viewport(kind)
        var stable = 0
        await("$kind never displayed a stable nonblank viewport", 30_000) {
            val current = viewport(kind)
            // A zoomed numbered fixture may contain only a few flat colors. Count
            // visible contrast against its dominant background, not distinct colors.
            val background = current.asIterable().groupingBy { it }.eachCount().maxBy { it.value }.key
            val contentPixels = current.count { pixel ->
                (0..2).sumOf { channel -> abs((pixel shr (channel * 8) and 255) - (background shr (channel * 8) and 255)) } > 60
            }
            stable = if (contentPixels >= current.size / 200 && current.contentEquals(previous)) stable + 1 else 0
            previous = current
            stable >= 2
        }
        return previous
    }
}
