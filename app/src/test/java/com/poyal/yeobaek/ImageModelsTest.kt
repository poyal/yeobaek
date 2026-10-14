package com.poyal.yeobaek

import com.poyal.yeobaek.image.*
import org.junit.Assert.*
import org.junit.Test

class ImageModelsTest {
    private fun page(id: Int, wide: Boolean = false) = ImagePage("$id", "$id.jpg", "$id.jpg", width = if (wide) 2000 else 1000, height = 1400, measured = true)
    @Test fun spreadsRespectCoverWidePagesAndOddTail() {
        val pages = (0..6).map { page(it, it == 3) }
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6)), imageSpreads(pages, true, true))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3), listOf(4, 5), listOf(6)), imageSpreads(pages, true, false))
        assertEquals(pages.indices.map { listOf(it) }, imageSpreads(pages, false, false))
    }
    @Test fun locatorsFollowStableImageKeysAndRejectInvalidCoordinates() {
        val pages = listOf(page(9), page(1), page(2))
        val p = ImageLocator(0, "2", Float.NaN, 10f, Float.POSITIVE_INFINITY).constrained(pages)
        assertEquals(2, p.page); assertEquals("2", p.key); assertEquals(0f, p.x); assertEquals(1f, p.y); assertEquals(1f, p.zoom)
        assertEquals(2, ImageLocator(99, "deleted").constrained(pages).page)
        assertEquals(1f, ImageLocator(completed = true).progress(100))
    }
    @Test fun unsafePathsAndMetadataAreNeverExtractedByName() {
        for (path in listOf("../page.jpg", "/page.jpg", "C:\\page.jpg", "a/../../page.jpg", "a\u0000.jpg", "__MACOSX/._page.jpg", "._page.jpg"))
            assertNull(path, ImageFormats.safePath(path))
        assertEquals("권1/001.jpg", ImageFormats.safePath("./권1\\001.jpg"))
        assertTrue(ImageFormats.image("긴 이름.JPEG")); assertTrue(ImageFormats.archive("책.CB7"))
    }
    @Test fun splitAndEncryptedRarHeadersAreRejectedBeforePartialListing() {
        val signature = byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0x01,0x00)
        val normal = signature + ByteArray(4) + byteArrayOf(3,1,0,4)
        checkArchiveHeader(normal)
        for (header in listOf(signature + ByteArray(4) + byteArrayOf(3,1,0,1), signature + ByteArray(4) + byteArrayOf(3,4,0,0))) {
            try { checkArchiveHeader(header); fail("unsupported archive accepted") } catch (_: ImageFailure) { }
        }
    }
}
