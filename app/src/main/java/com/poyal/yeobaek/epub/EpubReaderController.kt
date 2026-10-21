@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek.epub

import com.poyal.yeobaek.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

interface EpubRenderer {
    suspend fun capture(): Locator?
    fun move(locator: Locator): Boolean
    fun advance(forward: Boolean): Boolean
    fun release()
}

data class EpubSearchState(val query: String = "", val running: Boolean = false, val count: Int = 0,
    val rows: List<Locator> = emptyList(), val start: Int = 0, val error: String? = null)

fun decodeEpubLocator(value: String?): Locator? = value?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
fun epubLocationKey(locator: Locator): String {
    val location = locator.locations.cssSelector ?: locator.text.highlight?.take(512)
        ?: "${((locator.locations.progression ?: 0.0) * 10000).toInt()}"
    return MessageDigest.getInstance("SHA-256").digest("${locator.href}:$location".toByteArray()).joinToString("") { "%02x".format(it) }
}

class EpubReaderController(private val scope: CoroutineScope, private val dao: LibraryDao, private val cache: File,
    private val error: (String) -> Unit) {
    var opened: OpenEpub? = null; private set
    var renderer: EpubRenderer? = null
    val locator = MutableStateFlow<Locator?>(null)
    val marks = MutableStateFlow<List<EpubBookmark>>(emptyList())
    val search = MutableStateFlow(EpubSearchState())
    val chrome = MutableStateFlow(true)
    val ready = MutableStateFlow(false)
    val returnLocation = MutableStateFlow<Locator?>(null)
    val pending = MutableStateFlow<Locator?>(null)
    val completed = MutableStateFlow(false)
    private var revision = 0L
    private var sequence = 0L
    private var dirty = false
    private var marksJob: Job? = null
    private var activateJob: Job? = null
    private var saveJob: Job? = null
    private var searchJob: Job? = null
    private var index: EpubSearchIndex? = null
    private var searchOrigin: Locator? = null

    var userNavigated = false
        private set
    fun userNavigation() { userNavigated = true }
    fun restoring() { userNavigated = false }
    fun show(value: OpenEpub) {
        opened = value; locator.value = value.initial; revision = 0; sequence = 0; dirty = false
        ready.value = false; chrome.value = true; completed.value = value.initialCompleted; userNavigated = false; returnLocation.value = null; pending.value = null
        marks.value = emptyList(); search.value = EpubSearchState(); searchOrigin = null
        marksJob = scope.launch { dao.epubBookmarks(value.book.id).collect { marks.value = it } }
    }
    fun rendered() {
        val doc = opened ?: return
        ready.value = true
        if (revision != 0L || activateJob?.isActive == true) return
        activateJob = scope.launch {
            try {
                val next = dao.activate(doc.book)
                if (opened?.token != doc.token) { dao.consumeRevision(next); return@launch }
                revision = next; dirty = true; capture(); flush()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error("EPUB 독서 기록을 시작하지 못했습니다. 책을 다시 열어 주세요.") }
        }
    }
    fun position(value: Locator) {
        if (!ready.value || opened == null) return
        val previous = locator.value
        val moved = previous?.href != value.href || previous?.locations?.progression != value.locations.progression || previous?.locations?.position != value.locations.position
        if (previous != value) { locator.value = value; dirty = true }
        if (userNavigated && moved) { completed.value = false; dirty = true }
        saveJob?.cancel(); saveJob = scope.launch { delay(300); capture(); flush() }
    }
    suspend fun capture(): Locator? {
        val token = opened?.token ?: return null
        if (!ready.value) return locator.value
        val captured = withTimeoutOrNull(700) { renderer?.capture() }
        if (opened?.token != token) return null
        if (captured != null && captured != locator.value) { locator.value = captured; dirty = true }
        return locator.value
    }
    suspend fun flush() {
        val doc = opened ?: return
        if (!dirty || revision == 0L) return
        val at = locator.value ?: return
        val value = EpubPosition(doc.book.id, doc.book.version, at.toJSON().toString(), doc.document.progress(at), completed.value, revision, ++sequence)
        dirty = false
        try { dao.saveEpub(value) }
        catch (e: CancellationException) { dirty = true; throw e }
        catch (_: Exception) { if (opened?.token == doc.token) dirty = true; error("EPUB 읽기 위치를 저장하지 못했습니다.") }
    }
    fun finish() { userNavigated = false; completed.value = true; dirty = true; scope.launch { flush() } }
    suspend fun close() = withContext(NonCancellable) {
        capture(); flush()
        ready.value = false
        activateJob?.cancelAndJoin(); saveJob?.cancelAndJoin(); searchJob?.cancelAndJoin(); marksJob?.cancel()
        renderer?.release(); renderer = null
        val old = opened; opened = null; revision = 0; pending.value = null
        withContext(Dispatchers.IO) { index?.close(); index = null; old?.document?.close() }
    }
    fun dispose() { renderer = null; opened?.document?.close(); index?.close() }
    fun move(value: Locator) { userNavigation(); pending.value = value }
    fun follow(link: Link) {
        val doc = opened ?: return
        scope.launch {
            val origin = capture()
            if (opened?.token != doc.token) return@launch
            returnLocation.value = origin
            doc.document.publication.locatorFromLink(link)?.let(::move)
        }
    }
    fun returnFromLink() { returnLocation.value?.let(::move); returnLocation.value = null }
    fun toggleBookmark() {
        val doc = opened ?: return
        scope.launch {
            try {
                val at = capture() ?: return@launch
                if (opened?.token != doc.token) return@launch
                val key = epubLocationKey(at)
                val old = marks.value.firstOrNull { it.version == doc.book.version && it.locationKey == key }
                if (old != null) dao.deleteEpubBookmark(old.id)
                else dao.addEpubBookmark(EpubBookmark(UUID.randomUUID().toString(), doc.book.id, doc.book.version, key,
                    at.toJSON().toString(), at.text.highlight?.take(160)?.takeIf { it.isNotBlank() } ?: at.title ?: "읽던 위치"))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error("EPUB 책갈피를 저장하지 못했습니다.") }
        }
    }
    fun deleteBookmark(id: String) { scope.launch { try { dao.deleteEpubBookmark(id) } catch (_: Exception) { error("책갈피를 삭제하지 못했습니다.") } } }
    fun openBookmark(mark: EpubBookmark) {
        if (mark.version != opened?.book?.version) { error("변경 전 EPUB의 책갈피입니다. 이전 원본에서 사용할 수 있습니다."); return }
        decodeEpubLocator(mark.locator)?.let(::move) ?: error("책갈피 위치를 읽지 못했습니다.")
    }
    fun cancelSearch() { searchJob?.cancel(); search.update { it.copy(running = false) } }
    fun startSearch(query: String) {
        val doc = opened ?: return
        val previous = searchJob
        previous?.cancel()
        search.value = EpubSearchState(query = query, running = query.isNotBlank())
        searchJob = scope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { index?.close() }; index = null
            if (query.isBlank()) return@launch
            searchOrigin = searchOrigin ?: capture()
            val found = EpubSearchIndex(File(cache, "epub-search-${UUID.randomUUID()}")); index = found
            try {
                withContext(Dispatchers.IO) {
                    val iterator = doc.document.publication.search(query) ?: throw EpubFailure("검색할 텍스트가 없는 EPUB입니다.")
                    try {
                        while (true) {
                            ensureActive()
                            val next = iterator.next().getOrElse { throw EpubFailure("EPUB 검색 중 본문을 읽지 못했습니다.") } ?: break
                            found.append(next.locators)
                            search.update { it.copy(count = found.count, rows = if (it.start == 0) found.page(0) else it.rows) }
                        }
                    } finally { iterator.close() }
                }
                search.update { it.copy(running = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { search.update { it.copy(running = false, error = e.message ?: "검색을 완료하지 못했습니다.") } }
        }
    }
    fun searchPage(start: Int) {
        val found = index ?: return
        scope.launch {
            val at = start.coerceIn(0, (found.count - 1).coerceAtLeast(0))
            val rows = withContext(Dispatchers.IO) { found.page(at) }
            if (index === found) search.update { it.copy(start = at, rows = rows) }
        }
    }
    fun returnFromSearch() { searchOrigin?.let(::move); searchOrigin = null }
}

/** Only the visible result window is decoded into UI state. */
private class EpubSearchIndex(private val file: File) {
    private val stream = RandomAccessFile(file, "rw")
    private val offsets = mutableListOf<Long>()
    private var closed = false
    val count get() = synchronized(this) { offsets.size }
    @Synchronized fun append(values: List<Locator>) {
        stream.seek(stream.length())
        values.forEach {
            val bytes = it.toJSON().toString().toByteArray(Charsets.UTF_8)
            offsets += stream.filePointer; stream.writeInt(bytes.size); stream.write(bytes)
        }
    }
    @Synchronized fun page(start: Int): List<Locator> {
        if (closed) return emptyList()
        return (start until minOf(start + 100, offsets.size)).mapNotNull {
            stream.seek(offsets[it]); val bytes = ByteArray(stream.readInt()); stream.readFully(bytes)
            decodeEpubLocator(bytes.toString(Charsets.UTF_8))
        }
    }
    @Synchronized fun close() { if (!closed) { closed = true; stream.close(); file.delete() } }
}
