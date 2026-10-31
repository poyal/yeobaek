package com.poyal.yeobaek

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.rendering.TextLayoutEngine
import com.poyal.yeobaek.rendering.TextBlock
import com.poyal.yeobaek.txt.TextDocument
import com.poyal.yeobaek.txt.TextStore
import com.poyal.yeobaek.ui.Serif
import kotlinx.coroutines.*
import org.junit.*
import java.io.File
import java.util.UUID

class ReaderEngineTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var measurer: TextMeasurer
    private fun setup() {
        compose.setContent { val m = rememberTextMeasurer(cacheSize = 0); SideEffect { measurer = m } }
        compose.waitForIdle()
    }
    private fun style(size: Int) = TextStyle(fontFamily = Serif, fontSize = size.sp, lineHeight = (size * 1.85f).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        lineBreak = LineBreak.Simple, hyphens = Hyphens.None, textDirection = TextDirection.ContentOrLtr)

    private fun withDocument(text: String, test: suspend (TextDocument) -> Unit) = runBlocking {
        setup()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "engine-navigation-${UUID.randomUUID()}")
        try {
            val doc = withContext(Dispatchers.IO) { TextStore(root).prepare({ text.byteInputStream() }) }
            withContext(Dispatchers.Main) { test(doc) }
        } finally { withContext(Dispatchers.IO) { root.deleteRecursively() } }
    }

    private fun assertContent(text: String, block: TextBlock) {
        Assert.assertTrue("A nonempty block must advance: ${block.start}..${block.end}", block.end > block.start)
        val source = text.substring(block.start.toInt(), block.end.toInt())
        Assert.assertEquals(if (block.end < text.length) source.removeSuffix("\n") else source, block.text)
    }

    @Test fun coldSeekWalksBackThroughEveryShortAndEmptyLine() {
        val text = (0 until 160).joinToString("\n\n\n") { "문장 $it" }
        withDocument(text) { doc ->
            val engine = TextLayoutEngine(doc, "cold-reverse", style(18), 624, measurer)
            val rows = engine.around(doc.length * 4 / 5)
            rows.zipWithNext().forEach { (a, b) -> Assert.assertEquals(a.end, b.start) }
            var end = rows.first().start
            var count = 0
            while (end > 0) {
                val previous = engine.previous(end)
                Assert.assertEquals("Reverse navigation must not skip text", end, previous.end)
                assertContent(text, previous)
                end = previous.start
                Assert.assertTrue("Reverse navigation must terminate", ++count <= text.length)
            }
            Assert.assertEquals(0L, end)
            Assert.assertTrue("Must cover more than one retained window", count > 24)
        }
    }

    @Test fun rapidDistantSeeksKeepContiguousRangesAfterCacheEvictionAndReopen() {
        val text = (0 until 5000).joinToString("\n") { "문장 $it · 한글과 English é 👩‍👩‍👧‍👦" }
        withDocument(text) { doc ->
            repeat(2) {
                val engine = TextLayoutEngine(doc, "reopened-navigation", style(18), 624, measurer)
                for (percent in listOf(80, 10, 100, 50, 0, 95, 25, 75, 5, 90)) {
                    val target = (doc.length * percent / 100).coerceAtMost(doc.length - 1)
                    val rows = engine.around(target)
                    Assert.assertTrue(rows.any { target >= it.start && target < it.end })
                    rows.forEach { assertContent(text, it) }
                    rows.zipWithNext().forEach { (a, b) -> Assert.assertEquals("seek=$percent reopen=$it", a.end, b.start) }
                    var end = rows.first().start
                    repeat(6) {
                        if (end > 0) {
                            val previous = engine.previous(end)
                            Assert.assertEquals(end, previous.end)
                            assertContent(text, previous)
                            end = previous.start
                        }
                    }
                }
            }
        }
    }

    @Test fun previousReachesItsRequestedEndWhenACheckpointHasAShorterCachedBlock() {
        val text = (0 until 200).joinToString("\n") { "문장 $it" }
        withDocument(text) { doc ->
            val engine = TextLayoutEngine(doc, "shortened-checkpoint", style(18), 624, measurer)
            val original = engine.block(0)
            val short = engine.block(0, text.indexOf('\n').toLong() + 1)
            Assert.assertTrue(short.end < original.end)
            val previous = engine.previous(original.end)
            Assert.assertEquals("An older checkpoint must not return a truncated predecessor", original.end, previous.end)
            assertContent(text, previous)
        }
    }

    @Test fun outOfRangeAndTinyDocumentSeeksKeepValidBoundaries() = withDocument("첫 줄\n끝") { doc ->
        val engine = TextLayoutEngine(doc, "tiny-boundaries", style(18), 624, measurer)
        for (target in listOf(Long.MIN_VALUE, -1L, 0L, doc.length - 1, doc.length, Long.MAX_VALUE)) {
            val rows = engine.around(target)
            Assert.assertEquals(0L, rows.first().start)
            Assert.assertEquals(doc.length, rows.last().end)
            rows.zipWithNext().forEach { (a, b) -> Assert.assertEquals(a.end, b.start) }
            rows.forEach { assertContent("첫 줄\n끝", it) }
        }
    }

    @Test fun segmentedLinesMatchWholeParagraphAndSeekUsesSameBoundary() = runBlocking {
        setup()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "engine-test-${UUID.randomUUID()}")
        try {
            val text = "한글과 영문 ABC, é와 👩‍👩‍👧‍👦를 읽습니다. 오늘은 조금 늦어도 괜찮겠네. ".repeat(150)
            val doc = withContext(Dispatchers.IO) { TextStore(root).prepare({ text.byteInputStream() }) }
            for (size in listOf(14, 18, 32)) withContext(Dispatchers.Main) {
                val engine = TextLayoutEngine(doc, "test-$size", style(size), 624, measurer)
                val full = measurer.measure(AnnotatedString(text), style(size), constraints = Constraints(maxWidth = 624), skipCache = true)
                var at = 0L; var line = 0
                while (at < doc.length) {
                    val block = engine.block(at)
                    Assert.assertEquals(text.substring(at.toInt(), block.end.toInt()), block.text)
                    for (local in 0 until block.layout.lineCount) {
                        Assert.assertEquals("블록 줄 경계 size=$size offset=$at", full.getLineStart(line++).toLong(), block.start + block.layout.getLineStart(local))
                    }
                    at = block.end
                }
                Assert.assertEquals(full.lineCount, line)
                val target = doc.length * 8 / 10
                val around = engine.around(target)
                val chosen = around.first { it.start <= target && it.end > target }
                val savedLine = chosen.start + chosen.layout.getLineStart(chosen.layout.getLineForOffset((target - chosen.start).toInt()))
                Assert.assertEquals(full.getLineStart(full.getLineForOffset(target.toInt())).toLong(), savedLine)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun newlinesRemainContinuousIncludingManyEmptyLines() = runBlocking {
        setup()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "engine-test-${UUID.randomUUID()}")
        try {
            val text = ("한 줄\n\n\n \t다음 줄\n".repeat(300)) + "마지막\n\n"
            val doc = withContext(Dispatchers.IO) { TextStore(root).prepare({ text.byteInputStream() }) }
            withContext(Dispatchers.Main) {
                val engine = TextLayoutEngine(doc, "newlines", style(18), 624, measurer)
                val full = measurer.measure(AnnotatedString(text), style(18), constraints = Constraints(maxWidth = 624), skipCache = true)
                var at = 0L; var line = 0
                while (at < doc.length) {
                    val block = engine.block(at)
                    val source = text.substring(at.toInt(), block.end.toInt())
                    Assert.assertEquals(if (block.end < doc.length) source.removeSuffix("\n") else source, block.text)
                    for (local in 0 until block.layout.lineCount) Assert.assertEquals(full.getLineStart(line++).toLong(), block.start + block.layout.getLineStart(local))
                    at = block.end
                }
                Assert.assertEquals(full.lineCount, line)
                val fresh = TextLayoutEngine(doc, "no-checkpoints", style(18), 624, measurer)
                val rows = fresh.around(doc.length * 8 / 10)
                rows.zipWithNext().forEach { (left, right) -> Assert.assertEquals("역방향 블록 중복/누락", left.end, right.start) }
                for (block in rows) for (local in 0 until block.layout.lineCount) {
                    val offset = block.start + block.layout.getLineStart(local)
                    Assert.assertEquals(offset, full.getLineStart(full.getLineForOffset(offset.toInt())).toLong())
                }
            }
        } finally { root.deleteRecursively() }
    }
}
