package com.poyal.yeobaek.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index(value = ["sourceKey"], unique = true)])
data class Book(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val uri: String,
    val name: String,
    val folder: String,
    val charset: String = "",
    val charsetOverride: String? = null,
    val version: String = "",
    val sourceHash: String = "",
    val length: Long = 0,
    val openedAt: Long = 0,
    @ColumnInfo(defaultValue = "'TXT'") val format: String = "TXT",
    val pageCount: Int? = null,
)
@Entity(tableName = "positions", primaryKeys = ["bookId", "version"])
data class ReadingPosition(
    val bookId: String, val version: String, val offset: Long,
    val before: String = "", val after: String = "",
    val session: Long = 0, val sequence: Long = 0,
    val layoutKey: String = "", val fraction: Float = 0f,
    val completed: Boolean = false, val locatorVersion: Int = 1,
)
@Entity(tableName = "bookmarks", indices = [Index(value = ["bookId", "version", "offset"], unique = true)])
data class Bookmark(
    @PrimaryKey val id: String,
    val bookId: String, val version: String, val offset: Long,
    val excerpt: String, val before: String, val after: String,
    val createdAt: Long = System.currentTimeMillis(), val locatorVersion: Int = 1,
)
@Entity(tableName = "session")
data class ReaderSession(@PrimaryKey val id: Int = 1, val bookId: String? = null, val revision: Long = 0)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM image_positions") abstract fun observeImagePositions(): Flow<List<ImagePosition>>
    @Query("SELECT * FROM image_positions WHERE bookId=:id AND version=:version") abstract suspend fun imagePosition(id: String, version: String): ImagePosition?
    @Upsert protected abstract suspend fun putImagePosition(position: ImagePosition)
    @Query("SELECT * FROM image_bookmarks WHERE bookId=:id ORDER BY createdAt DESC") abstract fun imageBookmarks(id: String): Flow<List<ImageBookmark>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addImageBookmark(mark: ImageBookmark)
    @Query("DELETE FROM image_bookmarks WHERE id=:id") abstract suspend fun deleteImageBookmark(id: String)
    @Query("DELETE FROM image_bookmarks WHERE bookId=:id") protected abstract suspend fun deleteImageBookmarks(id: String)
    @Query("DELETE FROM image_positions WHERE bookId=:id") protected abstract suspend fun deleteImagePositions(id: String)
    @Transaction
    open suspend fun saveImage(position: ImagePosition) {
        val active = session() ?: return
        if (active.revision != position.session || active.bookId != position.bookId) return
        val old = imagePosition(position.bookId, position.version)
        if (old != null && old.session == position.session && old.sequence >= position.sequence) return
        putImagePosition(position)
    }
    @Query("SELECT * FROM pdf_positions") abstract fun observePdfPositions(): Flow<List<PdfPosition>>
    @Query("SELECT * FROM pdf_positions WHERE bookId=:id AND version=:version") abstract suspend fun pdfPosition(id: String, version: String): PdfPosition?
    @Upsert protected abstract suspend fun putPdfPosition(position: PdfPosition)
    @Query("SELECT * FROM pdf_bookmarks WHERE bookId=:id ORDER BY createdAt DESC") abstract fun pdfBookmarks(id: String): Flow<List<PdfBookmark>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addPdfBookmark(mark: PdfBookmark)
    @Query("DELETE FROM pdf_bookmarks WHERE id=:id") abstract suspend fun deletePdfBookmark(id: String)
    @Query("DELETE FROM pdf_bookmarks WHERE bookId=:id") protected abstract suspend fun deletePdfBookmarks(id: String)
    @Query("DELETE FROM pdf_positions WHERE bookId=:id") protected abstract suspend fun deletePdfPositions(id: String)
    @Transaction
    open suspend fun savePdf(position: PdfPosition) {
        val active = session() ?: return
        if (active.revision != position.session || active.bookId != position.bookId) return
        val old = pdfPosition(position.bookId, position.version)
        if (old != null && old.session == position.session && old.sequence >= position.sequence) return
        putPdfPosition(position)
    }
    @Query("SELECT * FROM books WHERE openedAt > 0 ORDER BY openedAt DESC") abstract fun observeBooks(): Flow<List<Book>>
    @Query("SELECT * FROM books WHERE id=:id") abstract suspend fun book(id: String): Book?
    @Query("SELECT * FROM books WHERE sourceKey=:key") abstract suspend fun source(key: String): Book?
    @Upsert abstract suspend fun putBook(book: Book)
    @Query("SELECT * FROM positions WHERE bookId=:id AND version=:version") abstract suspend fun position(id: String, version: String): ReadingPosition?
    @Query("SELECT * FROM positions") abstract fun observePositions(): Flow<List<ReadingPosition>>
    @Upsert protected abstract suspend fun putPosition(position: ReadingPosition)
    @Query("SELECT * FROM session WHERE id=1") abstract suspend fun session(): ReaderSession?
    @Upsert abstract suspend fun putSession(session: ReaderSession)
    @Query("SELECT * FROM bookmarks WHERE bookId=:id ORDER BY createdAt DESC") abstract fun bookmarks(id: String): Flow<List<Bookmark>>
    @Query("SELECT * FROM bookmarks WHERE bookId=:id") abstract suspend fun allBookmarks(id: String): List<Bookmark>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addBookmark(mark: Bookmark)
    @Query("DELETE FROM bookmarks WHERE id=:id") abstract suspend fun deleteBookmark(id: String)
    @Query("DELETE FROM bookmarks WHERE bookId=:id") protected abstract suspend fun deleteBookmarks(id: String)
    @Query("DELETE FROM positions WHERE bookId=:id") protected abstract suspend fun deletePositions(id: String)
    @Query("UPDATE books SET openedAt=0 WHERE id=:id") protected abstract suspend fun forgetOpened(id: String)

    @Transaction
    open suspend fun activate(book: Book): Long {
        val revision = (session()?.revision ?: 0) + 1
        putBook(book.copy(openedAt = System.currentTimeMillis()))
        putSession(ReaderSession(bookId = book.id, revision = revision))
        return revision
    }
    @Transaction
    open suspend fun save(position: ReadingPosition) {
        val active = session() ?: return
        if (active.revision != position.session || active.bookId != position.bookId) return
        val old = position(position.bookId, position.version)
        if (old != null && old.session == position.session && old.sequence >= position.sequence) return
        putPosition(position)
    }
    @Transaction
    open suspend fun consumeCandidate() {
        val old = session()
        putSession(ReaderSession(revision = (old?.revision ?: 0) + 1))
    }
    @Transaction
    open suspend fun consumeRevision(revision: Long) {
        if (session()?.revision == revision) consumeCandidate()
    }
    @Transaction
    open suspend fun forget(id: String) {
        if (session()?.bookId == id) consumeCandidate()
        deleteBookmarks(id); deletePositions(id); deletePdfBookmarks(id); deletePdfPositions(id)
        deleteImageBookmarks(id); deleteImagePositions(id); forgetOpened(id)
    }
}

