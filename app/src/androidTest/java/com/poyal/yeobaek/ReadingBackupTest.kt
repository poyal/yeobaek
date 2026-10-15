package com.poyal.yeobaek

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadingBackupTest {
    @Test fun restoringForgottenAndRelinkedBooksKeepsCurrentSourceAndRestoresVisibility() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            val old = Book("book", "old-source", "content://old/a", "a.txt", "old", version = "v1", openedAt = 100)
            dao.putBook(old.copy(sourceKey = "new-source", uri = "content://new/a", openedAt = 0))
            dao.importMissing(LibrarySnapshot(books = listOf(old), positions = listOf(ReadingPosition("book", "v1", 50))))
            assertEquals("content://new/a", dao.book("book")!!.uri)
            assertEquals(100L, dao.book("book")!!.openedAt)
            assertEquals(50L, dao.position("book", "v1")!!.offset)
        } finally { db.close() }
    }
    @Test fun mergingTwicePreservesExistingPositionsAndRemapsBookIds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            val local = Book("local", "source", "content://test/a", "a.txt", "folder", version = "v1", length = 100, openedAt = 1)
            dao.putBook(local); dao.importPosition(ReadingPosition("local", "v1", 90))
            val incoming = LibrarySnapshot(books = listOf(local.copy(id = "imported")), positions = listOf(ReadingPosition("imported", "v1", 10)),
                bookmarks = listOf(Bookmark("mark", "imported", "v1", 20, "excerpt", "", "")),
                imagePositions = listOf(ImagePosition("imported", "v0", "p", segment = 1)))
            repeat(2) { assertEquals("local", dao.importMissing(incoming)["imported"]) }
            val merged = dao.snapshot()
            assertEquals(1, merged.books.size); assertEquals(90L, merged.positions.single().offset)
            assertEquals("local", merged.bookmarks.single().bookId); assertEquals(1, merged.imagePositions.single().segment)
            assertNull(dao.session())
        } finally { db.close() }
    }
}
