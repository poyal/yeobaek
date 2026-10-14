package com.poyal.yeobaek

import com.poyal.yeobaek.data.DocumentEntry
import com.poyal.yeobaek.data.naturalCompare
import org.junit.Assert.*
import org.junit.Test
import java.text.Collator
import java.util.Locale

class ReadingEndTest {
    private fun file(name: String, id: String = name, folder: Boolean = false) =
        DocumentEntry(id, "content://test/$id", name, folder, null, null, if (folder) "vnd.android.document/directory" else "")

    @Test fun nextUsesNaturalFolderOrderAcrossFormatsAndSkipsUnsupportedFiles() {
        val entries = listOf(file("10.cbz"), file("2.pdf"), file("1.txt"), file("1.png"), file("1.exe"))
            .sortedWith { a, b -> naturalCompare(a.name, b.name, Collator.getInstance(Locale.KOREAN)) }
        assertEquals("2.pdf", nextReadingEntry(entries, "1.txt") { false }?.name)
        assertEquals("10.cbz", nextReadingEntry(entries, "2.pdf") { false }?.name)
    }
    @Test fun noWrappingOrGuessingWhenCurrentEntryIsMissing() {
        val entries = listOf(file("1.txt"), file("2.txt"))
        assertNull(nextReadingEntry(entries, "2.txt") { false })
        assertNull(nextReadingEntry(entries, "removed.txt") { false })
    }
    @Test fun identityIsTheDocumentIdEvenWhenNamesAreEqual() {
        val entries = listOf(file("same.txt", "first"), file("same.txt", "second"))
        assertEquals("second", nextReadingEntry(entries, "first") { false }?.id)
    }
    @Test fun imageFoldersAreBooksButEmptyFoldersAndLooseImagePagesAreNotNextBooks() {
        val entries = listOf(file("Volume 1", folder = true), file("Empty", folder = true),
            file("Volume 2", folder = true), file("1.png"), file("Volume 3.cbz"))
        assertEquals("Volume 2", nextReadingEntry(entries, "Volume 1") { it.name == "Volume 2" }?.name)
        assertEquals("Volume 3.cbz", nextReadingEntry(entries, "Volume 2") { false }?.name)
    }
    @Test fun restoredEndAndSettingsChangesDoNotAnnounceUntilReaderAdvances() {
        val signal = ReadingEndSignal()
        assertFalse(signal.take(true, true, true))
        signal.advance(true)
        assertFalse(signal.take(false, true, true))
        signal.cancel()
        assertFalse(signal.take(true, true, true))
        signal.advance(false)
        assertFalse(signal.take(true, true, true))
    }
    @Test fun completionWaitsForBothRenderAndGestureAndConsumesTheIntent() {
        val signal = ReadingEndSignal()
        signal.advance(true)
        assertFalse(signal.take(true, false, true))
        assertFalse(signal.take(true, true, false))
        assertTrue(signal.take(true, true, true))
        assertFalse(signal.take(true, true, true))
    }
}
