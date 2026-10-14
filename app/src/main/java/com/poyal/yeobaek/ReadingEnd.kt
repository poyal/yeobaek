package com.poyal.yeobaek

import com.poyal.yeobaek.data.DocumentEntry

data class ReadingEnd(
    val token: String,
    val loading: Boolean = true,
    val next: DocumentEntry? = null,
    val path: List<DocumentEntry> = emptyList(),
    val entries: List<DocumentEntry> = emptyList(),
    val error: String? = null,
)

/** Use the folder's existing sort order, without wrapping or replaying images in the same book. */
internal fun nextReadingEntry(
    entries: List<DocumentEntry>, currentId: String, containsImages: (DocumentEntry) -> Boolean,
): DocumentEntry? {
    val current = entries.indexOfFirst { it.id == currentId }
    if (current < 0) return null
    return entries.asSequence().drop(current + 1).firstOrNull {
        if (it.directory) containsImages(it) else it.txt || it.pdf || it.archive
    }
}

/** Require an intentional advance and a settled, rendered end, not merely a restored locator. */
internal class ReadingEndSignal {
    private var advancing = false
    fun advance(forward: Boolean) { advancing = forward }
    fun cancel() { advancing = false }
    fun take(completed: Boolean, settled: Boolean, rendered: Boolean): Boolean {
        if (!advancing || !completed || !settled || !rendered) return false
        advancing = false
        return true
    }
}
