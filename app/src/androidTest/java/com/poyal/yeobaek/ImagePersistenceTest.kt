package com.poyal.yeobaek

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.image.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ImagePersistenceTest {
    @Test fun migrationKeepsTextPdfAndRejectsStaleImageWrites() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val name = "image-migration-${UUID.randomUUID()}"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrument.context.assets.open("com.poyal.yeobaek.data.LibraryDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i); val table = e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO books VALUES ('pdf','pdf-key','content://test/pdf','기존.pdf','폴더','',NULL,'old','hash',1000,12,'PDF',500)")
            old.execSQL("INSERT INTO pdf_positions VALUES ('pdf','old',321,0.1,0.2,2.0,0,1,2)")
            old.execSQL("INSERT INTO pdf_bookmarks VALUES ('mark','pdf','old',123,0.0,0.0,1.0,12)")
            old.version = 2
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_2_3).build()
        try {
            val dao = db.library()
            assertEquals(321, dao.pdfPosition("pdf", "old")!!.page)
            assertEquals(1, dao.pdfBookmarks("pdf").first().size)
            val book = Book("image", "image-key", "content://test/image", "만화.cbz", "폴더", version = "v1", format = "IMAGE_ARCHIVE", length = 10, pageCount = 10)
            val first = dao.activate(book)
            dao.saveImage(ImagePosition("image", "v1", "p9", 9, .1f, .3f, 2f, session = first, sequence = 2))
            dao.saveImage(ImagePosition("image", "v1", "p1", 1, session = first, sequence = 1))
            assertEquals(9, dao.imagePosition("image", "v1")!!.page)
            val second = dao.activate(book)
            dao.saveImage(ImagePosition("image", "v1", "p3", 3, session = second, sequence = 1))
            dao.saveImage(ImagePosition("image", "v1", "p7", 7, session = first, sequence = 3))
            assertEquals(3, dao.imagePosition("image", "v1")!!.page)
            dao.addImageBookmark(ImageBookmark("a", "image", "v1", "p3", 3, "3.jpg"))
            dao.addImageBookmark(ImageBookmark("b", "image", "v1", "p3", 3, "3.jpg"))
            assertEquals(1, dao.imageBookmarks("image").first().size)
            dao.forget("image")
            dao.saveImage(ImagePosition("image", "v1", "p1", 1, session = second, sequence = 2))
            assertNull(dao.imagePosition("image", "v1")); assertTrue(dao.imageBookmarks("image").first().isEmpty())
            assertEquals(321, dao.pdfPosition("pdf", "old")!!.page)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun imageResetIsIndependentFromPdfTextAndApp() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YeobaekApplication
        val prefs = app.preferences
        val image = prefs.image.first(); val pdf = prefs.pdf.first(); val txt = prefs.txt.first(); val general = prefs.app.first()
        try {
            prefs.setImage(ImagePreferences(mode = ImageMode.PAGED, spread = ImageSpread.LANDSCAPE, inverted = true))
            assertTrue(prefs.image.first().inverted)
            prefs.resetImage(); assertEquals(ImagePreferences(), prefs.image.first())
            assertEquals(pdf, prefs.pdf.first()); assertEquals(txt, prefs.txt.first()); assertEquals(general, prefs.app.first())
        } finally { prefs.setImage(image) }
    }
}
