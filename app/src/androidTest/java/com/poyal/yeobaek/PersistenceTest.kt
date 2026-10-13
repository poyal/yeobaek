package com.poyal.yeobaek

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersistenceTest {
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
