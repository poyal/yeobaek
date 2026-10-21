package com.poyal.yeobaek

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.txt.*
import com.poyal.yeobaek.pdf.*
import com.poyal.yeobaek.image.*
import com.poyal.yeobaek.epub.*
import org.readium.r2.shared.publication.services.coverFitting
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class OpenBook(val book: Book, val document: TextDocument, val initial: ReadingPosition?, val token: String = UUID.randomUUID().toString())
data class SeekRequest(val offset: Long, val serial: Long = System.nanoTime())
data class ChangedBook(val opened: OpenBook, val exact: Boolean)
data class UiState(
    val screen: String = "folder", val txtSettingsOrigin: String? = null,
    val path: List<DocumentEntry> = emptyList(), val entries: List<DocumentEntry> = emptyList(),
    val folderBusy: Boolean = false, val folderError: String? = null, val showAll: Boolean = false,
    val imageReader: OpenImages? = null, val changedImages: OpenImages? = null, val imageSettingsOrigin: String? = null, val imagePagesOrigin: String? = null,
    val pdfReader: OpenPdf? = null, val changedPdf: OpenPdf? = null, val pdfSettingsOrigin: String? = null,
    val epubReader: OpenEpub? = null, val changedEpub: OpenEpub? = null, val epubSettingsOrigin: String? = null,
    val reader: OpenBook? = null, val preparing: String? = null, val error: String? = null,
    val failedEntry: DocumentEntry? = null, val resume: Book? = null, val sheet: String? = null,
    val info: DocumentEntry? = null, val changed: ChangedBook? = null,
    val missingBook: Book? = null,
    val readingEnd: ReadingEnd? = null,
    val searchQuery: String = "", val searching: Boolean = false, val searchResult: SearchResult? = null,
    val searchHits: List<Pair<Long, String>> = emptyList(), val searchIndex: Long = 0,
    val searchError: String? = null, val searchScanned: Float = 0f,
    val searchPageStart: Long = 0,
) {
    val hasReader get() = reader != null || pdfReader != null || imageReader != null || epubReader != null
    fun searchHitIndex(row: Int): Long {
        require(row in searchHits.indices)
        return searchPageStart + row
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application as YeobaekApplication
    val prefs = container.preferences
    val thumbnails = FolderThumbnails(application)
    val scrollDiagnostics = container.scrollDiagnostics
    val updates = container.updates
    private val dao = container.database.library()
    private val folders = container.folders
    private val store = container.texts
    val app = prefs.app.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    val imagePositions = dao.observeImagePositions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    private val imageStore = ImageStore(File(application.noBackupFilesDir, "reader-cache"))
    val imageReader = ImageReaderController(viewModelScope, dao) { message -> _ui.update { it.copy(error = message) } }
    val pdfPositions = dao.observePdfPositions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    private val pdfEngine = PdfEngine(application)
    private val pdfStore = PdfStore(application.cacheDir)
    private val epubStore = EpubStore(application.cacheDir)
    private val epubEngine = EpubEngine(application)
    val epubReader = EpubReaderController(viewModelScope, dao, application.cacheDir) { message -> _ui.update { it.copy(error = message) } }
    val epubPositions = dao.observeEpubPositions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val pdfReader = PdfReaderController(viewModelScope, dao, application.cacheDir) { message -> _ui.update { it.copy(error = message) } }
    val books = dao.observeBooks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val positions = dao.observePositions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    val settingsStore = container.readerSettings
    val settings = settingsStore.data.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val settingsScope = MutableStateFlow(SettingsScope.COMMON)
    private val backups = ReadingBackups(container, dao, prefs, settingsStore)
    val backupPreview = MutableStateFlow<BackupPreview?>(null)
    val backupStatus = MutableStateFlow("")
    val backupBusy = MutableStateFlow(false)
    private fun backupAction(block: suspend () -> Unit) {
        if (backupBusy.value) return
        backupBusy.value = true
        action { try { block() } finally { backupBusy.value = false } }
    }
    fun exportBackup(uri: Uri) = backupAction { flushPosition(); backups.export(uri); backupStatus.value = "독서 기록과 설정을 저장했습니다." }
    fun inspectBackup(uri: Uri) = backupAction {
        backupPreview.value = null
        try { backupPreview.value = backups.preview(uri) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { backupStatus.value = "백업을 읽지 못했습니다. 손상되었거나 지원하지 않는 백업입니다." }
    }
    fun restoreBackup() {
        val value = backupPreview.value?.backup ?: return
        backupAction { flushPosition(); backups.restore(value); backupPreview.value = null; backupStatus.value = "복원했습니다. 접근할 수 없는 책은 최근 읽기에서 파일을 다시 연결해 주세요." }
    }
    fun setBookCrop(crop: CropRect) {
        val id = currentBook?.id ?: return
        action { settingsStore.fields("book:$id:IMAGE", mapOf("cropMode" to "MANUAL", "cropLeft" to "${crop.left}", "cropTop" to "${crop.top}", "cropRight" to "${crop.right}", "cropBottom" to "${crop.bottom}")) }
    }
    private fun UiState.book() = reader?.book ?: pdfReader?.book ?: imageReader?.book ?: epubReader?.book
    val currentBook get() = ui.value.book()
    val epub = combine(settings, ui) { extra, state -> ReaderSettingValues.epub(EpubReaderPreferences(), extra.fields("EPUB", state.book()?.id)) }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, EpubReaderPreferences())
    val editEpub = combine(settings, ui, settingsScope) { extra, state, scope -> ReaderSettingValues.epub(EpubReaderPreferences(), extra.editingFields("EPUB", state.book()?.id, scope)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EpubReaderPreferences())
    val txt = combine(prefs.txt, settings, ui) { base, extra, state -> ReaderSettingValues.txt(base, extra.fields("TXT", state.book()?.id)) }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, TxtPreferences())
    val pdf = combine(prefs.pdf, settings, ui) { base, extra, state -> ReaderSettingValues.pdf(base, extra.fields("PDF", state.book()?.id)) }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, PdfPreferences())
    val image = combine(prefs.image, settings, ui) { base, extra, state ->
        val id = state.book()?.id
        ReaderSettingValues.image(base, extra.fields("IMAGE", id)).copy(pageCrops = extra.crops.filterKeys { it.startsWith("$id:") }.mapKeys { it.key.removePrefix("$id:") })
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, ImagePreferences())
    val editTxt = combine(prefs.txt, settings, ui, settingsScope) { base, extra, state, scope -> ReaderSettingValues.txt(base, extra.editingFields("TXT", state.book()?.id, scope)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TxtPreferences())
    val editPdf = combine(prefs.pdf, settings, ui, settingsScope) { base, extra, state, scope -> ReaderSettingValues.pdf(base, extra.editingFields("PDF", state.book()?.id, scope)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PdfPreferences())
    val editImage = combine(prefs.image, settings, ui, settingsScope) { base, extra, state, scope -> ReaderSettingValues.image(base, extra.editingFields("IMAGE", state.book()?.id, scope)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ImagePreferences())
    fun changeSetting(format: String, field: String, value: Any) {
        val key = settings.value.scopeKey(settingsScope.value, format, currentBook?.id)
        action { settingsStore.fields(key, mapOf(field to value.toString())) }
    }
    fun setImage(value: ImagePreferences) {
        val old = ReaderSettingValues.image(editImage.value)
        val changes = ReaderSettingValues.image(value).filter { old[it.key] != it.value }
        val key = settings.value.scopeKey(settingsScope.value, "IMAGE", currentBook?.id)
        action { settingsStore.fields(key, changes) }
    }
    fun toggleInversion(format: String) {
        val inverted = if (format == "PDF") pdf.value.inverted else image.value.inverted
        val key = settings.value.effectiveFieldScope(format, currentBook?.id, "inverted", settingsScope.value)
        action { settingsStore.fields(key, mapOf("inverted" to (!inverted).toString())) }
    }
    fun resetSettings(format: String) {
        val scope = settingsScope.value
        val key = settings.value.scopeKey(scope, format, currentBook?.id)
        action {
            settingsStore.reset(key)
            if (scope == SettingsScope.COMMON) when (format) { "TXT" -> prefs.resetTxt(); "PDF" -> prefs.resetPdf(); "IMAGE" -> prefs.resetImage() }
        }
    }
    fun inheritSetting(format: String, field: String) {
        val key = settings.value.scopeKey(settingsScope.value, format, currentBook?.id)
        action { settingsStore.update { it.copy(overrides = it.overrides + (key to (it.overrides[key].orEmpty() - field))) } }
    }
    fun setScreen(value: ScreenSettings) = action { settingsStore.update { it.copy(screen = value) } }
    private suspend fun associateFolder(book: Book) {
        settingsScope.value = SettingsScope.COMMON
        val parent = withContext(Dispatchers.IO) { runCatching { folders.parentPath(book.uri, ui.value.path).lastOrNull()?.let { folders.key(it.uri) } }.getOrNull() }
        settingsStore.update { it.copy(folders = if (parent == null) it.folders - book.id else it.folders + (book.id to parent)) }
    }
    fun setPageCrop(crop: CropRect?) {
        val doc = ui.value.imageReader ?: return
        val key = "${doc.book.id}:${imageReader.locator.value.key}"
        action { settingsStore.update { it.copy(crops = if (crop == null) it.crops - key else it.crops + (key to crop)) } }
    }
    fun setFolderSort(value: FolderSort) = action { settingsStore.update { it.copy(sort = value) } }
    fun entryKey(uri: String) = folders.key(uri)

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks = _bookmarks.asStateFlow()
    private val _seek = MutableStateFlow<SeekRequest?>(null)
    val seek = _seek.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()
    private val _progressDisplay = MutableStateFlow(ReadingProgress.Unknown)
    val progressDisplay = _progressDisplay.asStateFlow()
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
    private var readingEndJob: Job? = null
    private var announcedEnd: String? = null
    private fun endOpenTrace() { openTrace?.let { android.os.Trace.endAsyncSection("OpenText", it) }; openTrace = null }

    init {
        viewModelScope.launch(Dispatchers.IO) { epubStore.clean(); container.cacheDir.listFiles()?.filter { it.name.startsWith("epub-search-") }?.forEach { it.delete() } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.cleanAbandoned(); pdfStore.clean(); imageStore.clean(); container.cacheDir.listFiles()?.filter { it.name.startsWith("pdf-search-") }?.forEach { it.delete() }; File(container.cacheDir, "search").deleteRecursively() }
            try { backups.recover() } catch (_: Exception) { _ui.update { it.copy(error = "중단된 백업 복원을 완료하지 못했습니다. 저장 공간을 확인해 주세요.") } }
            val preferences = prefs.app.first()
            if (preferences.tree.isNotEmpty()) loadRoot(preferences.tree)
            val candidate = dao.session()?.bookId?.let { dao.book(it) }
            if (candidate != null && !externalLaunch) {
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

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(error = message(e)) } }
        }
    }
    fun screen(value: String) { _ui.update { it.copy(screen = value, sheet = null) } }
    fun openTxtSettings() {
        _ui.update { if (it.screen == "txt_settings") it else it.copy(screen = "txt_settings", txtSettingsOrigin = it.screen, sheet = null) }
    }
    fun openPdfSettings() { _ui.update { it.copy(screen = "pdf_settings", pdfSettingsOrigin = it.screen, sheet = null) } }
    fun openEpubSettings() = action {
        epubReader.capture(); epubReader.flush()
        epubReader.ready.value = false
        _ui.update { it.copy(screen = "epub_settings", epubSettingsOrigin = it.screen, sheet = null) }
    }
    fun closeEpubSettings() {
        epubReader.ready.value = false
        _ui.update { it.copy(screen = it.epubSettingsOrigin ?: "settings", epubSettingsOrigin = null) }
    }
    fun openImagePages() { _ui.update { it.copy(screen = "image_pages", imagePagesOrigin = it.screen, sheet = null) } }
    fun closeImagePages() { _ui.update { it.copy(screen = it.imagePagesOrigin ?: "folder", imagePagesOrigin = null) } }
    fun openImageSettings() { _ui.update { it.copy(screen = "image_settings", imageSettingsOrigin = it.screen, sheet = null) } }
    fun closeImageSettings() { _ui.update { it.copy(screen = it.imageSettingsOrigin ?: "settings", imageSettingsOrigin = null) } }
    fun closePdfSettings() { _ui.update { it.copy(screen = it.pdfSettingsOrigin ?: "settings", pdfSettingsOrigin = null) } }
    fun closeTxtSettings() {
        _ui.update { it.copy(screen = it.txtSettingsOrigin ?: "settings", txtSettingsOrigin = null) }
    }
    fun sheet(value: String?) {
        if (value == "search" && searchOrigin == null) searchOrigin = latest?.offset ?: 0
        _ui.update { it.copy(sheet = value) }
    }
    fun showAll() { _ui.update { it.copy(showAll = !it.showAll) } }
    fun clearError() { _ui.update { it.copy(error = null) } }
    private val metadataSlots = kotlinx.coroutines.sync.Semaphore(2)
    private val metadataCache = android.util.LruCache<String, DocumentEntry>(128)
    suspend fun bookMetadata(book: Book): DocumentEntry? {
        val key = "${book.uri}:${book.openedAt}"
        metadataCache.get(key)?.let { return it }
        metadataSlots.acquire()
        return try {
            withContext(Dispatchers.IO) { folders.info(book.uri) }.also { metadataCache.put(key, it) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { metadataSlots.release() }
    }
    fun bookInfo(book: Book) = action {
        info(bookMetadata(book) ?: DocumentEntry("", book.uri, book.name, book.format == "IMAGE_FOLDER", null, null,
            when (book.format) { "TXT" -> "text/plain"; "PDF" -> "application/pdf"; "EPUB" -> "application/epub+zip"; else -> "" }))
    }
    fun info(entry: DocumentEntry?) { _ui.update { it.copy(info = entry, sheet = if (entry != null) "info" else null) } }
    private fun message(e: Exception) = when (e) {
        is SecurityException -> "폴더 접근 권한이 없습니다. 시작 폴더를 다시 연결해 주세요."
        is java.io.FileNotFoundException -> "파일이나 폴더를 찾을 수 없습니다. 위치를 확인하고 다시 연결해 주세요."
        is ImageFailure -> e.message ?: "이미지를 열지 못했습니다."
        is PdfFailure -> e.message ?: "PDF를 열지 못했습니다."
        is EpubFailure -> e.message ?: "EPUB을 열지 못했습니다."
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
                // Keep grants used by reading history.
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
        val entry = ui.value.path.lastOrNull() ?: return if (app.value.tree.isNotEmpty()) loadRoot(app.value.tree) else Unit
        navigate(ui.value.path, entry)
    }
    fun foreground() {
        if (!ui.value.hasReader && !ui.value.folderBusy) refresh()
    }
    fun enter(entry: DocumentEntry) {
        if (entry.directory) navigate(ui.value.path + entry, entry)
        else if (entry.supported || ui.value.showAll) open(entry)
        else _ui.update { it.copy(error = "TXT·PDF·EPUB·이미지·압축파일을 읽을 수 있습니다.") }
    }
    fun up() {
        if (ui.value.path.size > 1) { val path = ui.value.path.dropLast(1); navigate(path, path.last()) }
    }
    private fun navigate(path: List<DocumentEntry>, entry: DocumentEntry) {
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            _ui.update { it.copy(folderBusy = true) }
            try { queryFolder(entry.uri, entry); _ui.update { it.copy(path = path) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(folderError = message(e)) } }
            finally { _ui.update { it.copy(folderBusy = false) } }
        }
    }
    fun disconnect() = action {
        cancelOpen()
        folderJob?.cancelAndJoin()
        val old = app.value.tree
        prefs.setFolder("", "")
        // Disconnecting the home folder does not invalidate books.
        _ui.update { it.copy(path = emptyList(), entries = emptyList(), folderError = null, sheet = null) }
    }
    fun clearCache() = action {
        if (ui.value.preparing != null) return@action
        withContext(Dispatchers.IO) { epubStore.clear(setOfNotNull(ui.value.epubReader?.document?.file, ui.value.changedEpub?.document?.file)) }
        withContext(Dispatchers.IO) { store.clear(setOfNotNull(ui.value.reader?.document?.version)); pdfStore.clear(setOfNotNull(ui.value.pdfReader?.document?.file, ui.value.changedPdf?.document?.file)); imageStore.clear() }
        _ui.update { it.copy(sheet = null) }
    }
    fun chooseResume(resume: Boolean) = action {
        val book = ui.value.resume ?: return@action
        _ui.update { it.copy(resume = null) }
        dao.consumeCandidate()
        if (resume) openBook(book)
    }
    private val externalUris = mutableSetOf<String>()
    private var externalLaunch = false
    fun openExternal(intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        externalLaunch = true
        action {
            require(uri.scheme == "content")
            if (intent.flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                runCatching { container.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            val entry = withContext(Dispatchers.IO) { folders.info(uri.toString()) }
            if (!entry.supported) { _ui.update { it.copy(error = "TXT·EPUB·PDF·이미지·압축파일을 선택해 주세요.") }; return@action }
            externalUris += entry.uri
            _ui.update { it.copy(resume = null) }
            open(entry)
        }
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
    private fun openEpub(entry: DocumentEntry, existing: Book?) {
        ++entryRequest
        openJob?.cancel()
        val generation = ++openGeneration
        ui.value.changedEpub?.document?.close()
        _ui.update { it.copy(changedEpub = null, preparing = "EPUB 확인 중", error = null, failedEntry = entry, sheet = null) }
        openJob = viewModelScope.launch {
            var document: EpubDocument? = null
            var retained = false
            try {
                flushPosition()
                val original = existing ?: dao.source(folders.key(entry.uri))
                val file = epubStore.prepare { folders.open(entry.uri) }
                document = epubEngine.open(file)
                ensureActive()
                if (generation != openGeneration) return@launch
                val book = (original ?: Book(UUID.randomUUID().toString(), folders.key(entry.uri), entry.uri, entry.name, ui.value.path.joinToString(" / ") { it.name }))
                    .copy(uri = entry.uri, name = entry.name, sourceKey = folders.key(entry.uri), format = "EPUB", version = file.nameWithoutExtension,
                        sourceHash = file.nameWithoutExtension, length = file.length(), pageCount = if (document.fixed) document.publication.readingOrder.size else null,
                        charset = "", charsetOverride = null)
                val saved = dao.epubPosition(book.id, book.version)
                val opened = OpenEpub(book, document, decodeEpubLocator(saved?.locator), initialCompleted = saved?.completed == true)
                if (original != null && original.sourceHash.isNotBlank() && original.sourceHash != book.sourceHash) {
                    _ui.update { it.copy(changedEpub = opened, preparing = null) }
                } else showEpub(opened)
                retained = true
                withContext(Dispatchers.IO) { epubStore.trim(setOfNotNull(file, ui.value.epubReader?.document?.file)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == openGeneration) _ui.update { it.copy(preparing = null, error = message(e)) } }
            finally { if (!retained) document?.close() }
        }
    }
    private suspend fun showEpub(opened: OpenEpub) {
        flushPosition(); epubReader.close(); pdfReader.close(); imageReader.close(); cancelSearch(); marksJob?.cancel()
        activeSession = 0; latest = null; dirty = false
        associateFolder(opened.book)
        epubReader.show(opened)
        _ui.update { it.copy(reader = null, pdfReader = null, imageReader = null, epubReader = opened, changedEpub = null,
            preparing = null, sheet = null, missingBook = null, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!opened.document.closed.get()) {
                        val cover = opened.document.publication.coverFitting(android.util.Size(128, 160))
                        if (cover != null) try { thumbnails.save(folders.info(opened.book.uri), cover) } finally { cover.recycle() }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Cover is optional. */ }
        }
    }
    fun acceptEpubChange(accept: Boolean) = action {
        val opened = ui.value.changedEpub ?: return@action
        _ui.update { it.copy(changedEpub = null) }
        if (accept) showEpub(opened.copy(initial = null)) else opened.document.close()
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
    fun reconnectImageFolder(uri: Uri) = action {
        val book = ui.value.missingBook?.takeIf { it.format == "IMAGE_FOLDER" } ?: return@action
        val entry = withContext(Dispatchers.IO) { folders.connect(uri) }
        val sameSource = dao.source(folders.key(entry.uri))
        if (sameSource != null && sameSource.id != book.id) {
            _ui.update { it.copy(error = "선택한 폴더에는 별도의 독서 기록이 있습니다. 최근 읽기에서 해당 폴더를 열어 주세요.") }
        } else open(entry, existing = book)
    }
    fun open(entry: DocumentEntry, override: String? = null, existing: Book? = null) {
        dismissReadingEnd()
        if (entry.epub && !entry.directory) { openEpub(entry, existing); return }
        if (entry.directory || entry.image || entry.archive || existing?.format == "IMAGE_FOLDER") { openImages(entry, existing); return }
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
                val isPdf = withContext(Dispatchers.IO) { PdfStore.signature(folders.open(entry.uri)) }
                if (isPdf) {
                    val file = pdfStore.prepare { folders.open(entry.uri) }
                    withContext(Dispatchers.IO) { pdfStore.trim(setOfNotNull(file, ui.value.pdfReader?.document?.file, ui.value.changedPdf?.document?.file)) }
                    val document = pdfEngine.open(file) { phase -> if (generation == openGeneration) _ui.update { it.copy(preparing = phase) } }
                    var retained = false
                    try {
                        ensureActive()
                        val old = original?.let { dao.pdfPosition(it.id, it.version) }
                        val book = (original ?: Book(UUID.randomUUID().toString(), folders.key(entry.uri), entry.uri, entry.name, ui.value.path.joinToString(" / ") { it.name }))
                            .copy(name = entry.name, uri = entry.uri, sourceKey = folders.key(entry.uri), format = "PDF", pageCount = document.pages.size,
                                version = document.version, sourceHash = document.version, length = file.length(), charset = "", charsetOverride = null)
                        val saved = dao.pdfPosition(book.id, book.version)
                        val opened = OpenPdf(book, document, (saved?.locator() ?: old?.locator()?.copy(completed = false) ?: PdfLocator()).constrained(document.pages.size))
                        if (original != null && original.sourceHash.isNotEmpty() && original.sourceHash != book.sourceHash) {
                            _ui.update { it.copy(changedPdf = opened, preparing = null) }
                        } else showPdf(opened)
                        retained = true; endOpenTrace()
                    } finally { if (!retained) withContext(NonCancellable) { document.close() } }
                    return@launch
                }
                if (entry.pdf) throw PdfFailure("PDF 형식이 아닌 파일입니다. 원본 파일을 확인해 주세요.")
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
                        version = document.version, sourceHash = document.sourceHash, length = document.length, format = "TXT", pageCount = null)
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
    private fun openImages(entry: DocumentEntry, existing: Book?) {
        ++entryRequest; openJob?.cancel(); val generation = ++openGeneration
        openJob = viewModelScope.launch {
            flushPosition()
            _ui.update { it.copy(preparing = "이미지 확인 중", error = null, failedEntry = entry, sheet = null) }
            var source: ImageSource? = null
            var retained = false
            try {
                val folder = if (entry.directory || existing?.format == "IMAGE_FOLDER") entry else if (entry.image && !externalUris.contains(entry.uri) && existing?.format != "IMAGE_SINGLE") withContext(Dispatchers.IO) { runCatching { folders.parentPath(entry.uri, ui.value.path).lastOrNull() }.getOrNull() } else null
                val origin = folder ?: entry
                val key = folders.key(origin.uri)
                val original = existing ?: dao.source(key)
                source = if (folder != null) {
                    val children = withContext(Dispatchers.IO) { folders.list(folder.uri, folder.id) }
                    ImageSource.folder(container, imageStore, children, folders)
                } else if (entry.image) ImageSource.folder(container, imageStore, listOf(entry), folders)
                else ImageSource.archive(container, imageStore, { folders.open(entry.uri) }, original?.sourceHash) { phase ->
                    if (generation == openGeneration) _ui.update { it.copy(preparing = phase) }
                }
                ensureActive()
                val pages = source.pages.value
                val book = (original ?: Book(UUID.randomUUID().toString(), key, origin.uri, origin.name, ui.value.path.joinToString(" / ") { it.name }))
                    .copy(sourceKey = key, uri = origin.uri, name = origin.name, format = if (folder != null) "IMAGE_FOLDER" else if (entry.image) "IMAGE_SINGLE" else "IMAGE_ARCHIVE",
                        pageCount = pages.size, version = source.version, sourceHash = source.version, length = pages.size.toLong(), charset = "", charsetOverride = null)
                val saved = dao.imagePosition(book.id, book.version) ?: original?.let { dao.imagePosition(it.id, it.version) }
                val explicit = if (entry.image) pages.indexOfFirst { it.key == entry.uri }.takeIf { it >= 0 } else null
                val initial = (explicit?.let { ImageLocator(page = it) } ?: saved?.locator() ?: ImageLocator()).constrained(pages)
                val opened = OpenImages(book, source, initial)
                val changed = original != null && original.version != source.version && explicit == null &&
                    (folder == null || saved != null && pages.none { it.key == saved.pageKey })
                if (changed) _ui.update { it.copy(changedImages = opened, preparing = null) }
                else showImages(opened)
                retained = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == openGeneration) _ui.update { it.copy(error = message(e), missingBook = existing.takeIf { e is SecurityException || e is java.io.FileNotFoundException }) } }
            finally {
                if (!retained) source?.close()
                if (generation == openGeneration) _ui.update { it.copy(preparing = null) }
            }
        }
    }
    private suspend fun showImages(opened: OpenImages) {
        associateFolder(opened.book)
        flushPosition(); epubReader.close(); pdfReader.close(); imageReader.close(); cancelSearch(); marksJob?.cancel()
        activeSession = 0; latest = null; dirty = false
        imageReader.show(opened)
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { folders.info(opened.book.uri) }
                val cover = opened.source.thumbnail(0)
                try { thumbnails.save(entry, cover) } finally { cover.recycle() }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Preview is optional. */ }
        }
        _ui.update { it.copy(reader = null, pdfReader = null, epubReader = null, imageReader = opened, changedImages = null,
            preparing = null, sheet = null, missingBook = null, error = null) }
    }
    fun acceptImageChange(mode: String) = action {
        val opened = ui.value.changedImages ?: return@action
        _ui.update { it.copy(changedImages = null) }
        if (mode == "cancel") opened.source.close()
        else showImages(if (mode == "first") opened.copy(initial = ImageLocator().constrained(opened.source.pages.value)) else opened)
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
    fun acceptChange(accept: Boolean) = action {
        val change = ui.value.changed ?: return@action
        _ui.update { it.copy(changed = null) }
        if (accept) showReader(change.opened)
    }
    private suspend fun showPdf(opened: OpenPdf) {
        associateFolder(opened.book)
        flushPosition(); epubReader.close(); pdfReader.close(); imageReader.close(); cancelSearch(); marksJob?.cancel(); activeSession = 0; latest = null; dirty = false
        pdfReader.show(opened)
        _ui.update { it.copy(reader = null, imageReader = null, epubReader = null, pdfReader = opened, changedPdf = null, preparing = null, sheet = null, missingBook = null, error = null) }
    }
    fun acceptPdfChange(mode: String) = action {
        val value = ui.value.changedPdf ?: return@action
        _ui.update { it.copy(changedPdf = null) }
        if (mode == "cancel") value.document.close()
        else showPdf(if (mode == "first") value.copy(initial = PdfLocator()) else value)
    }
    private suspend fun showReader(opened: OpenBook) {
        associateFolder(opened.book)
        epubReader.close(); pdfReader.close(); imageReader.close()
        activeSession = 0; sequence = 0; latest = opened.initial; dirty = false; searchOrigin = null
        _locator.value = opened.initial?.offset ?: 0
        cancelSearch(); marksJob?.cancel(); _seek.value = null
        _progressDisplay.value = ReadingProgress.text(opened.initial?.offset ?: 0, opened.document.length, opened.initial?.completed == true)
        _progress.value = _progressDisplay.value.fraction?.toFloat() ?: 0f
        _ui.update { it.copy(reader = opened, imageReader = null, pdfReader = null, epubReader = null, sheet = null, info = null, missingBook = null, preparing = null, error = null, changed = null, searchHits = emptyList(), searchResult = null, searchQuery = "", searchPageStart = 0) }
        marksJob = viewModelScope.launch { dao.bookmarks(opened.book.id).collect { _bookmarks.value = it } }
    }
    fun cancelOpen() { ++entryRequest; ++openGeneration; openJob?.cancel(); endOpenTrace(); _ui.update { it.copy(preparing = null) } }
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
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _ui.update { it.copy(error = "독서 기록을 시작하지 못했습니다. 저장 공간을 확인하고 책을 다시 열어 주세요.") }
            } finally { if (activationToken == token) activationToken = null }
        }
    }
    fun position(offset: Long, layout: String, fraction: Float, completed: Boolean) {
        val reader = ui.value.reader ?: return
        readingProgress(reader.token, completed)
        val at = offset.coerceIn(0, reader.document.length)
        latest = ReadingPosition(reader.book.id, reader.document.version, at, layoutKey = layout, fraction = fraction, completed = completed)
        _locator.value = at
        _progressDisplay.value = ReadingProgress.text(at, reader.document.length, completed)
        _progress.value = _progressDisplay.value.fraction?.toFloat() ?: 0f
        dirty = true
        idleSave?.cancel()
        idleSave = viewModelScope.launch { delay(300); flushPosition() }
    }
    suspend fun flushPosition() {
        epubReader.capture(); epubReader.flush()
        pdfReader.flush(); imageReader.flush()
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
        catch (e: Exception) {
            if (activeSession == session && ui.value.reader?.token == reader.token) dirty = true
            _ui.update { it.copy(error = "읽던 위치를 저장하지 못했습니다. 저장 공간을 확인해 주세요.") }
        }
    }
    fun background() = action { flushPosition() }
    fun closeReader() = action {
        settingsScope.value = SettingsScope.COMMON
        dismissReadingEnd(); announcedEnd = null
        cancelOpen(); cancelSearch(); idleSave?.cancel()
        flushPosition(); epubReader.close(); pdfReader.close(); imageReader.close(); dao.consumeCandidate(); activeSession = 0; marksJob?.cancel()
        _ui.update { it.copy(reader = null, imageReader = null, pdfReader = null, epubReader = null, sheet = null, searchResult = null, searchHits = emptyList(), searchQuery = "") }
        latest = null; searchOrigin = null; _seek.value = null
    }
    private fun readerToken() = ui.value.reader?.token ?: ui.value.pdfReader?.token ?: ui.value.imageReader?.token ?: ui.value.epubReader?.token
    fun readingProgress(token: String, completed: Boolean) {
        if (readerToken() != token || completed) return
        if (announcedEnd == token) announcedEnd = null
        if (ui.value.readingEnd?.token == token) dismissReadingEnd()
    }
    fun readingFinished(token: String) {
        if (readerToken() != token || announcedEnd == token || ui.value.preparing != null) return
        val book = currentBook ?: return
        val knownPath = ui.value.path
        announcedEnd = token
        readingEndJob?.cancel()
        _ui.update { it.copy(readingEnd = ReadingEnd(token)) }
        readingEndJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val path = folders.parentPath(book.uri, knownPath)
                    val parent = path.lastOrNull()
                    val entries = if (parent == null) emptyList() else sortEntries(folders.list(parent.uri, parent.id), settings.value.sort)
                    val current = folders.info(book.uri)
                    val next = nextReadingEntry(entries, current.id) { folder ->
                        ensureActive()
                        folders.list(folder.uri, folder.id).any { !it.directory && it.image }
                    }
                    ReadingEnd(token, loading = false, next = next, path = path, entries = entries)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ReadingEnd(token, loading = false, error = "다음 항목을 확인하지 못했어요. 폴더에서 파일과 접근 권한을 확인해 주세요.") }
            if (readerToken() == token) _ui.update { if (it.readingEnd?.token == token) it.copy(readingEnd = result) else it }
        }
    }
    fun dismissReadingEnd() { readingEndJob?.cancel(); _ui.update { it.copy(readingEnd = null) } }
    fun readNext() {
        val end = ui.value.readingEnd ?: return
        val next = end.next ?: return
        if (end.loading || readerToken() != end.token) return
        _ui.update { it.copy(path = end.path, entries = end.entries, screen = "folder", folderError = null) }
        open(next)
    }
    fun exitReadingEnd() {
        ui.value.readingEnd?.takeIf { it.path.isNotEmpty() }?.let { end ->
            _ui.update { it.copy(path = end.path, entries = end.entries, folderError = null) }
        }
        dismissReadingEnd(); screen("folder"); closeReader()
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
        _ui.update { it.copy(searchQuery = query, searching = query.isNotBlank(), searchError = null, searchHits = emptyList(), searchResult = null, searchIndex = 0, searchScanned = 0f, searchPageStart = 0) }
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
        val start = ui.value.searchPageStart + 100
        if (start >= result.count) return@action
        try {
            val hits = withContext(Dispatchers.IO) { result.page(start).map { it to doc.excerpt(it) } }
            if (ui.value.searchResult == result) _ui.update { it.copy(searchIndex = start, searchPageStart = start, searchHits = hits) }
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
    override fun onCleared() { epubReader.dispose(); ui.value.changedEpub?.document?.close(); pdfEngine.dispose(); imageReader.opened?.source?.close(); ui.value.changedImages?.source?.close(); super.onCleared() }
    suspend fun encodingPreview(encoding: String): String = withContext(Dispatchers.IO) {
        val uri = ui.value.reader?.book?.uri ?: ui.value.failedEntry?.uri ?: return@withContext ""
        folders.open(uri).use { input ->
            val reader = java.io.InputStreamReader(input, java.nio.charset.Charset.forName(encoding))
            val chars = CharArray(240); val n = reader.read(chars)
            if (n < 0) "빈 문서입니다." else String(chars, 0, n).removePrefix("\uFEFF")
        }
    }
}
