package com.poyal.yeobaek

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PersistenceTest {
    @Test fun recentBooksKeepOldEntriesAndReopenWithoutDuplicates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            val oldest = Book("1", "1", "content://test/1", "처음 읽은 책.txt", "서재", openedAt = 1420070400000L)
            db.withTransaction {
                dao.putBook(oldest)
                for (index in 2..1001) {
                    val id = index.toString()
                    dao.putBook(oldest.copy(id = id, sourceKey = id, uri = "content://test/$id", openedAt = oldest.openedAt + index))
                }
            }
            val original = dao.observeBooks().first()
            assertEquals(1001, original.size)
            assertEquals(oldest.id, original.last().id)
            dao.activate(oldest)
            val reopened = dao.observeBooks().first()
            assertEquals(original.size, reopened.size)
            assertEquals(oldest.id, reopened.first().id)
            dao.forget(oldest.id)
            assertEquals(original.dropLast(1), dao.observeBooks().first())
        } finally { db.close() }
    }

    @Test fun latePositionCannotResurrectDeletedRecordOrOverwriteNewSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            val book = Book("test", "provider:opaque-id", "content://test/book", "책.txt", "테스트", version = "v1", length = 10000)
            val first = dao.activate(book)
            dao.save(ReadingPosition(book.id, "v1", 100, session = first, sequence = 2))
            dao.save(ReadingPosition(book.id, "v1", 50, session = first, sequence = 1))
            assertEquals(100L, dao.position(book.id, "v1")!!.offset)
            val second = dao.activate(book)
            dao.consumeRevision(first)
            assertEquals(second, dao.session()!!.revision)
            assertEquals(book.id, dao.session()!!.bookId)
            dao.save(ReadingPosition(book.id, "v1", 200, session = second, sequence = 1))
            dao.save(ReadingPosition(book.id, "v1", 300, session = first, sequence = 3))
            assertEquals(200L, dao.position(book.id, "v1")!!.offset)
            dao.forget(book.id)
            dao.save(ReadingPosition(book.id, "v1", 400, session = second, sequence = 2))
            assertNull(dao.position(book.id, "v1")); assertNull(dao.session()!!.bookId)
            assertEquals(0, dao.book(book.id)!!.openedAt)
        } finally { db.close() }
    }
}
