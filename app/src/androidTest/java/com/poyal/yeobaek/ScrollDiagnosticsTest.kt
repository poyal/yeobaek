package com.poyal.yeobaek

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.diagnostics.ScrollTouchTrace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class ScrollDiagnosticsTest {
    @Test fun touchTimestampsPointerIdsAndTerminalSampleAreExported() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
        val diagnostics = app.scrollDiagnostics
        val trace = ScrollTouchTrace(diagnostics)
        val id = UUID.randomUUID().toString()
        val down = SystemClock.uptimeMillis() - 500
        fun event(action: Int, time: Long, y: Float) {
            MotionEvent.obtain(down, time, action, 20f, y, 0).also { trace.touch(it, id); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, down, 600f)
        repeat(40) { event(MotionEvent.ACTION_MOVE, down + it * 10 + 1, 600f - it * 10) }
        event(MotionEvent.ACTION_UP, down + 420, 205f)
        val out = ByteArrayOutputStream()
        diagnostics.export(out)
        val text = out.toString("UTF-8")
        val session = text.lineSequence().filter { it.contains(id) }.toList()
        assertTrue(session.any { it.contains("action=ACTION_DOWN") })
        assertTrue(session.any { it.contains("action=ACTION_UP") && it.contains("y=205") })
        assertTrue(session.any { it.contains("raw_touch_tail") && it.contains("${down + 420}[id=0") })
        assertTrue(session.all { Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}(Z|[+-]\\d\\d:\\d\\d) uptime=").containsMatchIn(it) })
        assertTrue(text.contains("128 MiB"))
    }
}
