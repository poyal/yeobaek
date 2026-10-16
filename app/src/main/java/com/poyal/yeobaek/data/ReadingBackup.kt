package com.poyal.yeobaek.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable data class LibrarySnapshot(
    val books: List<Book> = emptyList(), val positions: List<ReadingPosition> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(), val pdfPositions: List<PdfPosition> = emptyList(),
    val pdfBookmarks: List<PdfBookmark> = emptyList(), val imagePositions: List<ImagePosition> = emptyList(),
    val imageBookmarks: List<ImageBookmark> = emptyList(),
    val epubPositions: List<EpubPosition> = emptyList(), val epubBookmarks: List<EpubBookmark> = emptyList(),
) {
    val positionCount get() = positions.size + pdfPositions.size + imagePositions.size + epubPositions.size
    val bookmarkCount get() = bookmarks.size + pdfBookmarks.size + imageBookmarks.size + epubBookmarks.size
}
@Serializable data class ReadingBackup(val version: Int = 2, val createdAt: Long = System.currentTimeMillis(),
    val library: LibrarySnapshot, val settings: ReaderSettings, val theme: AppTheme)
data class BackupPreview(val backup: ReadingBackup, val conflicts: Int)

/** Journal makes a DB + DataStore import restartable; all writes are insert/merge-if-missing. */
class ReadingBackups(private val context: Context, private val dao: LibraryDao,
    private val prefs: Preferences, private val settings: ReaderSettingsStore) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val journal = AtomicFile(File(context.noBackupFilesDir, "reading-restore.json"))
    private val mutex = Mutex()
    suspend fun export(uri: Uri) = mutex.withLock { withContext(Dispatchers.IO) {
        val extra = settings.data.first()
        val globals = mapOf("common:TXT" to ReaderSettingValues.txt(prefs.txt.first()),
            "common:PDF" to ReaderSettingValues.pdf(prefs.pdf.first()), "common:IMAGE" to ReaderSettingValues.image(prefs.image.first()))
        val merged = globals.mapValues { (key, values) -> values + extra.overrides[key].orEmpty() } + extra.overrides.filterKeys { it !in globals }
        val value = ReadingBackup(library = dao.snapshot(), settings = extra.copy(overrides = merged), theme = prefs.app.first().theme)
        val bytes = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "백업이 32MiB를 초과합니다." }
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("백업 파일을 열지 못했습니다.")).use { it.write(bytes) }
    } }
    suspend fun preview(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        val bytes = (context.contentResolver.openInputStream(uri) ?: error("백업 파일을 열지 못했습니다.")).use { input ->
            readBounded(input)
        }
        val backup = decode(bytes)
        val existing = dao.snapshot()
        val ids = existing.books.map { it.id }.toSet(); val sources = existing.books.map { it.sourceKey }.toSet()
        BackupPreview(backup, backup.library.books.count { it.id in ids || it.sourceKey in sources })
    }
    internal fun decode(bytes: ByteArray): ReadingBackup = json.decodeFromString<ReadingBackup>(bytes.toString(Charsets.UTF_8)).also(::validateBackup)
    suspend fun restore(value: ReadingBackup) = mutex.withLock { withContext(Dispatchers.IO) {
        validateBackup(value)
        val stream = journal.startWrite()
        try { stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8)); journal.finishWrite(stream) }
        catch (e: Exception) { journal.failWrite(stream); throw e }
        apply(value)
        journal.delete()
    } }
    suspend fun recover() = mutex.withLock { withContext(Dispatchers.IO) {
        if (journal.baseFile.exists()) { apply(decode(journal.openRead().use(::readBounded))); journal.delete() }
    } }
    private suspend fun apply(value: ReadingBackup) {
        val aliases = dao.importMissing(value.library)
        val existing = prefs.explicitReaderFields()
        settings.update { current ->
            val incoming = value.settings
            fun mapped(key: String): String {
                if (!key.startsWith("book:")) return key
                val id = key.removePrefix("book:").substringBefore(':')
                return "book:${aliases[id] ?: id}:${key.substringAfterLast(':')}"
            }
            val imported = incoming.overrides.mapKeys { mapped(it.key) }
            val keys = imported.keys + current.overrides.keys + existing.keys
            current.copy(overrides = keys.associateWith { imported[it].orEmpty() + existing[it].orEmpty() + current.overrides[it].orEmpty() },
                folders = incoming.folders.mapKeys { aliases[it.key] ?: it.key } + current.folders,
                favorites = (current.favorites + incoming.favorites).distinctBy { it.uri },
                crops = incoming.crops.mapKeys { (key, _) -> val id = key.substringBefore(':'); "${aliases[id] ?: id}:${key.substringAfter(':')}" } + current.crops,
                screen = if (current.screen == ScreenSettings()) incoming.screen else current.screen,
                sort = if (current.sort == FolderSort.NAME) incoming.sort else current.sort)
        }
        prefs.restoreThemeIfMissing(value.theme)
    }
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "백업은 32MiB까지 읽을 수 있습니다." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    companion object { const val MAX_BYTES = 32 * 1024 * 1024 }
}

