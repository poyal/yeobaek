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

data class EpubOutlineEntry(val link: Link, val depth: Int)

fun epubOutline(links: List<Link>, depth: Int = 0): List<EpubOutlineEntry> =
    links.flatMap { listOf(EpubOutlineEntry(it, depth)) + epubOutline(it.children, depth + 1) }

fun sameEpubResource(first: Url, second: Url): Boolean = first.removeFragment().isEquivalent(second.removeFragment())

/** A resource may contain several TOC anchors; the renderer refines this fallback while reading. */
fun epubOutlineIndex(outline: List<EpubOutlineEntry>, href: Url?): Int =
    if (href == null) -1 else outline.indexOfFirst { sameEpubResource(it.link.url(), href) }

fun epubChapterTitle(readingOrder: List<Link>, outline: List<EpubOutlineEntry>, index: Int): String {
    val resource = readingOrder.getOrNull(index) ?: return "본문"
    return outline.firstOrNull { sameEpubResource(it.link.url(), resource.url()) && !it.link.title.isNullOrBlank() }
        ?.link?.title ?: resource.title?.takeIf { it.isNotBlank() } ?: "${index + 1}번째 구간"
}
