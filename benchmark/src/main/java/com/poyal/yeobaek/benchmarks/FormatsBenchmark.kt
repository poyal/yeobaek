@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
package com.poyal.yeobaek.benchmarks

import android.os.SystemClock
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import com.poyal.yeobaek.testing.JourneyDevice
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/** Optimized/profileable app, real SAF and input, identical workload for baseline/candidate. */
class FormatsBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val j = JourneyDevice("com.poyal.yeobaek.benchmark")
    private val samples get() = j.args.getString("iterations")?.toInt() ?: 30
    private val rounds get() = j.args.getString("scrollIterations")?.toInt() ?: 10
    private val seconds get() = j.args.getString("durationSeconds")?.toLong() ?: 30L
    private val compilation = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
    private val fixtures = listOf("reader" to "02-story.txt", "pdf" to "03-book.pdf",
        "image" to "04-comic.cbz", "epub" to "05-reflow.epub")
    private fun observe(metric: String, action: () -> Unit) {
        val start = SystemClock.elapsedRealtimeNanos(); action()
        File(j.out, "latency.ndjson").appendText(JSONObject().put("metric", metric)
            .put("ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
            .put("compilation", "Full (speed)")
            .put("observation", "UI readiness including automation overhead").toString() + "\n")
    }
    private fun prepareUiLatencyWorkload(folder: String = "YeobaekQA") {
        j.reset(); j.connect(folder)
        // These UI observations run outside measureRepeated. Explicitly fix ART's
        // state instead of inheriting whichever benchmark happened to run before them.
        j.shell("am force-stop ${j.pkg}")
        val compiled = j.shell("cmd package compile -f -m speed ${j.pkg}")
        check(compiled.lineSequence().any { it.trim() == "Success" }) { "Full compilation failed: $compiled" }
        j.launch(); j.node("folder_refresh")
    }

    @Test fun coldProcessStartup() {
        j.reset(); j.connect()
        benchmark.measureRepeated(j.pkg, listOf(StartupTimingMetric()), compilation,
            startupMode = StartupMode.COLD, iterations = samples,
            setupBlock = { pressHome() }) {
            startActivityAndWait(); j.node("folder_refresh")
        }
    }
    @Test fun cachedOpenFourFormats() {
        j.reset(); j.connect()
        // Prepare each cache once. A process restart below is never labelled cold filesystem I/O.
        fixtures.forEach { (kind, file) -> j.open(file, kind); j.close(kind) }
        benchmark.measureRepeated(j.pkg, listOf(FrameTimingMetric()), compilation, iterations = samples,
            setupBlock = { startActivityAndWait(); j.node("library_list") }) {
            for ((kind, file) in fixtures) {
                j.find("file_$file")
                val start = SystemClock.elapsedRealtimeNanos()
                j.open(file, kind)
                j.settledViewport(kind)
                // UI observable readiness includes accessibility/automation overhead; record as such.
                File(j.out, "latency.ndjson").appendText(JSONObject().put("metric", "cached_open_$kind")
                    .put("ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                    .put("compilation", "Partial (BaselineProfile Require)")
                    .put("observation", "UI readiness including automation overhead").toString() + "\n")
                j.close(kind)
            }
        }
    }
    @Test fun scrollText() = scroll("reader", "01-short.txt")
    @Test fun scrollPdf() = scroll("pdf", "03-book.pdf")
    @Test fun scrollImages() = scroll("image", "04-comic.cbz")
    @Test fun scrollEpub() = scroll("epub", "05-reflow.epub")
    @Test fun seekAndSearchThreeFormats() {
        prepareUiLatencyWorkload()
        for ((kind, file) in fixtures.filter { it.first != "image" }) {
            j.open(file, kind)
            repeat(samples) { iteration ->
                observe("navigate_$kind") {
                    when (kind) {
                        "reader" -> {
                            val target = if (iteration % 2 == 0) .3f else .7f
                            j.moveText(target); j.await("TXT seek") { kotlin.math.abs(j.percent() - target * 100) < 5 }
                        }
                        "pdf" -> j.movePage(kind, if (iteration % 2 == 0) 30 else 80)
                        else -> {
                            j.click("epub_contents"); j.click("epub_chapter_${if (iteration % 2 == 0) 0 else 1}"); j.ready(kind)
                            j.await("EPUB chapter") { j.node("epub_current_chapter").text.startsWith(if (iteration % 2 == 0) "1장" else "2장") }
                        }
                    }
                    j.settledViewport(kind)
                }
                val prefix = if (kind == "reader") "search" else "${kind}_search"
                j.click("${kind}_search")
                j.node("${prefix}_input").text = when (kind) { "reader" -> "needle"; "pdf" -> "Searchable"; else -> "아침" }
                observe("search_$kind") {
                    j.click("${prefix}_submit")
                    j.await("Search results", 90_000) { !j.exists("${prefix}_cancel") && j.exists("${prefix}_next") && j.node("${prefix}_next").isEnabled }
                }
                j.click("${prefix}_return"); j.ready(kind)
            }
            j.close(kind)
        }
    }
    @Test fun imageSeekAndTextLayout() {
        prepareUiLatencyWorkload(); j.open("04-comic.cbz", "image")
        repeat(samples) { iteration -> observe("navigate_image") {
            j.movePage("image", if (iteration % 2 == 0) 5 else 18); j.settledViewport("image")
        } }
        j.close("image"); j.open("02-story.txt", "reader"); j.moveText(.5f)
        repeat(samples) { iteration ->
            j.click("reader_txt")
            observe("txt_font_change_until_reader_ready") {
                j.click(if (iteration % 2 == 0) "font_larger" else "font_smaller")
                j.click("sheet_close"); j.ready("reader"); j.settledViewport("reader")
                j.await("Font change lost anchor") { j.percent() in 45f..55f }
            }
        }
    }
    @Test fun listThousandFiles() {
        prepareUiLatencyWorkload("Yeobaek")
        repeat(samples) {
            j.find("folder_천개 폴더")
            observe("list_1000_files") {
                j.click("folder_천개 폴더")
                j.await("Thousand-file folder did not load") {
                    j.device.hasObject(By.res(Pattern.compile("file_책 \\d+\\.txt")))
                }
                j.find("file_책 0000.txt", direction = Direction.UP)
            }
            j.click("folder_up")
        }
    }
    private fun scroll(kind: String, file: String) {
        j.reset(); j.connect()
        benchmark.measureRepeated(j.pkg, listOf(FrameTimingMetric()), compilation, iterations = rounds,
            setupBlock = {
                startActivityAndWait()
                if (j.exists("resume_accept")) { j.click("resume_accept"); j.ready(kind) }
                else if (!j.exists("${kind}_footer")) j.open(file, kind)
                if (kind == "reader") j.moveText(.4f)
                if (kind == "pdf" || kind == "image") j.movePage(kind, 6)
                if (kind == "epub" && j.exists("epub_previous") && j.node("epub_previous").isEnabled) j.click("epub_previous")
                j.settledViewport(kind)
            }) {
            val end = SystemClock.elapsedRealtime() + seconds * 1000
            var turn = 0
            // Paired gestures exercise forward and reverse without repeatedly hitting document ends.
            while (SystemClock.elapsedRealtime() < end) {
                val area = j.body(kind).visibleBounds
                val forward = if (kind == "reader") turn++ % 8 >= 4 else turn++ % 8 < 4
                device.swipe(area.centerX(), area.top + area.height() * (if (forward) 3 else 1) / 4,
                    area.centerX(), area.top + area.height() * (if (forward) 1 else 3) / 4, 18)
            }
            check(!j.exists("error_close") && !j.exists("layout_retry"))
        }
    }
}
