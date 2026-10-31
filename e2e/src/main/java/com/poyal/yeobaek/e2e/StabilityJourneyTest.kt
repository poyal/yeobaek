package com.poyal.yeobaek.e2e

import android.os.SystemClock
import com.poyal.yeobaek.testing.JourneyDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** S13: each format has its own duration, reopen count and process/resource evidence. */
class StabilityJourneyTest {
    private val j = JourneyDevice()
    private val out = File(j.out, "stability-${System.currentTimeMillis()}").apply { mkdirs() }
    @Test fun textThirtyMinutesAndHundredReopens() = exercise("reader", "02-story.txt")
    @Test fun pdfThirtyMinutesAndHundredReopens() = exercise("pdf", "03-book.pdf")
    @Test fun imageThirtyMinutesAndHundredReopens() = exercise("image", "04-comic.cbz")
    @Test fun epubThirtyMinutesAndHundredReopens() = exercise("epub", "05-reflow.epub")
    private fun memory(kind: String, label: String) {
        File(out, "$kind-$label-memory.txt").writeText(j.shell("dumpsys meminfo ${j.pkg}"))
        File(out, "$kind-$label-processes.txt").writeText(j.shell("ps -A -o PID,NAME"))
        File(out, "$kind-$label-frames.txt").writeText(j.shell("dumpsys gfxinfo ${j.pkg}"))
    }
    private fun exercise(kind: String, file: String) {
        j.reset(); j.connect(); j.open(file, kind)
        val duration = (j.args.getString("soakMinutes")?.toLong() ?: 30L) * 60_000
        val repetitions = j.args.getString("repetitions")?.toInt() ?: 100
        require(duration > 0 && repetitions > 0)
        val pid = j.shell("pidof ${j.pkg}")
        memory(kind, "start"); j.shell("dumpsys gfxinfo ${j.pkg} reset")
        val start = SystemClock.elapsedRealtime()
        var turns = 0; var nextSample = 60_000L
        while (SystemClock.elapsedRealtime() - start < duration) {
            j.swipe(kind, turns++ % 8 < 4)
            if (j.exists("reading_end_dialog")) j.device.pressBack()
            if (turns % 20 == 0) {
                assertEquals("Unexpected app process replacement", pid, j.shell("pidof ${j.pkg}"))
                assertFalse(j.exists("error_close") || j.exists("layout_retry") || j.exists("image_retry"))
            }
            if (SystemClock.elapsedRealtime() - start >= nextSample) {
                memory(kind, "${nextSample / 60_000}min"); nextSample += 60_000
            }
        }
        val actualDuration = SystemClock.elapsedRealtime() - start
        memory(kind, "soak-end"); j.close(kind)
        repeat(repetitions) { index ->
            val opened = SystemClock.elapsedRealtime()
            j.open(file, kind); j.swipe(kind, index % 2 == 0)
            if (j.exists("reading_end_dialog")) j.device.pressBack()
            File(out, "$kind-reopen.ndjson").appendText(JSONObject().put("iteration", index + 1)
                .put("open_and_gesture_ms", SystemClock.elapsedRealtime() - opened).toString() + "\n")
            j.close(kind)
            if (index % 25 == 24) memory(kind, "reopen-${index + 1}")
        }
        File(out, "$kind-result.json").writeText(JSONObject().put("duration_ms", duration)
            .put("actual_soak_ms", actualDuration).put("gestures", turns)
            .put("reopens", repetitions).put("pid", pid).put("status", "PASS").toString(2))
    }
}
