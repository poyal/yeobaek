package com.poyal.yeobaek

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EpubPersistenceTest {
    @Test fun completedBookSurvivesRestoreButRealNavigationReturnsToReading() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val file = EpubFixtures.write(java.io.File(context.cacheDir, "epub-completion.epub"))
        val document = com.poyal.yeobaek.epub.EpubEngine(context).open(file)
        val first = document.publication.locatorFromLink(document.publication.readingOrder.first())!!
        val last = document.publication.locatorFromLink(document.publication.readingOrder.last())!!
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val errors = mutableListOf<String>()
        val controller = com.poyal.yeobaek.epub.EpubReaderController(scope, db.library(), context.cacheDir, errors::add)
        val book = Book("complete", "complete", "content://complete", "책.epub", "폴더", version = "v1", format = "EPUB")
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                controller.show(com.poyal.yeobaek.epub.OpenEpub(book, document, last, initialCompleted = true))
                controller.rendered()
            }
            kotlinx.coroutines.withTimeout(5000) { while (db.library().epubPosition(book.id, book.version) == null) kotlinx.coroutines.delay(20) }
            assertTrue(db.library().epubPosition(book.id, book.version)!!.completed)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                controller.restoring()
                controller.position(last.copy(title = "다시 배치된 제목"))
                controller.flush()
            }
            assertTrue(db.library().epubPosition(book.id, book.version)!!.completed)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                controller.userNavigation(); controller.position(first); controller.flush()
            }
            assertFalse(db.library().epubPosition(book.id, book.version)!!.completed)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { controller.close() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            db.close(); file.delete()
        }
    }
    @Test fun migrationFromFourPreservesExistingRecordsAndEpubWritesRejectStaleSessions() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val name = "epub-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(inst.context.assets.open("com.poyal.yeobaek.data.LibraryDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO books VALUES ('txt','key','content://test/txt','기존.txt','폴더','UTF-8',NULL,'old','hash',1000,12,'TXT',NULL)")
            old.execSQL("INSERT INTO positions VALUES ('txt','old',321,'before','after',1,2,'layout',0.25,0,1)")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_4_5).build()
        try {
            val dao = db.library()
            assertEquals(321L, dao.position("txt", "old")!!.offset)
            val book = Book("epub", "epub-source", "content://test/epub", "책.epub", "폴더", format = "EPUB", version = "v1")
            val at = """{"href":"chapter.xhtml","type":"application/xhtml+xml"}"""
            val first = dao.activate(book)
            dao.saveEpub(EpubPosition("epub", "v1", at, .5, session = first, sequence = 2))
            dao.saveEpub(EpubPosition("epub", "v1", at, .1, session = first, sequence = 1))
            assertEquals(.5, dao.epubPosition("epub", "v1")!!.progress, .001)
            val second = dao.activate(book)
            dao.saveEpub(EpubPosition("epub", "v1", at, .7, session = second, sequence = 1))
            dao.saveEpub(EpubPosition("epub", "v1", at, .2, session = first, sequence = 3))
            assertEquals(.7, dao.epubPosition("epub", "v1")!!.progress, .001)
            dao.addEpubBookmark(EpubBookmark("mark", "epub", "v1", "location", at, "문단"))
            dao.addEpubBookmark(EpubBookmark("duplicate", "epub", "v1", "location", at, "문단"))
            assertEquals(1, dao.epubBookmarks("epub").first().size)
            val incoming = LibrarySnapshot(books = listOf(book.copy(id = "incoming")),
                epubPositions = listOf(EpubPosition("incoming", "v1", at, .1)),
                epubBookmarks = listOf(EpubBookmark("second", "incoming", "v1", "other", at, "다른 문단")))
            repeat(2) { assertEquals("epub", dao.importMissing(incoming)["incoming"]) }
            assertEquals(.7, dao.epubPosition("epub", "v1")!!.progress, .001)
            assertEquals(2, dao.epubBookmarks("epub").first().size)
            val backup = ReadingBackup(library = dao.snapshot(), settings = ReaderSettings(), theme = AppTheme.SYSTEM)
            validateBackup(backup)
            dao.forget("epub")
            dao.saveEpub(EpubPosition("epub", "v1", at, .8, session = second, sequence = 2))
            assertNull(dao.epubPosition("epub", "v1")); assertTrue(dao.epubBookmarks("epub").first().isEmpty())
            assertEquals(321L, dao.position("txt", "old")!!.offset)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
