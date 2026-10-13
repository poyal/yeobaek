package com.poyal.yeobaek

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.rendering.TextLayoutEngine
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
