package com.poyal.yeobaek

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.poyal.yeobaek.data.TxtPreferences
import com.poyal.yeobaek.rendering.*
import com.poyal.yeobaek.ui.textStyle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextWindowTest {
    @get:Rule val ui = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var source: DelayedBlocks
    private var window by mutableStateOf(TextWindow("test", emptyList()))

    private class DelayedBlocks(val blocks: List<TextBlock>) : TextBlockSource {
        override val length = blocks.last().end
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun block(start: Long): TextBlock { started.complete(Unit); release.await(); return blocks.first { it.start == start } }
        override suspend fun previous(end: Long): TextBlock { started.complete(Unit); release.await(); return blocks.first { it.end == end } }
    }

    private fun setup(first: Int = 1) {
        ui.setContent {
            val measurer = rememberTextMeasurer()
            val style = TxtPreferences().textStyle()
            val all = remember {
                (0 until 80).map { i ->
                    val text = ("문단 $i · 한글 Aa 123\n").repeat(12)
                    TextBlock(i * 1000L, (i + 1) * 1000L, text,
                        measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = 600)))
                }
            }
            list = rememberLazyListState(first)
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { source = DelayedBlocks(all); window = TextWindow("test", all.subList(10, 34)) }
            LazyColumn(Modifier.width(300.dp).height(120.dp), state = list) {
                items(window.blocks, key = window::itemKey) { BasicText(it.text, style = style) }
            }
        }
        ui.waitUntil(10000) { window.viewport(list) != null }
    }

    private fun move(index: Int, pixels: Int = 17) {
        lateinit var job: Job
        ui.runOnIdle { job = scope.launch { list.scrollToItem(index, pixels) } }
        ui.waitUntil(10000) { job.isCompleted }
        ui.waitForIdle()
    }

    @Test fun delayedPrependCannotEvictTheSentenceReachedWhileLoading() {
        setup()
        lateinit var pending: Job
        ui.runOnIdle {
            val base = window
            pending = scope.launch { base.prefetch(source, { base.viewport(list) }, { window === base })?.let { window = it } }
        }
        ui.waitUntil(10000) { source.started.isCompleted }
        // The user reverses direction while disk/layout work is suspended.
        move(23)
        val before = ui.runOnIdle { window.viewport(list)!! }
        ui.runOnIdle { source.release.complete(Unit) }
        ui.waitUntil(10000) { pending.isCompleted }
        ui.waitForIdle()
        val after = ui.runOnIdle { window.viewport(list)!! }
        Log.i("TextWindowTest", "delayed prepend: before=${before.block.start}/${before.pixels} after=${after.block.start}/${after.pixels}")
        assertEquals("A delayed prepend must retain the currently visible sentence", before.block.start, after.block.start)
        assertEquals(before.pixels, after.pixels)
        assertTrue(window.blocks.size <= 24)
    }

    @Test fun delayedAppendCannotEvictTheSentenceReachedWhileLoading() {
        setup(first = 22)
        lateinit var pending: Job
        ui.runOnIdle {
            val base = window
            pending = scope.launch { base.prefetch(source, { base.viewport(list) }, { window === base })?.let { window = it } }
        }
        ui.waitUntil(10000) { source.started.isCompleted }
        move(0)
        val before = ui.runOnIdle { window.viewport(list)!! }
        ui.runOnIdle { source.release.complete(Unit) }
        ui.waitUntil(10000) { pending.isCompleted }
        ui.waitForIdle()
        val after = ui.runOnIdle { window.viewport(list)!! }
        assertEquals(before.block.start, after.block.start)
        assertEquals(before.pixels, after.pixels)
        assertTrue(window.blocks.size <= 24)
    }

    @Test fun prependPreservesPixelPositionAndRejectsTheOldLayout() {
        setup()
        move(1, 39)
        val before = ui.runOnIdle { window.viewport(list)!! }
        lateinit var pending: Job
        ui.runOnIdle {
            source.release.complete(Unit)
            val base = window
            pending = scope.launch {
                window = base.prefetch(source, { base.viewport(list) }, { window === base })!!
                // Same item count, different keys at those indices: do not save a false anchor.
                assertNull(window.viewport(list))
            }
        }
        ui.waitUntil(10000) { pending.isCompleted }
        ui.waitForIdle()
        val after = ui.runOnIdle { window.viewport(list)!! }
        assertEquals(before.block.start, after.block.start)
        assertEquals(before.pixels, after.pixels)
        assertEquals(before.anchor, after.anchor)
        assertEquals(before.fraction, after.fraction, .001f)
    }

    @Test fun seekInvalidatesDelayedPrefetchAndOldMeasurements() {
        setup()
        lateinit var pending: Job
        ui.runOnIdle {
            val base = window
            pending = scope.launch {
                assertNull(base.prefetch(source, { base.viewport(list) }, { window === base }))
            }
        }
        ui.waitUntil(10000) { source.started.isCompleted }
        ui.runOnIdle {
            // A seek can reuse offsets and item count, but belongs to a new generation.
            window = TextWindow("seek-2", window.blocks)
            assertNull(window.viewport(list))
            list.requestScrollToItem(10, 27)
            source.release.complete(Unit)
        }
        ui.waitUntil(10000) { pending.isCompleted }
        ui.waitForIdle()
        ui.runOnIdle {
            assertEquals("seek-2", window.generation)
            assertEquals(20000L, window.viewport(list)!!.block.start)
            assertEquals(27, window.viewport(list)!!.pixels)
        }
    }
}
