package com.poyal.yeobaek

import com.poyal.yeobaek.data.*
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressTest {
    @Test fun labelsDistinguishMissingUnreadAndCompletedWithoutRoundingToCompletion() {
        assertEquals("0%", ReadingProgress.Unread.label)
        assertEquals("—", ReadingProgress.Unknown.label)
        assertEquals("33.3%", ReadingProgress.at(1.0 / 3).label)
        assertEquals("99.9%", ReadingProgress.at(.99999).label)
        assertEquals("99.9%", ReadingProgress.at(1.0).label)
        assertEquals("100%", ReadingProgress.at(.2, true).label)
        assertEquals("—", ReadingProgress.at(Double.NaN).label)
        assertEquals("0%", ReadingProgress.text(0, 0, true).label)
        assertEquals(ReadingProgress.Unread, ReadingProgress.text(0, 100, false))
        assertEquals(ReadingProgress.Unread, ReadingProgress.page(0, 0f, 10, false))
        assertEquals(ReadingProgress.Unread, ReadingProgress.at(0.0))
        assertEquals(ReadingProgress.Unknown, ReadingProgress.page(1, .5f, null, false))
    }
    @Test fun formatsUseNativeCoordinatesAndIgnoreOldVersions() {
        fun book(id: String, format: String, length: Long) = Book(id, id, "content://$id", id, "folder", version = "v2", length = length, openedAt = 1, format = format, pageCount = 3)
        val books = listOf(book("txt", "TXT", 300), book("pdf", "PDF", 1), book("image", "IMAGE_ARCHIVE", 3), book("epub", "EPUB", 1), book("old", "TXT", 300))
        val result = readingProgressByBook(books,
            listOf(ReadingPosition("txt", "v2", 100), ReadingPosition("old", "v1", 300, completed = true)),
            listOf(PdfPosition("pdf", "v2", page = 1)),
            listOf(ImagePosition("image", "v2", pageKey = "1", page = 1)),
            listOf(EpubPosition("epub", "v2", "{}", progress = 1.0 / 3)))
        listOf("txt", "pdf", "image", "epub").forEach { assertEquals(it, "33.3%", result.getValue(it).label) }
        assertEquals(ReadingProgress.Unknown, result["old"])
        assertEquals(listOf(300L, 1L, 3L, 1L, 300L), books.map { it.length })
    }
    @Test fun returningToEarlierPositionReplacesCompletion() {
        assertEquals(ReadingStatus.COMPLETED, ReadingProgress.page(2, 0f, 3, true).status)
        assertEquals(ReadingStatus.READING, ReadingProgress.page(1, 0f, 3, false).status)
        assertEquals("33.3%", ReadingProgress.page(1, 0f, 3, false).label)
    }
}
