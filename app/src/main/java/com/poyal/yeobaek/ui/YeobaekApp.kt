@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.poyal.yeobaek.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.poyal.yeobaek.*
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.txt.TextStore
import com.poyal.yeobaek.pdf.*
import com.poyal.yeobaek.image.*
import java.util.Locale

@Composable fun YeobaekApp(vm: AppViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val app by vm.app.collectAsStateWithLifecycle()
    val txt by vm.txt.collectAsStateWithLifecycle()
    val editTxt by vm.editTxt.collectAsStateWithLifecycle()
    val editPdf by vm.editPdf.collectAsStateWithLifecycle()
    val editImage by vm.editImage.collectAsStateWithLifecycle()
    val image by vm.image.collectAsStateWithLifecycle()
    val imagePositions by vm.imagePositions.collectAsStateWithLifecycle()
    val pdf by vm.pdf.collectAsStateWithLifecycle()
    val pdfPositions by vm.pdfPositions.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val textPositions = if (!state.hasReader || state.resume != null) vm.positions.collectAsStateWithLifecycle().value else emptyList()
    val positions = textPositions + pdfPositions.mapNotNull { p -> books.find { it.id == p.bookId && it.version == p.version }?.let { book ->
        ReadingPosition(book.id, book.version, (p.locator().progress(book.pageCount ?: 1) * book.length).toLong(), completed = p.completed)
    } }
    val allPositions = positions + imagePositions.mapNotNull { p -> books.find { it.id == p.bookId && it.version == p.version }?.let { book ->
        ReadingPosition(book.id, book.version, (p.locator().progress(book.pageCount ?: 1) * book.length * 1000).toLong(), completed = p.completed)
    } }
    val displayBooks = books.map { if (it.format.startsWith("IMAGE_")) it.copy(length = it.length * 1000) else it }
    val appColors = LocalPaper.current
    val showingTxtSettings = state.screen == "txt_settings"
    val showingGeneralSettings = state.screen == "general_settings"
    val showingPdfSettings = state.screen == "pdf_settings"
    val showingImageSettings = state.screen == "image_settings"
    val showingImagePages = state.screen == "image_pages" && state.imageReader != null
    val showingViewerSettings = state.screen == "viewer_settings"
    val showingSettingsPage = showingTxtSettings || showingGeneralSettings || showingPdfSettings || showingViewerSettings || showingImageSettings
    val pageState = rememberSaveableStateHolder()
    val background = if (state.reader != null && !showingSettingsPage && state.sheet == null && state.error == null) readerColors(txt.paper, appColors) else if (state.pdfReader != null && !showingSettingsPage && state.sheet == null && state.error == null) pdfColors(pdf, appColors) else appColors
    val context = LocalContext.current
    ReaderWindowSettings(vm, state.hasReader && !showingSettingsPage)
    BackupDialogs(vm)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val updateState by vm.updates.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm.updates, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.updates.startSession()
            while (isActive) { vm.updates.refreshDownload(); delay(1000) }
        }
    }
    SideEffect {
        val activity = context as? Activity
        if (activity != null) WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = background.surface.luminance() > .5
            isAppearanceLightNavigationBars = background.surface.luminance() > .5
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::connect) }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::reconnectBook) }
    val imageFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::reconnectImageFolder) }
    val chooseFolder = { folderPicker.launch(app.tree.takeIf(String::isNotEmpty)?.let(Uri::parse)) }
    BackHandler(state.hasReader || state.screen != "folder" || state.path.size > 1 || state.sheet != null) {
        when { state.sheet != null -> vm.sheet(null); showingTxtSettings -> vm.closeTxtSettings(); showingPdfSettings -> vm.closePdfSettings(); showingImageSettings -> vm.closeImageSettings(); showingImagePages -> vm.closeImagePages(); showingGeneralSettings || showingViewerSettings -> vm.screen("settings"); state.hasReader -> vm.closeReader(); state.screen in listOf("about", "updates") -> vm.screen("settings"); state.screen != "folder" -> vm.screen("folder"); else -> vm.up() }
    }
    Surface(color = background.surface, modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            pageState.SaveableStateProvider(if (showingSettingsPage) state.screen else "main") {
                if (showingTxtSettings) TxtSettingsScreen(vm, editTxt)
                else if (showingPdfSettings) PdfSettingsScreen(vm, editPdf)
                else if (showingImageSettings) ImageSettingsScreen(vm, editImage)
                else if (showingImagePages) ImagePagesScreen(vm, state.imageReader!!, image)
                else if (showingViewerSettings) ViewerSettingsScreen(vm)
                else if (showingGeneralSettings) GeneralSettingsScreen(vm, app, chooseFolder)
                else if (state.imageReader != null) ImageReaderScreen(vm, state.imageReader!!, image, Modifier.weight(1f))
                else if (state.pdfReader != null) PdfReaderScreen(vm, state.pdfReader!!, pdf, Modifier.weight(1f))
                else if (state.reader != null) ReaderScreen(vm, state.reader!!, txt, Modifier.weight(1f))
                else {
                    when (state.screen) {
                        "settings" -> AppSettings(vm, Modifier.weight(1f))
                        "about" -> AboutScreen(vm.updates, { vm.screen("settings") }, Modifier.weight(1f))
                        "updates" -> UpdatesScreen(vm.updates, { vm.screen("settings") }, Modifier.weight(1f))
                        "recent" -> RecentScreen(vm, displayBooks, allPositions, Modifier.weight(1f))
                        else -> LibraryScreen(vm, state, app, displayBooks.firstOrNull(), allPositions, chooseFolder, Modifier.weight(1f))
                    }
                    Rule()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        listOf(Triple("folder", "홈", Icons.Outlined.Home), Triple("recent", "최근 읽기", Icons.Outlined.Schedule), Triple("settings", "설정", Icons.Outlined.Tune)).forEach { (id, label, icon) ->
                            val active = state.screen == id || (id == "settings" && state.screen in listOf("about", "updates"))
                            Column(Modifier.weight(1f).clickable { vm.screen(id) }.testTag("nav_$id").semantics { selected = active }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(icon, null, Modifier.size(22.dp), tint = if (active) appColors.accent else appColors.muted)
                                Text(label, fontSize = 11.sp, color = appColors.muted)
                            }
                        }
                    }
                }
            }
        }
    }
    if (!state.hasReader && !showingSettingsPage && state.resume == null && state.error == null && state.preparing == null && state.sheet == null) {
        UpdatePrompt(updateState, vm.updates::dismissPrompt) {
            vm.updates.dismissPrompt(); vm.screen("updates"); vm.updates.download()
        }
    }
    state.resume?.let { book ->
        val p = allPositions.firstOrNull { it.bookId == book.id && it.version == book.version }
        AlertDialog(shape = RoundedCornerShape(8.dp), modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { vm.chooseResume(false) }, icon = { Icon(Icons.Outlined.MenuBook, null) },
            title = { Heading("이어서\n읽으시겠습니까?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = appColors.alt, shape = RoundedCornerShape(4.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Filename(book.name); Small("마지막 읽은 위치 · ${percent(displayBooks.find { it.id == book.id } ?: book, p)}") } }
                Small("마지막으로 읽던 위치부터\n이야기를 이어갈 수 있어요.")
            } },
            confirmButton = { PaperButton("이어서 읽기", "resume_accept", onClick = { vm.chooseResume(true) }) },
            dismissButton = { PaperButton("폴더 보기", "resume_decline", outlined = true, onClick = { vm.chooseResume(false) }) }, containerColor = appColors.raised)
    }
    if (state.hasReader && !showingSettingsPage && !showingImagePages && state.sheet == null && state.error == null && state.preparing == null) {
        state.readingEnd?.let { ReadingEndDialog(it, vm::readNext, vm::exitReadingEnd, vm::dismissReadingEnd) }
    }
    state.preparing?.let { phase ->
        AlertDialog(shape = RoundedCornerShape(8.dp), modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = vm::cancelOpen, title = { Text(phase) }, text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Small("원본 파일은 그대로 두고 읽을 준비를 하고 있어요.") } },
            confirmButton = { PaperButton("취소", "prepare_cancel", outlined = true, onClick = vm::cancelOpen) })
    }
    state.changed?.let { change ->
        AlertDialog(shape = RoundedCornerShape(8.dp), modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { vm.acceptChange(false) }, title = { Text("본문이 달라졌어요") },
            text = { Text(if (change.exact) "같은 문장을 찾았습니다. 새 본문으로 이어 읽을까요? 이전 위치와 책갈피도 보관합니다." else "같은 문장을 찾지 못했습니다. 비슷한 진행률로 이동할까요? 이전 위치와 책갈피는 보관합니다.") },
            confirmButton = { PaperButton("새 본문 열기", "change_accept", onClick = { vm.acceptChange(true) }) }, dismissButton = { PaperButton("취소", "change_cancel", outlined = true, onClick = { vm.acceptChange(false) }) })
    }
    state.changedPdf?.let {
        AlertDialog(onDismissRequest = { vm.acceptPdfChange("cancel") }, title = { Text("PDF가 변경되었어요") },
            text = { Text("처음부터 읽거나 이전 페이지로 이동할 수 있습니다. 이전 위치와 책갈피는 보관합니다.") },
            confirmButton = { PaperButton("이전 페이지로", "pdf_change_resume", onClick = { vm.acceptPdfChange("resume") }) },
            dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton("취소", "pdf_change_cancel", outlined = true, onClick = { vm.acceptPdfChange("cancel") })
                PaperButton("처음부터", "pdf_change_first", outlined = true, onClick = { vm.acceptPdfChange("first") })
            } })
    }
    state.changedImages?.let {
        AlertDialog(onDismissRequest = { vm.acceptImageChange("cancel") }, title = { Text("이미지 구성이 변경되었어요") },
            text = { Text("처음부터 읽거나 이전 페이지 번호로 이동할 수 있습니다. 이전 위치와 책갈피는 보관합니다.") },
            confirmButton = { PaperButton("이전 페이지로", "image_change_resume", onClick = { vm.acceptImageChange("resume") }) },
            dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton("취소", "image_change_cancel", outlined = true, onClick = { vm.acceptImageChange("cancel") })
                PaperButton("처음부터", "image_change_first", outlined = true, onClick = { vm.acceptImageChange("first") })
            } })
    }
    state.error?.let { error ->
        AlertDialog(shape = RoundedCornerShape(8.dp), modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = vm::clearError, title = { Text("잠시 확인해 주세요") }, text = { Text(error) },
            confirmButton = { PaperButton("확인", "error_close", onClick = vm::clearError) },
            dismissButton = {
                if (state.missingBook?.format == "IMAGE_FOLDER") PaperButton("폴더 다시 찾기", "find_missing_folder", outlined = true, onClick = { vm.clearError(); imageFolderPicker.launch(null) })
                else if (state.missingBook != null) PaperButton("파일 다시 찾기", "find_missing_book", outlined = true, onClick = { vm.clearError(); bookPicker.launch(arrayOf("*/*")) })
                else if (state.failedEntry != null && error.contains("인코딩")) PaperButton("인코딩 선택", "error_encoding", outlined = true, onClick = { vm.clearError(); vm.sheet("encoding") })
            })
    }
    state.sheet?.let { kind ->
        ModalBottomSheet(onDismissRequest = { vm.sheet(null) }, containerColor = appColors.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("sheet_$kind").semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Small(if (kind in listOf("move", "encoding")) "TXT 전용" else "여백"); Text(when (kind) { "quick_settings" -> "빠른 읽기 설정"; "image_crop" -> "여백 자르기"; "move", "pdf_move" -> "읽기 위치 이동"; "search", "pdf_search" -> "본문 검색"; "bookmarks", "pdf_bookmarks", "image_bookmarks" -> "책갈피"; "encoding" -> "인코딩 선택"; "info" -> "책 정보"; else -> "독서 기록 관리" }, fontFamily = Serif, fontSize = 24.sp) }
                    Tool(Icons.Outlined.Close, "닫기", "sheet_close", onClick = { vm.sheet(null) })
                }
                Spacer(Modifier.height(20.dp))
                when (kind) {
                    "quick_settings" -> QuickReaderSettings(vm)
                    "image_crop" -> ImageCropSettings(vm)
                    "pdf_move", "pdf_search", "pdf_bookmarks" -> PdfSheet(vm, kind)
                    "image_bookmarks" -> ImageBookmarksSheet(vm)
                    "move" -> MoveSheet(vm.progress.collectAsStateWithLifecycle().value, vm::seekPercent) { vm.sheet(null) }
                    "search" -> SearchSheet(vm, state)
                    "bookmarks" -> BookmarkSheet(vm)
                    "encoding" -> EncodingSheet(vm)
                    "info" -> InfoSheet(vm, state)
                    else -> if (kind.startsWith("forget:")) books.find { it.id == kind.removePrefix("forget:") }?.let { book ->
                        Small("${book.name}\n읽기 위치와 책갈피를 지웁니다. 원본 파일은 그대로 유지됩니다.")
                        Spacer(Modifier.height(20.dp))
                        PaperButton("독서 기록 삭제", "forget_confirm", Modifier.fillMaxWidth(), destructive = true, onClick = { vm.forget(book) })
                    }
                }
            }
        }
    }
}

