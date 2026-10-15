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

class PdfPersistenceTest {
    @Test fun pdfResetKeepsTextAndGeneralPreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
        val prefs = context.preferences
        val pdf = prefs.pdf.first(); val txt = prefs.txt.first(); val general = prefs.app.first()
        try {
            prefs.setPdfFit(PdfFit.PAGE); prefs.setPdfSpacing(24f); prefs.setPdfInverted(true); prefs.setPdfCanvas(PdfCanvas.DARK)
            prefs.resetPdf()
            assertEquals(PdfPreferences(), prefs.pdf.first())
            assertEquals(txt, prefs.txt.first()); assertEquals(general, prefs.app.first())
        } finally { prefs.setPdfFit(pdf.fit); prefs.setPdfSpacing(pdf.spacing); prefs.setPdfInverted(pdf.inverted); prefs.setPdfCanvas(pdf.canvas) }
    }

    @Test fun migrationPreservesTextAndPdfWritesRejectStaleSessions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "pdf-migration-${UUID.randomUUID()}"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("com.poyal.yeobaek.data.LibraryDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO books VALUES ('txt','key','content://test/txt','기존.txt','폴더','UTF-8',NULL,'old','hash',1000,12)")
            old.execSQL("INSERT INTO positions VALUES ('txt','old',321,'before','after',1,2,'layout',0.25,0,1)")
            old.execSQL("INSERT INTO bookmarks VALUES ('mark','txt','old',123,'excerpt','before','after',12,1)")
            old.execSQL("INSERT INTO session VALUES (1,'txt',1)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4).build()
        try {
            val dao = db.library()
            assertEquals("TXT", dao.book("txt")!!.format)
            assertEquals(321L, dao.position("txt", "old")!!.offset)
            assertEquals(1, dao.allBookmarks("txt").size)
            val book = Book("pdf", "pdf-key", "content://test/pdf", "문서.pdf", "폴더", version = "v1", format = "PDF", pageCount = 500)
            val first = dao.activate(book)
            dao.savePdf(PdfPosition("pdf", "v1", 499, .1f, .4f, 2f, session = first, sequence = 2))
            dao.savePdf(PdfPosition("pdf", "v1", 0, session = first, sequence = 1))
            assertEquals(499, dao.pdfPosition("pdf", "v1")!!.page)
            val second = dao.activate(book)
            dao.savePdf(PdfPosition("pdf", "v1", 10, session = second, sequence = 1))
            dao.savePdf(PdfPosition("pdf", "v1", 20, session = first, sequence = 3))
            assertEquals(10, dao.pdfPosition("pdf", "v1")!!.page)
            dao.addPdfBookmark(PdfBookmark("a", "pdf", "v1", 10))
            dao.addPdfBookmark(PdfBookmark("b", "pdf", "v1", 10))
            assertEquals(1, dao.pdfBookmarks("pdf").first().size)
            dao.forget("pdf")
            dao.savePdf(PdfPosition("pdf", "v1", 30, session = second, sequence = 2))
            assertNull(dao.pdfPosition("pdf", "v1"))
            assertTrue(dao.pdfBookmarks("pdf").first().isEmpty())
            assertEquals(321L, dao.position("txt", "old")!!.offset)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
