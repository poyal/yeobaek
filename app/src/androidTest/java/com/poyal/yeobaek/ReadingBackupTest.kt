package com.poyal.yeobaek

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import android.content.ContextWrapper
import android.util.AtomicFile
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReadingBackupTest {
    @Test fun previewRejectsMalformedAndOversizedInputWithoutChangingRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val file = File(context.cacheDir, "backup-input-${System.nanoTime()}.json")
        try {
            val dao = db.library()
            val book = Book("saved", "source", "content://synthetic/keep", "keep.txt", "folder", version = "v1")
            dao.putBook(book); dao.importPosition(ReadingPosition("saved", "v1", 17))
            val service = ReadingBackups(context, dao, Preferences(context), ReaderSettingsStore(context))
            val empty = ReadingBackup(library = LibrarySnapshot(), settings = ReaderSettings(), theme = AppTheme.SYSTEM)
            for (version in listOf(1, 2)) {
                file.writeText(Json.encodeToString(empty.copy(version = version)))
                assertEquals(version, service.preview(android.net.Uri.fromFile(file)).backup.version)
            }
            for (bad in listOf("{", Json.encodeToString(empty.copy(version = 999)))) {
                file.writeText(bad)
                assertTrue(runCatching { service.preview(android.net.Uri.fromFile(file)) }.isFailure)
            }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(ReadingBackups.MAX_BYTES.toLong() + 1) }
            val oversized = runCatching { service.preview(android.net.Uri.fromFile(file)) }.exceptionOrNull()
            assertTrue(oversized is IllegalArgumentException && oversized.message.orEmpty().contains("32MiB"))
            assertEquals(book, dao.book("saved")); assertEquals(17L, dao.position("saved", "v1")!!.offset)
            assertNull(dao.session())
        } finally { file.delete(); db.close() }
    }
    @Test fun interruptedFourFormatRestoreReplaysJournalWithoutOverwritingCurrentRecords() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "backup-journal-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = dir }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val prefs = Preferences(base); val settings = ReaderSettingsStore(base)
        val oldSettings = settings.data.first(); val oldTheme = prefs.app.first().theme
        try {
            val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""
            val books = listOf("TXT", "PDF", "IMAGE_ARCHIVE", "EPUB").mapIndexed { i, format ->
                Book("b$i", "source$i", "content://synthetic/$i", "book$i", "folder", version = "v1", format = format)
            }
            val library = LibrarySnapshot(books = books,
                positions = listOf(ReadingPosition("b0", "v1", 40)), bookmarks = listOf(Bookmark("t", "b0", "v1", 40, "line", "", "")),
                pdfPositions = listOf(PdfPosition("b1", "v1", page = 3)), pdfBookmarks = listOf(PdfBookmark("p", "b1", "v1", 3)),
                imagePositions = listOf(ImagePosition("b2", "v1", "3.png", page = 2, segment = 1)),
                imageBookmarks = listOf(ImageBookmark("i", "b2", "v1", "3.png", 2, "right", segment = 1)),
                epubPositions = listOf(EpubPosition("b3", "v1", locator, .5)),
                epubBookmarks = listOf(EpubBookmark("e", "b3", "v1", "chapter", locator, "middle")))
            val backup = ReadingBackup(library = library, settings = ReaderSettings(overrides = mapOf(
                "book:b0:TXT" to mapOf("size" to "24", "line" to "1.5"), "book:b3:EPUB" to mapOf("mode" to "PAGED"))), theme = AppTheme.DARK)
            settings.update { ReaderSettings(overrides = mapOf("book:local:TXT" to mapOf("size" to "20"))) }
            prefs.setTheme(AppTheme.LIGHT)
            db.library().putBook(books[0].copy(id = "local"))
            db.library().importPosition(ReadingPosition("local", "v1", 90))
            // Model the durable boundary after Room commits, before DataStore writes.
            val journal = AtomicFile(File(dir, "reading-restore.json"))
            val stream = journal.startWrite()
            stream.write(Json.encodeToString(backup).toByteArray()); journal.finishWrite(stream)
            db.library().importMissing(library)
            val recovery = ReadingBackups(context, db.library(), prefs, settings)
            recovery.recover(); recovery.recover(); recovery.restore(backup)
            val restored = db.library().snapshot()
            assertEquals(4, restored.books.size); assertEquals(4, restored.positionCount); assertEquals(4, restored.bookmarkCount)
            assertEquals(90L, restored.positions.single().offset)
            assertEquals("local", restored.bookmarks.single().bookId)
            assertEquals(1, restored.imagePositions.single().segment)
            assertEquals("20", settings.data.first().overrides.getValue("book:local:TXT")["size"])
            assertEquals("1.5", settings.data.first().overrides.getValue("book:local:TXT")["line"])
            assertEquals("PAGED", settings.data.first().overrides.getValue("book:b3:EPUB")["mode"])
            assertEquals(AppTheme.LIGHT, prefs.app.first().theme)
            assertNull(db.library().session()); assertFalse(journal.baseFile.exists())
        } finally {
            settings.update { oldSettings }; prefs.setTheme(oldTheme); db.close(); dir.deleteRecursively()
        }
    }
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