fun validateBackup(value: ReadingBackup) {
    require(value.version in 1..2) { "지원하지 않는 백업 버전입니다." }
    val data = value.library
    require(data.books.size <= 100_000 && data.positionCount + data.bookmarkCount <= 300_000)
    val ids = data.books.map { it.id }.toSet()
    require(ids.size == data.books.size && data.books.map { it.sourceKey }.distinct().size == data.books.size)
    require(data.books.all { it.id.isNotBlank() && !it.id.contains(':') && it.uri.startsWith("content://") && it.length >= 0 &&
        it.format in setOf("TXT", "PDF", "IMAGE_FOLDER", "IMAGE_ARCHIVE", "IMAGE_SINGLE", "EPUB") })
    if (value.version == 1) require(data.epubPositions.isEmpty() && data.epubBookmarks.isEmpty() && data.books.none { it.format == "EPUB" })
    val epubIds = data.books.filter { it.format == "EPUB" }.map { it.id }.toSet()
    require(data.epubPositions.all { it.bookId in epubIds && it.locatorVersion == 1 && it.progress.isFinite() && it.progress in 0.0..1.0 && validEpubLocator(it.locator) })
    require(data.epubBookmarks.all { it.bookId in epubIds && it.locatorVersion == 1 && it.locationKey.isNotBlank() && validEpubLocator(it.locator) })
    require(data.positions.all { it.bookId in ids && it.offset >= 0 && it.fraction.isFinite() })
    require(data.bookmarks.all { it.bookId in ids && it.offset >= 0 })
    fun valid(id: String, page: Int, x: Float, y: Float, zoom: Float) = id in ids && page >= 0 &&
        x.isFinite() && y.isFinite() && zoom.isFinite() && x in 0f..1f && y in 0f..1f && zoom in 1f..5f
    require(data.pdfPositions.all { valid(it.bookId, it.page, it.x, it.y, it.zoom) } && data.pdfBookmarks.all { valid(it.bookId, it.page, it.x, it.y, it.zoom) })
    require(data.imagePositions.all { valid(it.bookId, it.page, it.x, it.y, it.zoom) && it.segment in -1..1 } &&
        data.imageBookmarks.all { valid(it.bookId, it.page, it.x, it.y, it.zoom) && it.segment in -1..1 })
    require(value.settings.crops.values.all { it.valid() })
    require(value.settings.screen.brightness.isFinite() && value.settings.screen.brightness in -1f..1f &&
        value.settings.screen.rotation in setOf("SYSTEM", "PORTRAIT", "LANDSCAPE"))
    require(value.settings.favorites.all { it.directory && it.uri.startsWith("content://") })
}

internal fun validEpubLocator(value: String): Boolean = runCatching {
    require(value.length <= 256 * 1024)
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonObject
    val href = (obj["href"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    href.isNotBlank() && !href.startsWith("/") && !href.contains(":") && !href.split('/').contains("..") && type.isNotBlank()
}.getOrDefault(false)