private fun percent(book: Book, position: ReadingPosition?): String {
    val ratio = if (position?.completed == true) 1.0 else if (book.length > 0) (position?.offset ?: 0).toDouble() / book.length else 0.0
    return String.format(Locale.US, "%.1f%%", ratio * 100)
}
@Composable private fun LibraryScreen(vm: AppViewModel, state: UiState, app: AppPreferences, recent: Book?, positions: List<ReadingPosition>, chooseFolder: () -> Unit, modifier: Modifier) {
    val p = LocalPaper.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val knownBooks by vm.books.collectAsStateWithLifecycle()
    var query by rememberSaveable(state.path.lastOrNull()?.uri) { mutableStateOf("") }
    val entries = remember(state.entries, query, settings.sort) { sortEntries(state.entries.filter { it.name.contains(query, ignoreCase = true) }, settings.sort) }
    val booksBySource = remember(knownBooks) { knownBooks.map { if (it.format.startsWith("IMAGE_")) it.copy(length = it.length * 1000) else it }.associateBy { it.sourceKey } }
    LazyColumn(modifier.fillMaxWidth().testTag("library_list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Small("나의 읽는 자리")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol(); Text("여백", Modifier.padding(start = 8.dp), fontFamily = Serif, fontSize = 31.sp)
                Text("이야기가 머무는 곳", Modifier.weight(1f).padding(start = 12.dp), fontSize = 9.sp, color = p.muted)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (settings.favorites.isNotEmpty()) item {
            Small("즐겨찾는 폴더")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { settings.favorites.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.openFavorite(folder) }, modifier = Modifier.testTag("favorite_${folder.name}")) { Icon(Icons.Outlined.StarOutline, null); Text(folder.name) }
                    Tool(Icons.Outlined.Close, "${folder.name} 즐겨찾기 해제", "remove_favorite_${folder.name}") { vm.removeFavorite(folder.uri) }
                }
            } }
        }
        if (app.tree.isEmpty() && state.path.isEmpty()) item {
            EmptyState("읽을 이야기가 있는\n폴더를 골라 주세요.", "파일은 기기에 그대로 두고 읽습니다.\n다음 실행에도 이 폴더에서 시작해요.") {
                PaperButton("시작 폴더 선택", "connect_folder", Modifier.fillMaxWidth(), onClick = chooseFolder)
            }
        } else {
            if (recent != null) item {
                val position = positions.firstOrNull { it.bookId == recent.id && it.version == recent.version }
                Surface(onClick = { vm.openBook(recent) }, color = p.soft, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, p.line), modifier = Modifier.fillMaxWidth().testTag("continue_card")) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.MenuBook, null, Modifier.size(16.dp), tint = p.accent); Small("다시 펼치는 이야기", Modifier.padding(start = 8.dp)) }
                        Text(if (recent.format == "IMAGE_FOLDER") recent.name else recent.name.substringBeforeLast(".", recent.name), Modifier.fillMaxWidth().padding(vertical = 12.dp), fontFamily = Serif, fontSize = 23.sp, lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(percent(recent, position), Modifier.weight(1f), color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp)) }
                        LinearProgressIndicator(progress = { if (recent.length > 0) ((position?.offset ?: 0).toFloat() / recent.length).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp), color = p.accent, trackColor = p.line)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.path.size > 1) Tool(Icons.Outlined.ArrowUpward, "상위 폴더", "folder_up", onClick = vm::up) else Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp), tint = p.accent)
                    Text(state.path.joinToString(" / ") { it.name }.ifEmpty { app.label }, Modifier.weight(1f).padding(start = 8.dp), color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val starred = settings.favorites.any { it.uri == state.path.lastOrNull()?.uri }
                    Tool(if (starred) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (starred) "즐겨찾기 해제" else "폴더 즐겨찾기", "folder_favorite", onClick = vm::favorite)
                    Tool(Icons.Outlined.Refresh, "새로고침", "folder_refresh", onClick = vm::refresh)
                }
                Rule()
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("폴더와 파일", Modifier.weight(1f), fontSize = 13.sp)
                    Tool(
                        if (state.showAll) Icons.Outlined.FilterAltOff else Icons.Outlined.FilterAlt,
                        if (state.showAll) "읽을 수 있는 파일만 보기" else "모든 파일 보기",
                        "show_all",
                        Modifier.semantics { stateDescription = if (state.showAll) "모든 파일 표시 중" else "TXT·PDF·이미지·압축파일 표시 중" },
                        onClick = vm::showAll
                    )
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("folder_search"), singleLine = true,
                    label = { Text("현재 폴더에서 찾기") }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색 지우기") } })
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FolderSort.entries.forEach { sort ->
                    Choice(when (sort) { FolderSort.NAME -> "이름순"; FolderSort.MODIFIED -> "최신순"; FolderSort.SIZE -> "큰 파일순" }, settings.sort == sort, "sort_$sort", Modifier.weight(1f)) { vm.setFolderSort(sort) }
                } }
                if (state.folderBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.folderError != null) item { EmptyState("폴더를 다시 연결해 주세요.", state.folderError) { PaperButton("폴더 다시 연결", "reconnect_folder", onClick = chooseFolder) } }
            else {
                val folders = entries.filter { it.directory }
                items(folders.chunked(2), key = { it.first().uri }) { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { entry -> Surface(onClick = { vm.enter(entry) }, color = p.alt, shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f).testTag("folder_${entry.name}")) {
                            Row(Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                                FolderFileThumbnail(vm, entry)
                                val book = booksBySource[vm.entryKey(entry.uri)]
                                val position = book?.let { b -> positions.firstOrNull { it.bookId == b.id && it.version == b.version } }
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Filename(entry.name)
                                    if (position != null) Small(if (position.completed) "완독" else "읽는 중 · ${percent(book!!, position)}")
                                }
                            }
                        } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                val files = entries.filter { !it.directory && (state.showAll || it.supported) }
                if (files.isEmpty() && folders.isEmpty() && !state.folderBusy) item { EmptyState(if (query.isEmpty()) "아직 책이 없어요." else "검색 결과가 없어요.", if (query.isEmpty()) "이 폴더에 TXT·PDF·이미지 또는 압축파일을 넣고 새로고침해 주세요." else "파일명이나 검색어를 확인해 주세요.") }
                items(files, key = { it.uri }) { file ->
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { vm.enter(file) }.testTag("file_${file.name}").heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                            FolderFileThumbnail(vm, file)
                            val book = booksBySource[vm.entryKey(file.uri)]
                            val position = book?.let { b -> positions.firstOrNull { it.bookId == b.id && it.version == b.version } }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Filename(file.name); Small(if (file.supported) "${file.formatLabel} · ${(file.size ?: 0) / 1024} KB" else "지원 여부 확인 필요")
                                if (position != null && book != null) Small(if (position.completed) "완독" else "읽는 중 · ${percent(book, position)}", Modifier.testTag("status_${file.name}"))
                            }
                            Tool(Icons.Outlined.MoreHoriz, "${file.name} 정보", "file_info_${file.name}", onClick = { vm.info(file) })
                        }; Rule()
                    }
                }
            }
        }
    }
}

