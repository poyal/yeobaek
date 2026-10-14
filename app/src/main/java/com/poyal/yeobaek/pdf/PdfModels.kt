package com.poyal.yeobaek.pdf

import com.poyal.yeobaek.data.Book
import com.poyal.yeobaek.data.PdfPosition
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class PdfPageSize(val width: Int, val height: Int)
data class PdfLocator(val page: Int = 0, val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f, val completed: Boolean = false) {
    fun constrained(count: Int) = copy(page = page.coerceIn(0, (count - 1).coerceAtLeast(0)),
        x = x.finite(0f).coerceIn(0f, 1f), y = y.finite(0f).coerceIn(0f, 1f), zoom = zoom.finite(1f).coerceIn(1f, 5f))
    fun progress(count: Int): Float = if (count <= 0) 0f else if (completed) 1f else ((page + y) / count).coerceIn(0f, 1f)
}
private fun Float.finite(default: Float) = if (isFinite()) this else default
fun PdfPosition.locator() = PdfLocator(page, x, y, zoom, completed)
data class OpenPdf(val book: Book, val document: PdfDocumentHandle, val initial: PdfLocator, val token: String = UUID.randomUUID().toString())
data class PdfSeek(val locator: PdfLocator, val serial: Long = System.nanoTime())
data class PdfMatch(val page: Int, val start: Int, val count: Int, val excerpt: String = "")
data class PdfTextRange(val page: Int, val start: Int, val count: Int, val text: String, val rects: List<PdfRect>)
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PdfSearchBatch(val matches: List<PdfMatch>, val next: Int, val textCount: Int)

/** Fixed-size disk index: even repeated words in a large document do not grow the UI heap. */
class PdfSearchIndex(val file: File) {
    var count = 0L; private set
    @Synchronized fun append(hits: List<PdfMatch>) {
        RandomAccessFile(file, "rw").use { out ->
            out.seek(count * 12)
            for (hit in hits) { out.writeInt(hit.page); out.writeInt(hit.start); out.writeInt(hit.count) }
            count += hits.size
        }
    }
    @Synchronized fun at(index: Long): PdfMatch {
        require(index in 0 until count)
        return RandomAccessFile(file, "r").use { it.seek(index * 12); PdfMatch(it.readInt(), it.readInt(), it.readInt()) }
    }
    @Synchronized fun page(start: Long, limit: Int = 100): List<PdfMatch> =
        (start until minOf(count, start + limit)).map(::at)
}

internal object PdfProtocol {
    const val OPEN = 1; const val SIZES = 2; const val RENDER = 3; const val SEARCH = 4
    const val RANGE = 5; const val SELECT = 6; const val CLOSE = 7
}

/** Applied only to page pixels, never to controls, selections, or stored document bytes. */
val PDF_INVERSION = floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)
