package com.poyal.yeobaek

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.txt.TextStore
import com.poyal.yeobaek.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

class ReaderScrollTest {
    @get:Rule val ui = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var vm: AppViewModel
    private lateinit var readerView: View
    private lateinit var session: String
    private var prefs by mutableStateOf(TxtPreferences(size = 14f, line = 1.3f))
    private val frames = mutableListOf<Pair<Long, Int>>()

    private fun withReader(text: String, test: (Long) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
        val root = File(app.cacheDir, "reader-scroll-${UUID.randomUUID()}")
        val store = ViewModelStore()
        try {
            val doc = withContext(Dispatchers.IO) { TextStore(root).prepare({ text.byteInputStream() }) }
            ui.runOnUiThread { vm = AppViewModel(app); store.put("test", vm) }
            val book = OpenBook(Book("test", "test", "content://test/book", "테스트.txt", "", version = doc.version, length = doc.length), doc, null)
            session = book.token
            ui.setContent {
                readerView = LocalView.current
                list = rememberLazyListState()
                scope = rememberCoroutineScope()
                LaunchedEffect(list) {
                    snapshotFlow {
                        list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
                            ?.let { (it.key as String).substringAfterLast(':').toLong() to list.firstVisibleItemScrollOffset }
                    }.filterNotNull().collect { frames += it }
                }
                YeobaekTheme(AppTheme.LIGHT) { ReaderScreen(vm, book, prefs, list = list) }
            }
            ui.waitUntil(30000) { ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty() && list.layoutInfo.totalItemsCount > 0 }
            test(doc.length)
        } finally {
            ui.runOnUiThread { store.clear() }
            withContext(Dispatchers.IO) { root.deleteRecursively() }
        }
    }

    private fun offset(): Long {
        ui.waitForIdle()
        val (start, pixels) = ui.runOnIdle {
            val item = list.layoutInfo.visibleItemsInfo.first { it.index == list.firstVisibleItemIndex }
            (item.key as String).substringAfterLast(':').toLong() to list.firstVisibleItemScrollOffset
        }
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("text_$start").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return start + layouts.single().let { it.getLineStart(it.getLineForVerticalPosition(pixels.toFloat())) }
    }

    private fun scroll(pixels: Float, steps: Int) {
        lateinit var job: Job
        ui.runOnIdle { frames.clear(); job = scope.launch { repeat(steps) { list.scrollBy(pixels); delay(20) } } }
        ui.waitUntil(60000) { job.isCompleted }
        ui.waitForIdle()
        val observed = ui.runOnIdle { frames.toList() }
        assertTrue("Scrolling must produce measured positions", observed.size > 20)
        observed.zipWithNext().forEach { (a, b) ->
            val direction = if (a.first == b.first) b.second.compareTo(a.second) else b.first.compareTo(a.first)
            assertTrue("Unexpected sentence reversal: $a -> $b, scroll=$pixels", if (pixels > 0) direction >= 0 else direction <= 0)
        }
    }

    @Test fun sentencesStayContinuousAcrossManyForwardAndBackwardWindowChanges() = withReader(
        (0 until 15000).joinToString("\n") { "문장 $it · 책을 읽으면서 천천히 다음 이야기를 따라갑니다. Aa 123" }
    ) {
        scroll(1400f, 160)
        assertTrue("Must cross the 24-block retention limit", offset() > 50000)
        scroll(-1400f, 120)
        ui.onNodeWithTag("layout_retry").assertDoesNotExist()
    }

    @Test fun stoppingAFlingWithTheReportedNearStationaryTouchDoesNotRestartIt() = withReader(
        (0 until 3000).joinToString("\n") { "문장 $it · 터치로 관성 스크롤을 멈춥니다. 다음 문장을 읽습니다." }
    ) {
        val body = ui.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot
        val x = body.center.x
        val y = body.center.y
        fun event(action: Int, down: Long, time: Long, atX: Float, atY: Float) {
            SystemClock.sleep((time - SystemClock.uptimeMillis()).coerceAtLeast(0))
            ui.runOnUiThread {
                val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { this.x = atX; this.y = atY; pressure = 1f; size = 1f }
                val touch = MotionEvent.obtain(down, time, action, 1, arrayOf(pointer), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { readerView.dispatchTouchEvent(touch) } finally { touch.recycle() }
            }
        }
        val swipe = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, swipe, swipe, x, y + 200f)
        for (i in 1..8) event(MotionEvent.ACTION_MOVE, swipe, swipe + i * 12, x, y + 200f - i * 40f)
        event(MotionEvent.ACTION_UP, swipe, swipe + 104, x, y - 120f)
        val stop = maxOf(swipe + 160, SystemClock.uptimeMillis())
        event(MotionEvent.ACTION_DOWN, stop, stop, x, y)
        // Replay gesture 4342: stationary for 100 ms, then only 1 px vertically at lift-off.
        for (i in 1..13) event(MotionEvent.ACTION_MOVE, stop, stop + i * 8, x, y)
        event(MotionEvent.ACTION_MOVE, stop, stop + 108, x - 7f, y + 1f)
        event(MotionEvent.ACTION_MOVE, stop, stop + 117, x - 12f, y + 1f)
        lateinit var stopped: Pair<Int, Int>
        ui.runOnUiThread { stopped = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        event(MotionEvent.ACTION_UP, stop, stop + 125, x - 12f, y + 1f)
        ui.waitForIdle()
        ui.runOnIdle {
            assertEquals("Lifting a stopping touch must not restart scrolling", stopped,
                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }
        val out = ByteArrayOutputStream()
        runBlocking { vm.scrollDiagnostics.export(out) }
        val flings = out.toString("UTF-8").lineSequence()
            .filter { it.contains("fling_start session=$session") }.toList()
        assertTrue("The real swipe must keep its fling: $flings", flings.any { it.contains("suppressed=false") })
        assertTrue("The stopping touch must suppress the fling: $flings", flings.last().contains("suppressed=true"))
    }

    @Test fun fontChangesAndRapidSeeksKeepTheRequestedSentenceInALongParagraph() = withReader(
        "오늘은 한글과 English, 숫자 123을 함께 읽습니다. 문장 사이의 여백을 따라 이야기는 이어집니다. ".repeat(500)
    ) { length ->
        fun seek(target: Long) {
            ui.runOnIdle { vm.seek(target) }
            ui.waitUntil(30000) {
                ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty() &&
                    list.layoutInfo.visibleItemsInfo.any { (it.key as String).substringAfterLast(':').toLong() in (target - 4096)..target }
            }
            val actual = offset()
            assertTrue("seek target=$target actual=$actual", actual in (target - 100)..target)
        }
        seek(length / 2)
        for (font in TxtFont.entries) {
            val before = offset()
            ui.runOnIdle { prefs = prefs.copy(font = font, size = 20f) }
            ui.waitUntil(30000) { ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty() }
            val after = offset()
            assertTrue("font=$font before=$before after=$after", after in (before - 100)..before)
        }
        ui.runOnIdle { vm.seek(length / 4) }
        seek(length * 3 / 4)
    }
}
