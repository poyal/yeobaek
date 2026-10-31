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
import androidx.compose.ui.semantics.getOrNull
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

    private fun withReader(text: String, initialProgress: Float = 0f, test: (Long) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
        val root = File(app.cacheDir, "reader-scroll-${UUID.randomUUID()}")
        val store = ViewModelStore()
        try {
            val doc = withContext(Dispatchers.IO) { TextStore(root).prepare({ text.byteInputStream() }) }
            ui.runOnUiThread { vm = AppViewModel(app); store.put("test", vm) }
            val initial = ReadingPosition("test", doc.version, (doc.length * initialProgress).toLong())
            val book = OpenBook(Book("test", "test", "content://test/book", "테스트.txt", "", version = doc.version, length = doc.length), doc, initial)
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
        assertTrue("Scrolling must produce measured positions: count=${observed.size}, offset=${offset()}", observed.size > 20)
        observed.zipWithNext().forEach { (a, b) ->
            val direction = if (a.first == b.first) b.second.compareTo(a.second) else b.first.compareTo(a.first)
            assertTrue("Unexpected sentence reversal: $a -> $b, scroll=$pixels", if (pixels > 0) direction >= 0 else direction <= 0)
        }
    }

    private fun generation() = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString()?.substringBeforeLast(':')

    private fun seekTo(target: Long) {
        val before = ui.runOnIdle { generation() }
        ui.runOnIdle { vm.seek(target) }
        ui.waitUntil(30000) {
            generation() != before && ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty()
        }
        ui.waitForIdle()
        ui.onNodeWithTag("layout_retry").assertDoesNotExist()
    }

    private fun scrollToBeginning() {
        lateinit var job: Job
        ui.runOnIdle {
            frames.clear()
            job = scope.launch {
                repeat(800) {
                    list.scrollBy(-1400f)
                    val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (first?.key.toString().substringAfterLast(':') == "0" && !list.canScrollBackward) return@launch
                    delay(20)
                }
            }
        }
        ui.waitUntil(30000) { job.isCompleted }
        assertEquals("Reverse scrolling must reach the real document beginning", 0L, offset())
        ui.runOnIdle {
            assertFalse(list.canScrollBackward)
            frames.zipWithNext().forEach { (a, b) ->
                assertTrue("Unexpected reversal while scrolling to the beginning: $a -> $b",
                    b.first < a.first || b.first == a.first && b.second <= a.second)
            }
        }
        ui.onNodeWithTag("layout_retry").assertDoesNotExist()
    }

    private fun scrollToEnd(length: Long) {
        lateinit var job: Job
        ui.runOnIdle {
            job = scope.launch {
                repeat(800) {
                    list.scrollBy(1400f)
                    delay(20)
                }
            }
        }
        ui.waitUntil(30000) {
            job.isCompleted || !list.canScrollForward &&
                ui.onAllNodesWithText("마지막 문장 END", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.runOnIdle { job.cancel() }
        ui.onNodeWithText("마지막 문장 END", substring = true).assertExists()
        assertTrue("The final viewport must be near the document end", offset() > length / 2)
        ui.runOnIdle { assertFalse(list.canScrollForward) }
        ui.onNodeWithTag("layout_retry").assertDoesNotExist()
    }

    private fun event(action: Int, down: Long, time: Long, atX: Float, atY: Float) {
        SystemClock.sleep((time - SystemClock.uptimeMillis()).coerceAtLeast(0))
        ui.runOnUiThread {
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { this.x = atX; this.y = atY; pressure = 1f; size = 1f }
            val touch = MotionEvent.obtain(down, time, action, 1, arrayOf(pointer), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { readerView.dispatchTouchEvent(touch) } finally { touch.recycle() }
        }
    }

    @Test fun hiddenMenusLeaveTextAcrossTheWholeViewport() = withReader(
        (0 until 3000).joinToString("\n") { "문장 $it · 위아래 메뉴를 숨겨도 본문은 화면 끝까지 이어집니다." }
    ) { length ->
        ui.runOnIdle { vm.seek(length / 2) }
        ui.waitUntil(30_000) {
            ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty() &&
                list.layoutInfo.visibleItemsInfo.any { (it.key as String).substringAfterLast(':').toLong() > length / 3 }
        }
        val before = offset()
        val bounds = ui.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("reader_body").performTouchInput { click() }
        ui.onNodeWithTag("reader_footer").assertDoesNotExist()
        assertEquals(before, offset())
        assertEquals(bounds, ui.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot)
        val text = ui.onAllNodes(SemanticsMatcher("Text block") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("text_") == true
        }).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("Text must reach the top of the viewport", text.minOf { it.top } <= bounds.top + 1)
        assertTrue("Text must reach the bottom of the viewport", text.maxOf { it.bottom } >= bounds.bottom - 1)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reader-viewport").apply { mkdirs() }
        File(out, "txt-hidden.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun reportedSwipeHeldStillBeforeReleaseDoesNotFlingBackwards() = withReader(
        (0 until 3000).joinToString("\n") { "문장 $it · 움직인 뒤 멈추고 손을 떼어도 읽던 자리를 유지합니다." }
    ) { length ->
        // Start in the middle so an erroneous reverse fling cannot be hidden by the top edge.
        ui.runOnIdle { vm.seek(length / 2) }
        ui.waitUntil(30000) {
            ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty() &&
                list.layoutInfo.visibleItemsInfo.any { (it.key as String).substringAfterLast(':').toLong() > length / 3 }
        }
        ui.waitForIdle()
        val points = InstrumentationRegistry.getInstrumentation().context.assets.open("scroll/swipe-hold-125.csv")
            .bufferedReader().use { it.readLines().map { line ->
                val (time, y) = line.split(',')
                time.toLong() to y.toFloat()
            } }
        val body = ui.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot
        val scale = body.height / 3000f
        val startY = body.center.y + 222f * scale
        val down = SystemClock.uptimeMillis()
        points.dropLast(1).forEachIndexed { index, (time, y) ->
            event(if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                down, down + time, body.center.x, startY + (y - points.first().second) * scale)
        }
        lateinit var stopped: Pair<Int, Int>
        ui.runOnUiThread { stopped = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        val (time, y) = points.last()
        event(MotionEvent.ACTION_UP, down, down + time, body.center.x, startY + (y - points.first().second) * scale)
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(stopped, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
        val out = ByteArrayOutputStream()
        runBlocking { vm.scrollDiagnostics.export(out) }
        val fling = out.toString("UTF-8").lineSequence().last { it.contains("fling_start session=$session") }
        assertTrue("The stopped swipe must suppress even a bogus velocity: $fling", fling.contains("suppressed=true"))
        assertTrue("Release history must reach the fling callback: $fling", fling.contains("releaseDurationMs=100"))
    }

    @Test fun sentencesStayContinuousAcrossManyForwardAndBackwardWindowChanges() = withReader(
        (0 until 15000).joinToString("\n") { "문장 $it · 책을 읽으면서 천천히 다음 이야기를 따라갑니다. Aa 123" }
    ) {
        scroll(1400f, 160)
        assertTrue("Must cross the 24-block retention limit", offset() > 50000)
        scroll(-1400f, 120)
        ui.onNodeWithTag("layout_retry").assertDoesNotExist()
    }

    @Test fun seekingIntoUnreadShortLinesCanScrollBackToTheBeginning() = withReader(
        (0 until 600).joinToString("\n") { "문장 $it" }
    ) { length ->
        seekTo(length * 4 / 5)
        assertTrue(offset() > length / 2)
        scrollToBeginning()
    }

    @Test fun seekingPastEmptyLinesCanScrollBackToTheBeginning() = withReader(
        (0 until 180).joinToString("\n\n\n") { "줄 $it" }
    ) { length ->
        seekTo(length * 3 / 4)
        scrollToBeginning()
    }

    @Test fun restoredUnreadPositionCanScrollBackToTheBeginning() = withReader(
        (0 until 600).joinToString("\n") { "복원 $it" }, initialProgress = .8f
    ) { length ->
        assertTrue(offset() > length / 2)
        scrollToBeginning()
    }

    @Test fun endSeekCanScrollBackThroughUnreadTextAndReturnToTheEnd() = withReader(
        (0 until 400).joinToString("\n") { "문장 $it" } + "\n마지막 문장 END"
    ) { length ->
        seekTo(length)
        ui.runOnIdle { assertFalse(list.canScrollForward) }
        scrollToBeginning()
        scrollToEnd(length)
    }

    @Test fun repeatedSeeksAndDirectionChangesDoNotLeaveWindowBoundaries() = withReader(
        (0 until 300).joinToString("\n") { "문장 $it" } + "\n마지막 문장 END"
    ) { length ->
        for (percent in listOf(80, 25, 95, 50)) {
            seekTo(length * percent / 100)
            scrollToBeginning()
            scrollToEnd(length)
        }
    }

    @Test fun latestRapidSeekWinsAndBothDirectionsRemainAvailable() = withReader(
        (0 until 600).joinToString("\n") { "문장 $it" } + "\n마지막 문장 END"
    ) { length ->
        val before = ui.runOnIdle { generation() }
        val target = length * 3 / 4
        ui.runOnIdle {
            for (at in listOf(length, 0, length / 2, length / 4, target)) vm.seek(at)
        }
        ui.waitUntil(30000) {
            generation() != before && ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Only the last seek may be displayed: target=$target actual=${offset()}", offset() in (target - 20)..target)
        scrollToBeginning()
        scrollToEnd(length)
    }

    @Test fun fontAndMarginChangesAfterSeekKeepReverseScrollingAvailable() = withReader(
        (0 until 500).joinToString("\n") { "문장 $it" }
    ) { length ->
        seekTo(length * 4 / 5)
        val before = offset()
        val oldGeneration = ui.runOnIdle { generation() }
        ui.runOnIdle { prefs = prefs.copy(font = TxtFont.SYSTEM_MONOSPACE, size = 12f, line = 1.2f, margin = 24f) }
        ui.waitUntil(30000) {
            generation() != oldGeneration && ui.onAllNodesWithTag("layout_loading").fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Relayout must retain the sentence", offset() in (before - 20)..before)
        scrollToBeginning()
    }

    @Test fun seekingInsideALongParagraphAllowsReverseScrollingAcrossBlocks() = withReader(
        "한글 English é 👩‍👩‍👧‍👦 123 · 긴 문단의 줄 경계를 확인합니다. ".repeat(300)
    ) { length ->
        seekTo(length * 4 / 5)
        scrollToBeginning()
    }

    @Test fun touchSwipesAfterSeekingCrossTheShortBlockRetentionLimit() = withReader(
        (0 until 300).joinToString("\n") { "문장 $it" }
    ) { length ->
        seekTo(length * 4 / 5)
        repeat(35) {
            ui.onNodeWithTag("reader_body").performTouchInput {
                swipe(start = center.copy(y = height * .25f), end = center.copy(y = height * .75f), durationMillis = 250)
            }
            ui.waitForIdle()
        }
        assertTrue("Touch swipes must continue well beyond the initial 24 blocks: ${offset()}", offset() < length / 3)
        scrollToBeginning()
    }

    @Test fun stoppingAFlingWithTheReportedNearStationaryTouchDoesNotRestartIt() = withReader(
        (0 until 3000).joinToString("\n") { "문장 $it · 터치로 관성 스크롤을 멈춥니다. 다음 문장을 읽습니다." }
    ) {
        val body = ui.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot
        val x = body.center.x
        val y = body.center.y
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
