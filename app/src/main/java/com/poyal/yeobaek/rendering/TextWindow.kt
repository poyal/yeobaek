package com.poyal.yeobaek.rendering

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface TextBlockSource {
    val length: Long
    suspend fun block(start: Long): TextBlock
    suspend fun previous(end: Long): TextBlock
}

/** A measured item is identified by generation + source offset, never its sliding-window index. */
internal data class TextViewport(
    val block: TextBlock,
    val pixels: Int,
    val first: Int,
    val last: Int,
    val visibleStarts: List<Long>,
    val atEnd: Boolean,
) {
    val line get() = block.layout.getLineForVerticalPosition(pixels.toFloat())
    val anchor get() = block.start + block.layout.getLineStart(line)
    val fraction: Float get() {
        val top = block.layout.getLineTop(line)
        val height = block.layout.getLineBottom(line) - top
        return ((pixels - top) / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}

internal class TextWindow(val generation: String, val blocks: List<TextBlock>) {
    fun itemKey(block: TextBlock) = "$generation:${block.start}"

    /** A new data set can coexist with the previous layout until the next measure pass. */
    fun viewport(list: LazyListState): TextViewport? {
        val info = list.layoutInfo
        if (blocks.isEmpty() || info.totalItemsCount != blocks.size || info.visibleItemsInfo.isEmpty()) return null
        if (info.visibleItemsInfo.any { item -> blocks.getOrNull(item.index)?.let(::itemKey) != item.key }) return null
        val item = info.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex } ?: return null
        val block = blocks.firstOrNull { itemKey(it) == item.key } ?: return null
        return TextViewport(block, list.firstVisibleItemScrollOffset, item.index,
            info.visibleItemsInfo.last().index,
            info.visibleItemsInfo.map { blocks[it.index].start }, !list.canScrollForward)
    }

    /** Caller owns a single prefetch coroutine and cancels it on seek/layout/book changes. */
    suspend fun prefetch(source: TextBlockSource, current: () -> TextViewport?, isCurrent: () -> Boolean): TextWindow? {
        val initial = current() ?: return null
        val forward = initial.last >= blocks.lastIndex - 2 && blocks.last().end < source.length
        if (!forward && !(initial.first <= 2 && blocks.first().start > 0)) return null
        val more = mutableListOf<TextBlock>()
        if (forward) {
            var end = blocks.last().end
            repeat(4) { if (end < source.length) { val block = source.block(end); more += block; end = block.end } }
        } else {
            var start = blocks.first().start
            repeat(4) { if (start > 0) { val block = source.previous(start); more.add(0, block); start = block.start } }
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        // Disk IO and measurement suspend. The thumb/fling may have crossed the window
        // since the request began; trimming against initial.first can evict visible text.
        val latest = current() ?: return null
        val combined = if (forward) blocks + more else more + blocks
        val first = combined.indexOfFirst { it.start == latest.visibleStarts.first() }
        val last = combined.indexOfFirst { it.start == latest.visibleStarts.last() }
        if (first < 0 || last < first) return null
        val capacity = maxOf(24, last - first + 1)
        val maxStart = (combined.size - capacity).coerceAtLeast(0)
        val preferred = if (forward) minOf(maxStart, (first - 4).coerceAtLeast(0))
            else (last + 5 - capacity).coerceAtLeast(0)
        val start = preferred.coerceIn((last - capacity + 1).coerceAtLeast(0), minOf(first, maxStart))
        val retained = combined.subList(start, minOf(combined.size, start + capacity)).toList()
        return if (retained == blocks) null else TextWindow(generation, retained)
    }
}
