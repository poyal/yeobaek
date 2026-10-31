package com.poyal.yeobaek.rendering

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import com.poyal.yeobaek.txt.TextDocument
import com.poyal.yeobaek.txt.TextFailure
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.util.LinkedHashMap

data class TextBlock(val start: Long, val end: Long, val text: String, val layout: TextLayoutResult)

/** All measurement is main-thread confined; file IO is dispatched. Only a small visible window is retained. */
class TextLayoutEngine(val doc: TextDocument, val key: String, private val style: TextStyle, private val width: Int, private val measurer: TextMeasurer) : TextBlockSource {
    override val length get() = doc.length
    private val checkpoints = File(doc.directory, "layout-$key.bin")
    private val cache = object : LinkedHashMap<Long, TextBlock>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TextBlock>?) = size > 32
    }
    private fun measure(text: String): TextLayoutResult {
        android.os.Trace.beginSection("MeasureTextBlock")
        try { return measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = width.coerceAtLeast(1)), skipCache = true) }
        finally { android.os.Trace.endSection() }
    }

    override suspend fun block(start: Long): TextBlock = block(start, doc.length)
    suspend fun block(start: Long, limit: Long): TextBlock {
        cache[start]?.takeIf { it.end <= limit }?.let { return it }
        currentCoroutineContext().ensureActive()
        val raw = withContext(Dispatchers.IO) { doc.read(start, minOf(4096L, limit - start).toInt()) }
        if (raw.isEmpty()) return TextBlock(start, start, "", measure(""))
        var count: Int
        val naturalEnd = raw.lastIndexOf('\n', minOf(raw.length - 1, 2047))
        if (naturalEnd >= 0) count = naturalEnd + 1
        else if (start + raw.length == limit && raw.length <= 2048) count = raw.length
        else {
            val layout = measure(raw)
            val line = layout.getLineForOffset(minOf(2048, raw.length - 1))
            count = if (line > 0) layout.getLineStart(line) else layout.getLineEnd(0)
            if (count <= 0 || count >= raw.length && start + raw.length < doc.length)
                throw TextFailure("하나의 글자 묶음이 너무 길어 안전하게 표시할 수 없습니다.")
            val boundary = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(raw) }
            if (!boundary.isBoundary(count)) {
                // Splitting a shaped grapheme is never an acceptable fallback.
                count = if (line > 1) layout.getLineStart(line - 1) else 0
                if (count <= 0 || !boundary.isBoundary(count)) throw TextFailure("너무 긴 결합 문자를 표시할 수 없습니다.")
            }
        }
        val proposed = measure(raw.substring(0, count).removeSuffix("\n"))
        if (proposed.lineCount > 64) count = proposed.getLineStart(64)
        val source = raw.substring(0, count)
        // A terminal LF belongs to this block; the next row supplies its line advance.
        val display = if (start + count < doc.length) source.removeSuffix("\n") else source
        val result = TextBlock(start, start + count, display, measure(display))
        cache[start] = result
        withContext(Dispatchers.IO) {
            RandomAccessFile(checkpoints, "rw").use { f ->
                if (f.length() % 16 != 0L) f.setLength(f.length() / 16 * 16)
                if (f.length() < 4L * 1024 * 1024) { f.seek(f.length()); f.writeLong(start); f.writeLong(result.end) }
            }
        }
        yield()
        return result
    }
    private suspend fun paragraphStart(offset: Long): Long = withContext(Dispatchers.IO) {
        var at = offset.coerceIn(0, doc.length)
        while (at > 0) {
            ensureActive()
            val begin = (at - 8192).coerceAtLeast(0)
            val text = doc.read(begin, (at - begin).toInt())
            val lf = text.lastIndexOf('\n')
            if (lf >= 0) return@withContext begin + lf + 1
            at = begin
        }
        0L
    }
    private suspend fun checkpoint(anchor: Long, exactEnd: Boolean = false): Long? = withContext(Dispatchers.IO) {
        if (!checkpoints.exists()) return@withContext null
        var found: Long? = null
        RandomAccessFile(checkpoints, "r").use { f ->
            while (f.filePointer + 16 <= f.length()) {
                ensureActive()
                val start = f.readLong(); val end = f.readLong()
                if (exactEnd && end == anchor) return@withContext start
                if (!exactEnd && start <= anchor && end > anchor) found = start
            }
        }
        found
    }
    suspend fun around(anchor: Long): List<TextBlock> {
        val target = anchor.coerceIn(0, (doc.length - 1).coerceAtLeast(0))
        var start = checkpoint(target) ?: paragraphStart(target)
        var current = block(start)
        while (current.end <= target && current.end < doc.length) { current = block(current.end) }
        val rows = mutableListOf(current)
        repeat(2) { if (rows.first().start > 0) rows.add(0, previous(rows.first().start)) }
        repeat(5) { if (rows.last().end < doc.length) rows.add(block(rows.last().end)) }
        return rows
    }
    override suspend fun previous(end: Long): TextBlock {
        // A later bounded read may have cached a shorter block at this checkpoint.
        // Treat the checkpoint as a starting point, then reach the exact boundary.
        val start = checkpoint(end, true) ?: paragraphStart((end - 1).coerceAtLeast(0))
        var current = block(start, end)
        while (current.end < end) current = block(current.end, end)
        return current
    }
}
