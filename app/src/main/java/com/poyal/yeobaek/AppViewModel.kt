package com.poyal.yeobaek

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.txt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class OpenBook(val book: Book, val document: TextDocument, val initial: ReadingPosition?, val token: String = UUID.randomUUID().toString())
data class SeekRequest(val offset: Long, val serial: Long = System.nanoTime())
data class ChangedBook(val opened: OpenBook, val exact: Boolean)
data class UiState(
    val screen: String = "folder", val path: List<DocumentEntry> = emptyList(), val entries: List<DocumentEntry> = emptyList(),
    val folderBusy: Boolean = false, val folderError: String? = null, val showAll: Boolean = false,
    val reader: OpenBook? = null, val preparing: String? = null, val error: String? = null,
    val failedEntry: DocumentEntry? = null, val resume: Book? = null, val sheet: String? = null,
    val info: DocumentEntry? = null, val changed: ChangedBook? = null,
    val missingBook: Book? = null,
    val searchQuery: String = "", val searching: Boolean = false, val searchResult: SearchResult? = null,
    val searchHits: List<Pair<Long, String>> = emptyList(), val searchIndex: Long = 0,
    val searchError: String? = null, val searchScanned: Float = 0f,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application as YeobaekApplication
    val prefs = container.preferences
    private val dao = container.database.library()
    private val folders = container.folders
    private val store = container.texts
    val app = prefs.app.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    val txt = prefs.txt.stateIn(viewModelScope, SharingStarted.Eagerly, TxtPreferences())
    val books = dao.observeBooks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val positions = dao.observePositions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks = _bookmarks.asStateFlow()
    private val _seek = MutableStateFlow<SeekRequest?>(null)
    val seek = _seek.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()
    private val _locator = MutableStateFlow(0L)
    val locator = _locator.asStateFlow()
    private var folderJob: Job? = null
    private var openJob: Job? = null
    private var searchJob: Job? = null
    private var marksJob: Job? = null
    private var openGeneration = 0L
    private var activeSession = 0L
    private var sequence = 0L
    private var latest: ReadingPosition? = null
    val currentPosition: ReadingPosition? get() = latest
    private var dirty = false
    private var idleSave: Job? = null
    private var searchOrigin: Long? = null
    private var searchGeneration = 0L
    private var activationToken: String? = null
    private var entryRequest = 0L
    private var openTrace: Int? = null
    private fun endOpenTrace() { openTrace?.let { android.os.Trace.endAsyncSection("OpenText", it) }; openTrace = null }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.cleanAbandoned(); File(container.cacheDir, "search").deleteRecursively() }
            val preferences = prefs.app.first()
            if (preferences.tree.isNotEmpty()) loadRoot(preferences.tree)
            val candidate = dao.session()?.bookId?.let { dao.book(it) }
            if (candidate != null) {
                try {
                    withContext(Dispatchers.IO) { folders.info(candidate.uri) }
                    _ui.update { it.copy(resume = candidate) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    dao.consumeCandidate()
                    _ui.update { it.copy(error = message(e), missingBook = candidate) }
                }
            }
        }
        viewModelScope.launch {
            while (isActive) { delay(1000); flushPosition() }
        }
    }

    fun action(block: suspend () -> Unit) { viewModelScope.launch { block() } }
    fun screen(value: String) { _ui.update { it.copy(screen = value, sheet = null) } }
    fun sheet(value: String?) {
        if (value == "search" && searchOrigin == null) searchOrigin = latest?.offset ?: 0
        _ui.update { it.copy(sheet = value) }
    }
    fun showAll() { _ui.update { it.copy(showAll = !it.showAll) } }
    fun clearError() { _ui.update { it.copy(error = null) } }
    fun info(entry: DocumentEntry?) { _ui.update { it.copy(info = entry, sheet = if (entry != null) "info" else null) } }
    private fun message(e: Exception) = when (e) {
        is SecurityException -> "폴더 접근 권한이 없습니다. 시작 폴더를 다시 연결해 주세요."
        is java.io.FileNotFoundException -> "파일이나 폴더를 찾을 수 없습니다. 위치를 확인하고 다시 연결해 주세요."
        is TextFailure -> e.message ?: "본문을 준비하지 못했습니다."
        else -> "작업을 완료하지 못했습니다. 저장 공간과 파일 상태를 확인한 뒤 다시 시도해 주세요."
    }
    fun connect(uri: Uri) {
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            _ui.update { it.copy(folderBusy = true) }
            try {
                val previous = app.value.tree
                val entry = withContext(Dispatchers.IO) { folders.connect(uri) }
                prefs.setFolder(uri.toString(), entry.name)
                if (previous.isNotEmpty() && previous != uri.toString()) withContext(Dispatchers.IO) { folders.release(previous) }
                _ui.update { it.copy(path = listOf(entry), folderError = null, screen = "folder") }
                queryFolder(uri.toString(), entry)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(error = message(e)) } }
            finally { _ui.update { it.copy(folderBusy = false) } }
        }
    }
    private fun loadRoot(tree: String) {
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            _ui.update { it.copy(folderBusy = true) }
            try {
                val root = withContext(Dispatchers.IO) { folders.root(tree) }
                _ui.update { it.copy(path = listOf(root)) }
                queryFolder(tree, root)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(folderError = message(e)) } }
            finally { _ui.update { it.copy(folderBusy = false) } }
        }
    }
    private suspend fun queryFolder(tree: String, entry: DocumentEntry) {
        val entries = withContext(Dispatchers.IO) { folders.list(tree, entry.id) }
        currentCoroutineContext().ensureActive()
        _ui.update { it.copy(entries = entries, folderError = null) }
    }
    fun refresh() {
        if (app.value.tree.isEmpty()) return
        val entry = ui.value.path.lastOrNull() ?: return loadRoot(app.value.tree)
        navigate(ui.value.path, entry)
    }
    fun enter(entry: DocumentEntry) {
        if (entry.directory) navigate(ui.value.path + entry, entry)
        else if (entry.txt || ui.value.showAll) open(entry)
        else _ui.update { it.copy(error = "현재는 TXT 파일만 읽을 수 있습니다.") }
    }
    fun up() {
        if (ui.value.path.size > 1) { val path = ui.value.path.dropLast(1); navigate(path, path.last()) }
    }
    private fun navigate(path: List<DocumentEntry>, entry: DocumentEntry) {
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            _ui.update { it.copy(folderBusy = true) }
            try { queryFolder(app.value.tree, entry); _ui.update { it.copy(path = path) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(folderError = message(e)) } }
            finally { _ui.update { it.copy(folderBusy = false) } }
        }
    }
    fun disconnect() = action {
        folderJob?.cancelAndJoin()
        val old = app.value.tree
        prefs.setFolder("", "")
        withContext(Dispatchers.IO) { if (old.isNotEmpty()) folders.release(old) }
        _ui.update { it.copy(path = emptyList(), entries = emptyList(), folderError = null, sheet = null) }
    }
    fun clearCache() = action {
        if (ui.value.preparing != null) return@action
        withContext(Dispatchers.IO) { store.clear(setOfNotNull(ui.value.reader?.document?.version)) }
        _ui.update { it.copy(sheet = null) }
    }
    fun chooseResume(resume: Boolean) = action {
        val book = ui.value.resume ?: return@action
        _ui.update { it.copy(resume = null) }
        dao.consumeCandidate()
        if (resume) openBook(book)
    }
    fun openBook(book: Book) {
        val request = ++entryRequest
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { folders.info(book.uri) }
                if (request == entryRequest) open(entry, existing = book)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == entryRequest) _ui.update { it.copy(error = message(e), missingBook = book) } }
        }
    }
    fun reconnectBook(uri: Uri) = action {
        val book = ui.value.missingBook ?: return@action
        try {
            val entry = withContext(Dispatchers.IO) {
                container.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                folders.info(uri.toString())
            }
            val sameSource = dao.source(folders.key(entry.uri))
            if (sameSource != null && sameSource.id != book.id) {
                _ui.update { it.copy(error = "선택한 파일에는 이미 별도의 독서 기록이 있습니다. 해당 파일을 폴더에서 열어 주세요.") }
            } else open(entry, existing = book)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _ui.update { it.copy(error = message(e)) } }
    }
    fun open(entry: DocumentEntry, override: String? = null, existing: Book? = null) {
        ++entryRequest
        openJob?.cancel()
        val generation = ++openGeneration
        endOpenTrace()
        openTrace = generation.toInt()
        android.os.Trace.beginAsyncSection("OpenText", generation.toInt())
        openJob = viewModelScope.launch {
            flushPosition()
            _ui.update { it.copy(preparing = "파일 확인 중", error = null, failedEntry = entry, sheet = null) }
            var knownBook = existing
            try {
                val original = existing ?: dao.source(folders.key(entry.uri))
                knownBook = original
                val oldPosition = original?.let { dao.position(it.id, it.version) }
                val job = currentCoroutineContext()
                var lastProgress = 0L
                val document = withContext(Dispatchers.IO) {
                    store.prepare({ folders.open(entry.uri) }, override ?: original?.charsetOverride,
                        setOfNotNull(ui.value.reader?.document?.version), progress = { p ->
                            val now = System.nanoTime()
                            if (now - lastProgress > 100_000_000 && generation == openGeneration) {
                                lastProgress = now; _ui.update { it.copy(preparing = p.phase) }
                            }
                        }, check = { job.ensureActive() })
                }
                ensureActive()
                val book = (original ?: Book(UUID.randomUUID().toString(), folders.key(entry.uri), entry.uri, entry.name, ui.value.path.joinToString(" / ") { it.name }))
                    .copy(name = entry.name, uri = entry.uri, sourceKey = folders.key(entry.uri), charset = document.charset, charsetOverride = override ?: original?.charsetOverride,
                        version = document.version, sourceHash = document.sourceHash, length = document.length)
                val position = dao.position(book.id, document.version)
                val opened = OpenBook(book, document, position)
                if (original != null && original.version.isNotEmpty() && original.version != document.version) {
                    val mapped = remap(document, oldPosition ?: ReadingPosition(original.id, original.version, 0), job)
                    endOpenTrace()
                    _ui.update { it.copy(preparing = null, changed = ChangedBook(opened.copy(initial = mapped.first), mapped.second)) }
                } else showReader(opened)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == openGeneration) {
                endOpenTrace()
                val inaccessible = e is java.io.FileNotFoundException || e is SecurityException
                _ui.update { it.copy(error = message(e), missingBook = if (inaccessible) knownBook else null) }
            } }
            finally { if (generation == openGeneration) _ui.update { it.copy(preparing = null) } }
        }
    }
    private suspend fun remap(doc: TextDocument, old: ReadingPosition, job: kotlin.coroutines.CoroutineContext): Pair<ReadingPosition, Boolean> = withContext(Dispatchers.IO) {
        val query = old.before + old.after
        if (query.isNotEmpty()) {
            val file = File(container.cacheDir, "remap-${UUID.randomUUID()}")
            try {
                val found = doc.search(query, file, check = { job.ensureActive() })
                if (found.count == 1L) return@withContext old.copy(version = doc.version, offset = found.at(0) + old.before.length, layoutKey = "", fraction = 0f) to true
            } finally { file.delete() }
        }
        val oldBook = dao.book(old.bookId)
        val ratio = if ((oldBook?.length ?: 0) > 0) old.offset.toDouble() / oldBook!!.length else 0.0
        old.copy(version = doc.version, offset = (ratio * doc.length).toLong().coerceIn(0, doc.length), layoutKey = "", fraction = 0f) to false
    }
    fun acceptChange(accept: Boolean) {
        val change = ui.value.changed ?: return
        _ui.update { it.copy(changed = null) }
        if (accept) showReader(change.opened)
    }
    private fun showReader(opened: OpenBook) {
        activeSession = 0; sequence = 0; latest = opened.initial; dirty = false; searchOrigin = null
        _locator.value = opened.initial?.offset ?: 0
        searchJob?.cancel(); marksJob?.cancel(); _seek.value = null
        _progress.value = if (opened.document.length == 0L) 0f else ((opened.initial?.offset ?: 0).toFloat() / opened.document.length)
        _ui.update { it.copy(reader = opened, sheet = null, info = null, missingBook = null, preparing = null, error = null, changed = null, searchHits = emptyList(), searchResult = null, searchQuery = "") }
        marksJob = viewModelScope.launch { dao.bookmarks(opened.book.id).collect { _bookmarks.value = it } }
    }
    fun cancelOpen() { ++openGeneration; openJob?.cancel(); endOpenTrace(); _ui.update { it.copy(preparing = null) } }
    fun readerReady(token: String) {
        if (activeSession != 0L || activationToken == token) return
        val opened = ui.value.reader?.takeIf { it.token == token } ?: return
        endOpenTrace()
        activationToken = token
        viewModelScope.launch {
            try {
                if (ui.value.reader?.token != token) return@launch
                val revision = dao.activate(opened.book)
                if (ui.value.reader?.token != token) { dao.consumeRevision(revision); return@launch }
                activeSession = revision
                dirty = true; flushPosition()
            } finally { if (activationToken == token) activationToken = null }
        }
    }
    fun position(offset: Long, layout: String, fraction: Float, completed: Boolean) {
        val reader = ui.value.reader ?: return
        val at = offset.coerceIn(0, reader.document.length)
        latest = ReadingPosition(reader.book.id, reader.document.version, at, layoutKey = layout, fraction = fraction, completed = completed)
        _locator.value = at
        _progress.value = if (completed && reader.document.length > 0) 1f else if (reader.document.length == 0L) 0f else at.toFloat() / reader.document.length
        dirty = true
        idleSave?.cancel()
        idleSave = viewModelScope.launch { delay(300); flushPosition() }
    }
    suspend fun flushPosition() {
        if (!dirty || activeSession == 0L) return
        val p = latest ?: return
        val reader = ui.value.reader ?: return
        if (reader.book.id != p.bookId || reader.document.version != p.version) return
        dirty = false
        val session = activeSession; val seq = ++sequence
        try {
            val context = withContext(Dispatchers.IO) {
                try { reader.document.context(p.offset) } catch (_: java.io.IOException) { p.before to p.after }
            }
            dao.save(p.copy(before = context.first, after = context.second, session = session, sequence = seq))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _ui.update { it.copy(error = "읽던 위치를 저장하지 못했습니다. 저장 공간을 확인해 주세요.") } }
    }
    fun background() = action { flushPosition() }
    fun closeReader() = action {
        cancelOpen(); searchJob?.cancel(); idleSave?.cancel()
        flushPosition(); dao.consumeCandidate(); activeSession = 0; marksJob?.cancel()
        _ui.update { it.copy(reader = null, sheet = null, searchResult = null, searchHits = emptyList(), searchQuery = "") }
        latest = null; searchOrigin = null; _seek.value = null
    }
    fun seek(offset: Long) { _seek.value = SeekRequest(offset) }
    fun seekPercent(percent: Float) {
        val reader = ui.value.reader ?: return
        seek((reader.document.length * percent.coerceIn(0f, 1f)).toLong())
        sheet(null)
    }
    fun toggleBookmark() = action {
        val reader = ui.value.reader ?: return@action
        val at = latest?.offset ?: 0
        val existing = bookmarks.value.firstOrNull { it.version == reader.document.version && it.offset == at }
        if (existing != null) dao.deleteBookmark(existing.id)
        else withContext(Dispatchers.IO) {
            val context = reader.document.context(at)
            dao.addBookmark(Bookmark(UUID.randomUUID().toString(), reader.book.id, reader.document.version, at, reader.document.excerpt(at), context.first, context.second))
        }
    }
    fun deleteBookmark(id: String) = action { dao.deleteBookmark(id) }
    fun openBookmark(mark: Bookmark) = action {
        val reader = ui.value.reader ?: return@action
        if (mark.version == reader.document.version) { seek(mark.offset); sheet(null); return@action }
        val old = ReadingPosition(mark.bookId, mark.version, mark.offset, mark.before, mark.after)
        val (mapped, exact) = remap(reader.document, old, currentCoroutineContext())
        if (exact) { seek(mapped.offset); sheet(null) }
        else _ui.update { it.copy(error = "이 책갈피는 이전 본문에 있습니다. 현재 본문에서 같은 문장을 찾지 못해 기록을 그대로 보관합니다.") }
    }
    fun forget(book: Book) = action { dao.forget(book.id); _ui.update { it.copy(sheet = null) } }
    fun search(query: String) {
        searchJob?.cancel(); val generation = ++searchGeneration
        val previousResult = ui.value.searchResult
        viewModelScope.launch(Dispatchers.IO) { previousResult?.file?.delete() }
        _ui.update { it.copy(searchQuery = query, searching = query.isNotBlank(), searchError = null, searchHits = emptyList(), searchResult = null, searchIndex = 0, searchScanned = 0f) }
        if (query.isBlank()) return
        if (query.codePointCount(0, query.length) > 256) { _ui.update { it.copy(searching = false, searchError = "검색어는 256자까지 입력할 수 있습니다.") }; return }
        val reader = ui.value.reader ?: return
        searchJob = viewModelScope.launch {
            val context = currentCoroutineContext()
            val dir = File(container.cacheDir, "search").apply { mkdirs() }
            try {
                var firstSent = false
                val output = File(dir, UUID.randomUUID().toString())
                val result = withContext(Dispatchers.IO) {
                    reader.document.search(query, output, check = { context.ensureActive() }, progress = { at, count ->
                        if (generation == searchGeneration) {
                            if (!firstSent && count > 0) {
                                firstSent = true
                                val partial = SearchResult(output, count)
                                val first = partial.page(0).map { it to reader.document.excerpt(it) }
                                _ui.update { it.copy(searchHits = first) }
                            }
                            _ui.update { it.copy(searchScanned = if (reader.document.length == 0L) 1f else at.toFloat() / reader.document.length) }
                        }
                    })
                }
                val hits = withContext(Dispatchers.IO) { result.page(0).map { it to reader.document.excerpt(it) } }
                if (generation == searchGeneration) _ui.update { it.copy(searchResult = result, searchHits = hits, searching = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == searchGeneration) _ui.update { it.copy(searching = false, searchError = message(e)) } }
        }
    }
    fun cancelSearch() { searchJob?.cancel(); ++searchGeneration; _ui.update { it.copy(searching = false) } }
    fun searchResult(index: Long) = action {
        val result = ui.value.searchResult ?: return@action
        if (result.count == 0L) return@action
        val actual = index.coerceIn(0, result.count - 1)
        try {
            val at = withContext(Dispatchers.IO) { result.at(actual) }
            if (ui.value.searchResult == result) { _ui.update { it.copy(searchIndex = actual, sheet = null) }; seek(at) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (ui.value.searchResult == result) _ui.update { it.copy(searchError = message(e)) } }
    }
    fun moreSearch() = action {
        val result = ui.value.searchResult ?: return@action
        val doc = ui.value.reader?.document ?: return@action
        val start = (ui.value.searchIndex / 100 + 1) * 100
        if (start >= result.count) return@action
        try {
            val hits = withContext(Dispatchers.IO) { result.page(start).map { it to doc.excerpt(it) } }
            if (ui.value.searchResult == result) _ui.update { it.copy(searchIndex = start, searchHits = hits) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (ui.value.searchResult == result) _ui.update { it.copy(searchError = message(e)) } }
    }
    fun returnFromSearch() {
        searchOrigin?.let(::seek); searchOrigin = null; cancelSearch(); _ui.update { it.copy(sheet = null, searchQuery = "") }
    }
    fun retryEncoding(encoding: String) {
        val reader = ui.value.reader
        val entry = if (reader != null) DocumentEntry("", reader.book.uri, reader.book.name, false, null, null, "text/plain") else ui.value.failedEntry
        if (entry != null) open(entry, encoding, reader?.book)
    }
    suspend fun encodingPreview(encoding: String): String = withContext(Dispatchers.IO) {
        val uri = ui.value.reader?.book?.uri ?: ui.value.failedEntry?.uri ?: return@withContext ""
        folders.open(uri).use { input ->
            val reader = java.io.InputStreamReader(input, java.nio.charset.Charset.forName(encoding))
            val chars = CharArray(240); val n = reader.read(chars)
            if (n < 0) "빈 문서입니다." else String(chars, 0, n).removePrefix("\uFEFF")
        }
    }
}