@Composable private fun AppSettings(vm: AppViewModel, modifier: Modifier) {
    Column(modifier) {
        PageHeader("설정") { vm.screen("folder") }
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("app_settings").padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Small("나에게 맞는 여백"); Heading("읽는 자리를\n정해 보세요.")
            Spacer(Modifier.height(12.dp)); Rule()
            SettingsRow(Icons.Outlined.Tune, "일반 설정", "앱 테마 · 폴더와 저장 공간", "settings_general", { vm.screen("general_settings") })
            SettingsRow(Icons.Outlined.TextFields, "뷰어 설정", "TXT · PDF", "settings_viewer", { vm.screen("viewer_settings") })
            Rule()
            SettingsRow(Icons.Outlined.SystemUpdate, "업데이트", "현재 버전 ${BuildConfig.VERSION_NAME}", "settings_updates", { vm.screen("updates") })
            SettingsRow(Icons.Outlined.Info, "앱 정보·문의", "제작자 · GitHub · 변경 내역", "settings_about", { vm.screen("about") })
            Row(verticalAlignment = Alignment.CenterVertically) { Symbol(22.dp); Small("여백 · ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 8.dp)) }
        }
    }
}

@Composable private fun GeneralSettingsScreen(vm: AppViewModel, app: AppPreferences, chooseFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("general_settings_screen")) {
        PageHeader("일반 설정") { vm.screen("settings") }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("general_settings_options").padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            GeneralSettingsSection("앱 테마", Icons.Outlined.Palette, Modifier.testTag("theme_settings")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AppTheme.entries.forEach { theme ->
                    Choice(when (theme) { AppTheme.SYSTEM -> "기기 설정"; AppTheme.LIGHT -> "라이트"; AppTheme.DARK -> "다크" }, app.theme == theme, "theme_${theme.name}", Modifier.weight(1f)) { vm.action { vm.prefs.setTheme(theme) } }
                } }
            }
            BackupSettings(vm)
            StorageSettings(vm, app, chooseFolder)
        }
    }
}

