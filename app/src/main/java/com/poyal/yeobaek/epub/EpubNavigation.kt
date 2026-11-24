package com.poyal.yeobaek.epub

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

/** Transient renderer state. It is never serialized as a reading position. */
data class EpubNavigationState(
    val resourceIndex: Int = -1,
    val scroll: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false,
    val moving: Boolean = false,
    val atEnd: Boolean = false,
    /** Top of the native end action, relative to the WebView's viewport height. */
    val endActionTop: Float? = null,
    val outlineIndex: Int = -1,
)

data class EpubOutlineEntry(val link: Link, val depth: Int, val title: String)

fun epubOutline(links: List<Link>, readingOrder: List<Link> = emptyList()): List<EpubOutlineEntry> = buildList {
    val namedResources = readingOrder.filterNot { it.title.isNullOrBlank() }
    fun appendLinks(entries: List<Link>, depth: Int) {
        for (link in entries) {
            val title = link.title?.takeIf { it.isNotBlank() }
                ?: namedResources.firstOrNull { it.url().isEquivalent(link.url()) }?.title
                ?: "본문 ${size + 1}"
            add(EpubOutlineEntry(link, depth, title))
            appendLinks(link.children, depth + 1)
        }
    }
    appendLinks(links, 0)
}

fun sameEpubResource(first: Url, second: Url): Boolean = first.removeFragment().isEquivalent(second.removeFragment())

/** A resource may contain several TOC anchors; the renderer refines this fallback while reading. */
fun epubOutlineIndex(outline: List<EpubOutlineEntry>, href: Url?): Int =
    if (href == null) -1 else outline.indexOfFirst { sameEpubResource(it.link.url(), href) }

fun epubChapterTitle(readingOrder: List<Link>, outline: List<EpubOutlineEntry>, index: Int): String {
    val resource = readingOrder.getOrNull(index) ?: return "본문"
    val entryIndex = epubOutlineIndex(outline, resource.url())
    return outline.getOrNull(entryIndex)?.title
        ?: resource.title?.takeIf { it.isNotBlank() } ?: "본문 ${index + 1}"
}