@Database(entities = [Book::class, ReadingPosition::class, Bookmark::class, ReaderSession::class, PdfPosition::class, PdfBookmark::class, ImagePosition::class, ImageBookmark::class], version = 3, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS image_positions (bookId TEXT NOT NULL, version TEXT NOT NULL, pageKey TEXT NOT NULL, page INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL, zoom REAL NOT NULL, completed INTEGER NOT NULL, session INTEGER NOT NULL, sequence INTEGER NOT NULL, PRIMARY KEY(bookId, version))")
                db.execSQL("CREATE TABLE IF NOT EXISTS image_bookmarks (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, version TEXT NOT NULL, pageKey TEXT NOT NULL, page INTEGER NOT NULL, name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, zoom REAL NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_image_bookmarks_bookId_version_pageKey ON image_bookmarks(bookId, version, pageKey)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'TXT'")
                db.execSQL("ALTER TABLE books ADD COLUMN pageCount INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS pdf_positions (bookId TEXT NOT NULL, version TEXT NOT NULL, page INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL, zoom REAL NOT NULL, completed INTEGER NOT NULL, session INTEGER NOT NULL, sequence INTEGER NOT NULL, PRIMARY KEY(bookId, version))")
                db.execSQL("CREATE TABLE IF NOT EXISTS pdf_bookmarks (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, version TEXT NOT NULL, page INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL, zoom REAL NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pdf_bookmarks_bookId_version_page ON pdf_bookmarks(bookId, version, page)")
            }
        }
    }
}
