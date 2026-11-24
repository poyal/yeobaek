package com.poyal.yeobaek

import com.poyal.yeobaek.epub.*
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

/** Readium URLs use Android's Uri implementation, so these run on the device. */
class EpubNavigationTest {
    private fun link(href: String, title: String? = null, children: List<Link> = emptyList()) =
        Link(href = Url(href)!!, title = title, children = children)

    @Test fun nestedOutlineDoesNotBecomeReadingOrderOrChangeChapterCount() {
        val order = listOf(link("cover.xhtml"), link("body.xhtml"), link("end.xhtml", "맺음말"))
        val outline = epubOutline(listOf(link("body.xhtml", "첫 장", listOf(
            link("body.xhtml#one", "첫 절"), link("body.xhtml#two", "둘째 절"))), link("end.xhtml", "맺음말")))
        assertEquals(listOf(0, 1, 1, 0), outline.map { it.depth })
        assertEquals("첫 장", epubChapterTitle(order, outline, 1))
        assertEquals("맺음말", epubChapterTitle(order, outline, 2))
        assertEquals("본문 1", epubChapterTitle(order, outline, 0))
        assertEquals(-1, epubOutlineIndex(outline, Url("cover.xhtml")))
        assertEquals(0, epubOutlineIndex(outline, Url("body.xhtml#two")))
    }

    @Test fun resourceComparisonKeepsDifferentFilesDistinctAndIgnoresOnlyFragments() {
        assertTrue(sameEpubResource(Url("EPUB/body.xhtml#one")!!, Url("EPUB/body.xhtml#two")!!))
        assertFalse(sameEpubResource(Url("EPUB/body.xhtml")!!, Url("Notes/body.xhtml")!!))
        val order = listOf(link("one.xhtml", "첫 장"), link("two.xhtml", "둘째 장"))
        val outline = epubOutline(order)
        assertEquals(1, epubOutlineIndex(outline, Url("two.xhtml#note")))
        assertEquals("둘째 장", epubChapterTitle(order, outline, 1))
        assertEquals(-1, epubOutlineIndex(outline, null))
    }

    @Test fun unnamedOutlineItemsShareTheHeaderTitleEvenWhenSpineAndOutlineIndexesDiffer() {
        val order = listOf(link("cover.xhtml"), link("intro.xhtml", "프롤로그"), link("part.xhtml"), link("end.xhtml"))
        val outline = epubOutline(listOf(link("intro.xhtml"), link("part.xhtml", " ", listOf(link("part.xhtml#note"))),
            link("end.xhtml", "부록")), order)
        assertEquals(listOf("프롤로그", "본문 2", "본문 3", "부록"), outline.map { it.title })
        assertEquals("본문 2", epubChapterTitle(order, outline, 2))
        assertEquals("부록", epubChapterTitle(order, outline, 3))
    }
}
