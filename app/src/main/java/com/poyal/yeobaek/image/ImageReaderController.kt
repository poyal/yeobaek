package com.poyal.yeobaek.image

import com.poyal.yeobaek.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class ImageReaderController(private val scope: CoroutineScope, private val dao: LibraryDao, private val error: (String) -> Unit) {
    var opened: OpenImages? = null; private set
    val locator = MutableStateFlow(ImageLocator())
    val seek = MutableStateFlow<ImageSeek?>(null)
    val marks = MutableStateFlow<List<ImageBookmark>>(emptyList())
    private var revision = 0L
    private var sequence = 0L
    private var dirty = false
    private var activating = false
    private var saveJob: Job? = null
    private var marksJob: Job? = null
    fun show(value: OpenImages) {
        opened = value; locator.value = value.initial; seek.value = null; marks.value = emptyList()
        revision = 0; sequence = 0; dirty = false; activating = false
        marksJob?.cancel(); marksJob = scope.launch { dao.imageBookmarks(value.book.id).collect { marks.value = it } }
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
            catch (_: Exception) { error("이미지 독서 기록을 시작하지 못했습니다. 다시 열어 주세요.") }
            finally { if (opened?.token == doc.token) activating = false }
        }
    }
    fun position(value: ImageLocator) {
        val doc = opened ?: return
        locator.value = value.constrained(doc.source.pages.value); dirty = true
        saveJob?.cancel(); saveJob = scope.launch { delay(300); flush() }
    }
    suspend fun flush() {
        val doc = opened ?: return
        if (!dirty || revision == 0L) return
        val p = locator.value; val session = revision; dirty = false
        try { dao.saveImage(ImagePosition(doc.book.id, doc.book.version, p.key, p.page, p.x, p.y, p.zoom, p.completed, session, ++sequence, p.segment)) }
        catch (e: CancellationException) { dirty = true; throw e }
        catch (_: Exception) { if (opened?.token == doc.token) dirty = true; error("이미지 읽기 위치를 저장하지 못했습니다.") }
    }
    suspend fun close() {
        flush(); val doc = opened; opened = null; revision = 0
        saveJob?.cancel(); marksJob?.cancel(); doc?.source?.close()
    }
    fun move(value: ImageLocator) { opened?.let { seek.value = ImageSeek(value.constrained(it.source.pages.value)) } }
    fun toggleBookmark() {
        val doc = opened ?: return; val p = locator.value
        scope.launch {
            try {
                val existing = marks.value.firstOrNull { it.version == doc.book.version && it.pageKey == p.key }
                if (existing != null) dao.deleteImageBookmark(existing.id)
                else dao.addImageBookmark(ImageBookmark(UUID.randomUUID().toString(), doc.book.id, doc.book.version, p.key, p.page,
                    doc.source.pages.value[p.page].name, p.x, p.y, p.zoom, segment = p.segment))
            } catch (_: Exception) { error("책갈피를 저장하지 못했습니다.") }
        }
    }
    fun deleteBookmark(id: String) { scope.launch { try { dao.deleteImageBookmark(id) } catch (_: Exception) { error("책갈피를 삭제하지 못했습니다.") } } }
    fun openBookmark(mark: ImageBookmark): Boolean {
        val doc = opened ?: return false
        if (mark.version != doc.book.version && (doc.book.format == "IMAGE_ARCHIVE" || doc.source.pages.value.none { it.key == mark.pageKey })) {
            error("변경 전 이미지의 책갈피입니다. 현재 파일에서 위치를 확인할 수 없습니다."); return false
        }
        move(ImageLocator(mark.page, mark.pageKey, mark.x, mark.y, mark.zoom, segment = mark.segment)); return true
    }
}
