package com.poyal.yeobaek

import com.poyal.yeobaek.pdf.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfLocatorTest {
    @Test fun invalidAndOldPageLocatorsAreClamped() {
        assertEquals(PdfLocator(2, 0f, 1f, 1f), PdfLocator(100, Float.NaN, 8f, Float.NaN).constrained(3))
        assertEquals(.25f, PdfLocator(2, y = .5f).progress(10), .0001f)
        assertEquals(1f, PdfLocator(9, completed = true).progress(10), 0f)
    }
    @Test fun searchIndexRetainsResultsBeyondTheFirstPage() {
        val file = File.createTempFile("pdf-search", ".idx")
        try {
            val index = PdfSearchIndex(file)
            repeat(4) { page -> index.append((0 until 200).map { PdfMatch(page, it * 3, 2) }) }
            assertEquals(800L, index.count)
            assertEquals(PdfMatch(3, 597, 2), index.at(799))
            assertEquals(100, index.page(100).size)
            assertEquals(PdfMatch(0, 300, 2), index.page(100).first())
        } finally { file.delete() }
    }
}
