package com.poyal.yeobaek.pdf

import com.poyal.yeobaek.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class PdfSearchState(val query: String = "", val running: Boolean = false, val scanned: Int = 0,
    val count: Long = 0, val index: Long = -1, val rows: List<PdfMatch> = emptyList(), val rowStart: Long = 0,
    val noText: Boolean = false, val error: String? = null)

class PdfReaderController(private val scope: CoroutineScope, private val dao: LibraryDao, private val cache: File,
    private val error: (String) -> Unit) {
    var opened: OpenPdf? = null; private set
    val locator = MutableStateFlow(PdfLocator())
    val seek = MutableStateFlow<PdfSeek?>(null)
    val marks = MutableStateFlow<List<PdfBookmark>>(emptyList())
    val search = MutableStateFlow(PdfSearchState())
    val highlight = MutableStateFlow<PdfTextRange?>(null)
    val selection = MutableStateFlow<PdfTextRange?>(null)
    private var revision = 0L
    private var sequence = 0L
    private var dirty = false
    private var activating = false
    private var saveJob: Job? = null
    private var marksJob: Job? = null
    private var searchJob: Job? = null
    private var selectJob: Job? = null
    private var searchIndex: PdfSearchIndex? = null
    private var searchOrigin: PdfLocator? = null
    fun show(value: OpenPdf) {
        opened = value; locator.value = value.initial; seek.value = null; revision = 0; sequence = 0; dirty = false; activating = false
        cancelSearch(); search.value = PdfSearchState(); highlight.value = null; selection.value = null; searchOrigin = null
        marksJob?.cancel(); marksJob = scope.launch { dao.pdfBookmarks(value.book.id).collect { marks.value = it } }
    }
    fun ready() {
        val doc = opened ?: return
        if (revision != 0L || activating) return
        activating = true
        scope.launch {
            try {
                val next = dao.activate(doc.book)
                if (opened?.token != doc.token) { dao.consumeRevision(next); return@launch }
                revision = next; dirty = true; flush()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error("PDF 독서 기록을 시작하지 못했습니다. 문서를 다시 열어 주세요.") }
            finally { if (opened?.token == doc.token) activating = false }
        }
    }
    fun position(value: PdfLocator) {
        val doc = opened ?: return
        locator.value = value.constrained(doc.document.pages.size); dirty = true
        saveJob?.cancel(); saveJob = scope.launch { delay(300); flush() }
    }
    suspend fun flush() {
        val doc = opened ?: return
        if (!dirty || revision == 0L) return
        val p = locator.value; val session = revision
        dirty = false
        try { dao.savePdf(PdfPosition(doc.book.id, doc.book.version, p.page, p.x, p.y, p.zoom, p.completed, session, ++sequence)) }
        catch (e: CancellationException) { dirty = true; throw e }
        catch (_: Exception) { if (opened?.token == doc.token) dirty = true; error("PDF 읽기 위치를 저장하지 못했습니다.") }
    }
    suspend fun close() {
        flush(); val doc = opened; opened = null; revision = 0
        saveJob?.cancel(); marksJob?.cancel(); selectJob?.cancel(); searchJob?.cancelAndJoin(); cancelSearch()
        selection.value = null; highlight.value = null
        doc?.document?.close()
        withContext(Dispatchers.IO) { searchIndex?.file?.delete() }; searchIndex = null
    }
    fun move(value: PdfLocator) { opened?.let { seek.value = PdfSeek(value.constrained(it.document.pages.size)) } }
    fun toggleBookmark() {
        val doc = opened ?: return; val p = locator.value
        scope.launch {
            try {
                val existing = marks.value.firstOrNull { it.version == doc.book.version && it.page == p.page }
                if (existing != null) dao.deletePdfBookmark(existing.id)
                else dao.addPdfBookmark(PdfBookmark(UUID.randomUUID().toString(), doc.book.id, doc.book.version, p.page, p.x, p.y, p.zoom))
            } catch (_: Exception) { error("책갈피를 저장하지 못했습니다.") }
        }
    }
    fun deleteBookmark(id: String) { scope.launch { try { dao.deletePdfBookmark(id) } catch (_: Exception) { error("책갈피를 삭제하지 못했습니다.") } } }
    fun openBookmark(mark: PdfBookmark) {
        val doc = opened ?: return
        if (mark.version != doc.book.version) { error("변경 전 문서의 책갈피입니다. 현재 문서에서는 페이지가 달라졌을 수 있습니다."); return }
        move(PdfLocator(mark.page, mark.x, mark.y, mark.zoom))
    }
    fun select(page: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        val doc = opened ?: return
        selectJob?.cancel(); selectJob = scope.launch {
            try { selection.value = doc.document.select(page, x1, y1, x2, y2).takeIf { it.count > 0 } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "텍스트를 선택하지 못했습니다.") }
        }
    }
    fun clearSelection() { selectJob?.cancel(); selection.value = null }
    fun cancelSearch() { searchJob?.cancel(); searchJob = null; search.update { it.copy(running = false) } }
    fun startSearch(query: String) {
        val doc = opened ?: return
        val previousJob = searchJob
        val previousIndex = searchIndex
        cancelSearch(); if (query.isBlank()) { search.value = PdfSearchState(); highlight.value = null; return }
        searchOrigin = searchOrigin ?: locator.value
        val index = PdfSearchIndex(File(cache, "pdf-search-${UUID.randomUUID()}")); searchIndex = index
        search.value = PdfSearchState(query = query, running = true)
        searchJob = scope.launch {
            previousJob?.join(); withContext(Dispatchers.IO) { previousIndex?.file?.delete() }
            var textCount = 0L
            try {
                for (page in doc.document.pages.indices) {
                    var start = 0
                    do {
                        ensureActive()
                        val batch = doc.document.search(page, query, start)
                        if (start == 0) textCount += batch.textCount
                        withContext(Dispatchers.IO) { index.append(batch.matches) }
                        val rows = if (search.value.rows.size < 100) (search.value.rows + batch.matches).take(100) else search.value.rows
                        search.update { it.copy(count = index.count, rows = rows) }
                        start = batch.next
                        yield()
                    } while (start >= 0)
                    search.update { it.copy(scanned = page + 1) }
                }
                search.update { it.copy(running = false, noText = textCount == 0L) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { search.update { it.copy(running = false, error = e.message ?: "검색을 완료하지 못했습니다.") } }
        }
    }
    fun result(index: Long) {
        val doc = opened ?: return; val results = searchIndex ?: return
        if (index !in 0 until results.count) return
        scope.launch {
            try {
                val match = withContext(Dispatchers.IO) { results.at(index) }
                val range = doc.document.range(match)
                if (opened?.token != doc.token || searchIndex !== results) return@launch
                highlight.value = range
                move(PdfLocator(match.page, y = ((range.rects.firstOrNull()?.top ?: 0f) - .03f).coerceAtLeast(0f)))
                search.update { it.copy(index = index) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "검색 위치로 이동하지 못했습니다.") }
        }
    }
    fun moreResults() {
        val index = searchIndex ?: return; val from = search.value.rowStart + 100
        scope.launch {
            val rows = withContext(Dispatchers.IO) { index.page(from) }
            if (searchIndex === index) search.update { it.copy(rows = rows, rowStart = from) }
        }
    }
    fun returnFromSearch() { searchOrigin?.let(::move); highlight.value = null; searchOrigin = null }
}
