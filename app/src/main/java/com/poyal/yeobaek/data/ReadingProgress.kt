package com.poyal.yeobaek.data

import java.util.Locale

enum class ReadingStatus { UNREAD, READING, COMPLETED }

/** Presentation only: never convert page locators into TXT offsets or change Book.length. */
data class ReadingProgress(val status: ReadingStatus, val fraction: Double?) {
    val label: String get() = when {
        status == ReadingStatus.COMPLETED -> "100%"
        fraction == null -> "—"
        fraction <= 0.0 -> "0%"
        else -> String.format(Locale.US, "%.1f%%", (fraction * 100).coerceAtMost(99.9))
    }
    val description: String get() = when (status) {
        ReadingStatus.UNREAD -> "미독"
        ReadingStatus.COMPLETED -> "완독"
        ReadingStatus.READING -> if (fraction == null) "읽는 중 · 저장 위치 없음" else "읽는 중 · $label"
    }
    companion object {
        val Unread = ReadingProgress(ReadingStatus.UNREAD, 0.0)
        val Unknown = ReadingProgress(ReadingStatus.READING, null)
        fun at(fraction: Double?, completed: Boolean = false): ReadingProgress {
            if (completed) return ReadingProgress(ReadingStatus.COMPLETED, 1.0)
            val value = fraction?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            return if (value == 0.0) Unread else ReadingProgress(ReadingStatus.READING, value)
        }
        fun text(offset: Long, length: Long, completed: Boolean) = at(if (length > 0) offset.toDouble() / length else 0.0, completed && length > 0)
        fun page(page: Int, y: Float, count: Int?, completed: Boolean) =
            at(count?.takeIf { it > 0 }?.let { (page + y.toDouble()) / it }, completed)
    }
}

fun readingProgressByBook(books: List<Book>, txt: List<ReadingPosition>, pdf: List<PdfPosition>,
    images: List<ImagePosition>, epub: List<EpubPosition>): Map<String, ReadingProgress> {
    val texts = txt.associateBy { it.bookId to it.version }
    val pdfs = pdf.associateBy { it.bookId to it.version }
    val pictures = images.associateBy { it.bookId to it.version }
    val epubs = epub.associateBy { it.bookId to it.version }
    return books.associate { book ->
        val key = book.id to book.version
        val progress = when {
            book.format == "TXT" -> texts[key]?.let { ReadingProgress.text(it.offset, book.length, it.completed) }
            book.format == "PDF" -> pdfs[key]?.let { ReadingProgress.page(it.page, it.y, book.pageCount, it.completed) }
            book.format.startsWith("IMAGE_") -> pictures[key]?.let { ReadingProgress.page(it.page, it.y, book.pageCount, it.completed) }
            book.format == "EPUB" -> epubs[key]?.let { ReadingProgress.at(it.progress, it.completed) }
            else -> null
        }
        book.id to (progress ?: if (book.openedAt > 0) ReadingProgress.Unknown else ReadingProgress.Unread)
    }
}