@Composable private fun GeneralSettingsSection(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPaper.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = p.accent)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Surface(color = p.raised, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, p.line)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable private fun RecentScreen(vm: AppViewModel, books: List<Book>, positions: List<ReadingPosition>, modifier: Modifier) {
    val p = LocalPaper.current
    Column(modifier) { PageHeader("최근 읽기") { vm.screen("folder") }
        LazyColumn(Modifier.fillMaxSize().testTag("recent_list"), contentPadding = PaddingValues(24.dp)) {
            item { Heading("접어 둔 문장으로,\n다시 돌아오는 시간."); Spacer(Modifier.height(24.dp)); Small("${books.size}권의 읽기 기록"); Spacer(Modifier.height(24.dp)) }
            if (books.isEmpty()) item { EmptyState("첫 이야기를 펼쳐 보세요.", "읽기 시작한 책은 여기에 남습니다.") }
            items(books, key = { it.id }) { book ->
                val position = positions.firstOrNull { it.bookId == book.id && it.version == book.version }
                Column(Modifier.clickable { vm.openBook(book) }.testTag("recent_${book.name}").padding(vertical = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Description, null, Modifier.size(24.dp), tint = p.accent); Column(Modifier.weight(1f).padding(start = 12.dp)) { Filename(book.name); Small("${percent(book, position)} · 읽던 위치") }; Tool(Icons.Outlined.MoreHoriz, "독서 기록 관리", "manage_${book.name}", onClick = { vm.sheet("forget:${book.id}") }) }
                    LinearProgressIndicator(progress = { if (book.length > 0) ((position?.offset ?: 0).toFloat() / book.length).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(2.dp), color = p.accent, trackColor = p.line)
                    Small(book.folder, Modifier.padding(top = 8.dp))
                }; Rule()
            }
            item { Small("파일을 옮기지 않아도\n읽던 자리는 여기 남아 있어요.", Modifier.padding(top = 32.dp)) }
        }
    }
}

@Composable private fun TxtSettingsScreen(vm: AppViewModel, prefs: TxtPreferences) {
    Column(Modifier.fillMaxSize().testTag("txt_settings_screen")) {
        PageHeader("읽기 설정", vm::closeTxtSettings)
        TxtSettings(vm, prefs, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable private fun TxtSettings(vm: AppViewModel, prefs: TxtPreferences, modifier: Modifier = Modifier) {
    val colors = readerColors(prefs.paper, LocalPaper.current)
    Column(modifier.verticalScroll(rememberScrollState()).testTag("txt_options").padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsScopePicker(vm, "TXT")
        Small("TXT 전용")
        Surface(color = colors.surface, border = BorderStroke(1.dp, LocalPaper.current.line), shape = RoundedCornerShape(4.dp)) {
            Text("어떤 이야기는\n잠시 머물러도 좋았다.", Modifier.fillMaxWidth().padding(20.dp), style = prefs.textStyle(), color = colors.ink)
        }
        Text("글꼴", fontSize = 13.sp)
        TxtFontPicker(prefs.font) { font -> vm.changeSetting("TXT", "font", font) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("글자 크기", Modifier.weight(1f), fontSize = 13.sp); Tool(Icons.Outlined.Remove, "글자 작게", "font_smaller", outlined = true, onClick = { vm.changeSetting("TXT", "size", prefs.size - 1) }); Text(prefs.size.toInt().toString(), Modifier.testTag("font_size")); Tool(Icons.Outlined.Add, "글자 크게", "font_larger", outlined = true, onClick = { vm.changeSetting("TXT", "size", prefs.size + 1) }) }
        OptionSlider("줄간격", prefs.line, 1.3f..2.1f, "line_spacing") { vm.changeSetting("TXT", "line", it) }
        OptionSlider("좌우 여백", prefs.margin, 16f..40f, "text_margin") { vm.changeSetting("TXT", "margin", it) }
        Text("본문 색상", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { TxtPaper.entries.forEach { paper ->
            Choice(when (paper) { TxtPaper.FOLLOW_APP -> "앱 테마"; TxtPaper.LIGHT -> "라이트"; TxtPaper.SEPIA -> "세피아"; TxtPaper.DARK -> "다크" }, prefs.paper == paper, "paper_${paper.name}", Modifier.weight(1f)) { vm.changeSetting("TXT", "paper", paper) }
        } }
        Rule()
        PaperButton("TXT 설정 기본값 복원", "txt_reset", Modifier.fillMaxWidth(), outlined = true, destructive = true, onClick = { vm.resetSettings("TXT") })
        Small("시작 폴더와 독서 기록은 유지됩니다.")
    }
}
@Composable private fun OptionSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, commit: (Float) -> Unit) {
    var preview by remember(value) { mutableFloatStateOf(value) }
    Column { Row { Text(label, Modifier.weight(1f), fontSize = 13.sp); Small(String.format(Locale.US, "%.2f", preview)) }
        Slider(preview, { preview = it }, Modifier.testTag(tag).semantics { contentDescription = label }, valueRange = range, onValueChangeFinished = { commit(preview) }) }
}
@Composable private fun MoveSheet(progress: Float, apply: (Float) -> Unit, cancel: () -> Unit) {
    var target by rememberSaveable { mutableFloatStateOf(progress) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Small("이동할 위치", Modifier.weight(1f)); Text(String.format(Locale.US, "%.1f%%", target * 100), fontFamily = Serif, fontSize = 32.sp, color = LocalPaper.current.accent) }
    Slider(target, { target = it }, Modifier.testTag("move_slider").semantics { contentDescription = "읽기 위치" }, valueRange = 0f..1f)
    Row { Small("처음 · 0%", Modifier.weight(1f)); Small("끝 · 100%") }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PaperButton("취소", "move_cancel", Modifier.weight(1f), true, onClick = cancel); PaperButton("이 위치로 이동", "move_apply", Modifier.weight(1f), onClick = { apply(target) }) }
}
@Composable private fun SearchSheet(vm: AppViewModel, state: UiState) {
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("search_input"), label = { Text("본문에서 찾을 문장") }, singleLine = true)
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperButton("검색", "search_submit", onClick = { vm.search(query) })
        if (state.searching) PaperButton("검색 취소", "search_cancel", outlined = true, onClick = vm::cancelSearch)
        PaperButton("원래 위치로", "search_return", outlined = true, onClick = vm::returnFromSearch)
    }
    if (state.searching) { LinearProgressIndicator(progress = { state.searchScanned }, modifier = Modifier.fillMaxWidth()); Small("본문에서 찾고 있어요.") }
    state.searchError?.let { Text(it, color = LocalPaper.current.danger) }
    state.searchResult?.let { result ->
        Small(if (result.count == 0L) "검색 결과가 없습니다." else "${result.count}개의 문장 · ${state.searchIndex + 1}번째")
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("이전", "search_previous", Modifier.weight(1f), outlined = true, enabled = state.searchIndex > 0, onClick = { vm.searchResult(state.searchIndex - 1) })
            PaperButton("다음", "search_next", Modifier.weight(1f), outlined = true, enabled = state.searchIndex + 1 < result.count, onClick = { vm.searchResult(state.searchIndex + 1) })
        }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp).testTag("search_results")) {
        itemsIndexed(state.searchHits, key = { _, hit -> hit.first }) { index, (offset, excerpt) ->
            Column(Modifier.fillMaxWidth().clickable {
                if (state.searchResult != null) vm.searchResult(state.searchHitIndex(index))
                else { vm.seek(offset); vm.sheet(null) }
            }.padding(vertical = 16.dp)) { Text(excerpt, fontSize = 14.sp); Rule() }
        }
        if (state.searchResult != null && state.searchPageStart + 100 < state.searchResult.count) item { PaperButton("다음 100개 결과", "search_more", Modifier.fillMaxWidth(), outlined = true, onClick = vm::moreSearch) }
    }
}
@Composable private fun BookmarkSheet(vm: AppViewModel) {
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    if (marks.isEmpty()) EmptyState("접어 둔 문장이 없어요.", "독서 화면 위쪽의 책갈피를 눌러 남겨 보세요.")
    else LazyColumn(Modifier.heightIn(max = 430.dp).testTag("bookmark_list")) { items(marks, key = { it.id }) { mark ->
        Row(verticalAlignment = Alignment.CenterVertically) { Text(mark.excerpt.ifEmpty { "빈 문장" }, Modifier.weight(1f).clickable { vm.openBookmark(mark) }.padding(vertical = 16.dp, horizontal = 8.dp), fontSize = 14.sp); Tool(Icons.Outlined.DeleteOutline, "책갈피 삭제", "bookmark_delete", destructive = true, onClick = { vm.deleteBookmark(mark.id) }) }; Rule()
    } }
}
@Composable private fun EncodingSheet(vm: AppViewModel) {
    var encoding by rememberSaveable { mutableStateOf("UTF-8") }
    var preview by remember { mutableStateOf("") }
    LaunchedEffect(encoding) { preview = try { vm.encodingPreview(encoding) } catch (e: Exception) { "원본을 다시 확인해 주세요." } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Small("현재 책에만 적용됩니다. 아래 문장이 올바르게 보이는지 확인해 주세요.")
        TextStore.charsets.forEach { charset -> Choice(if (charset == "x-windows-949") "CP949" else charset, charset == encoding, "encoding_$charset", Modifier.fillMaxWidth()) { encoding = charset } }
        Surface(color = LocalPaper.current.alt) { Text(preview, Modifier.fillMaxWidth().padding(16.dp), fontSize = 14.sp, maxLines = 5) }
        PaperButton("이 인코딩 적용", "encoding_apply", Modifier.fillMaxWidth(), onClick = { vm.retryEncoding(encoding) })
    }
}
@Composable private fun InfoSheet(vm: AppViewModel, state: UiState) {
    val reader = state.reader
    val entry = state.info
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(entry?.name ?: reader?.book?.name ?: "책 정보", fontSize = 16.sp)
        Small(if (reader != null) "TXT · ${reader.document.charset}\n${reader.document.length} 문자\n${reader.book.folder}" else "${(entry?.size ?: 0) / 1024} KB\n${entry?.mime ?: ""}")
        if (reader != null) PaperButton("글자가 깨져 보이나요?", "info_encoding", Modifier.fillMaxWidth(), true, onClick = { vm.sheet("encoding") })
        Small("원본 파일은 기기의 폴더에 그대로 있습니다.")
    }
}
@Composable private fun StorageSettings(vm: AppViewModel, app: AppPreferences, chooseFolder: () -> Unit) {
    val p = LocalPaper.current
    var confirm by remember { mutableStateOf(false) }
    GeneralSettingsSection("폴더와 저장 공간", Icons.Outlined.FolderOpen, Modifier.testTag("storage_settings")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = chooseFolder).testTag("settings_folder").heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("시작 폴더", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(app.label.ifEmpty { "선택하지 않음" }, color = p.accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ChevronRight, "시작 폴더 변경", Modifier.size(20.dp), tint = p.muted)
            }
            Small("앱을 새로 열 때 이 폴더에서 시작합니다.")
        }
        Rule()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperButton("읽기 캐시 정리", "clear_cache", Modifier.fillMaxWidth(), outlined = true, destructive = true, onClick = vm::clearCache)
            Small("읽기 캐시만 정리합니다. 원본 파일과 독서 위치, 책갈피는 유지됩니다.")
        }
        Rule()
        if (confirm) {
            Small("폴더 연결을 해제할까요? 독서 기록과 원본 파일은 유지됩니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton("취소", "disconnect_cancel", Modifier.weight(1f), true, onClick = { confirm = false })
                PaperButton("연결 해제 확인", "disconnect_confirm", Modifier.weight(1f), destructive = true, onClick = { confirm = false; vm.disconnect() })
            }
        } else PaperButton("폴더 연결 해제", "disconnect", Modifier.fillMaxWidth(), outlined = true, destructive = true, onClick = { confirm = true })
    }
}
