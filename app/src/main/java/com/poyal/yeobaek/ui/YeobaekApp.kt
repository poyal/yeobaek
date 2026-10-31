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
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val editEpub by vm.editEpub.collectAsStateWithLifecycle()
    val epub by vm.epub.collectAsStateWithLifecycle()
    val epubPositions by vm.epubPositions.collectAsStateWithLifecycle()
    val editImage by vm.editImage.collectAsStateWithLifecycle()
    val image by vm.image.collectAsStateWithLifecycle()
    val imagePositions by vm.imagePositions.collectAsStateWithLifecycle()
    val pdf by vm.pdf.collectAsStateWithLifecycle()
    val pdfPositions by vm.pdfPositions.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val textPositions = if (!state.hasReader || state.resume != null) vm.positions.collectAsStateWithLifecycle().value else emptyList()
    val progress = remember(books, textPositions, pdfPositions, imagePositions, epubPositions) {
        readingProgressByBook(books, textPositions, pdfPositions, imagePositions, epubPositions)
    }
    val appColors = LocalPaper.current
    val showingTxtSettings = state.screen == "txt_settings"
    val showingGeneralSettings = state.screen == "general_settings"
    val showingPdfSettings = state.screen == "pdf_settings"
    val showingEpubSettings = state.screen == "epub_settings"
    val showingImageSettings = state.screen == "image_settings"
    val showingImagePages = state.screen == "image_pages" && state.imageReader != null
    val showingSettingsPage = showingTxtSettings || showingGeneralSettings || showingPdfSettings || showingImageSettings || showingEpubSettings
    val pageState = rememberSaveableStateHolder()
    val background = if (state.epubReader != null && !showingSettingsPage && state.sheet == null && state.error == null) epubColors(epub, state.epubReader!!.document.fixed, appColors) else if (state.reader != null && !showingSettingsPage && state.sheet == null && state.error == null) readerColors(txt.paper, appColors) else if (state.pdfReader != null && !showingSettingsPage && state.sheet == null && state.error == null) pdfColors(pdf, appColors) else appColors
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
        when { state.sheet != null -> vm.sheet(null); showingEpubSettings -> vm.closeEpubSettings(); showingTxtSettings -> vm.closeTxtSettings(); showingPdfSettings -> vm.closePdfSettings(); showingImageSettings -> vm.closeImageSettings(); showingImagePages -> vm.closeImagePages(); showingGeneralSettings -> vm.screen("settings"); state.hasReader -> vm.closeReader(); state.screen in listOf("about", "updates") -> vm.screen("settings"); state.screen != "folder" -> vm.screen("folder"); else -> vm.up() }
    }
    Surface(color = background.surface, modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            pageState.SaveableStateProvider(if (showingSettingsPage) state.screen else "main") {
                if (showingTxtSettings) TxtSettingsScreen(vm, editTxt)
                else if (showingEpubSettings) EpubSettingsScreen(vm, editEpub)
                else if (showingPdfSettings) PdfSettingsScreen(vm, editPdf)
                else if (showingImageSettings) ImageSettingsScreen(vm, editImage)
                else if (showingImagePages) ImagePagesScreen(vm, state.imageReader!!, image)
                else if (showingGeneralSettings) GeneralSettingsScreen(vm, app, chooseFolder)
                else if (state.epubReader != null) EpubReaderScreen(vm, state.epubReader!!, epub, Modifier.weight(1f))
                else if (state.imageReader != null) ImageReaderScreen(vm, state.imageReader!!, image, Modifier.weight(1f))
                else if (state.pdfReader != null) PdfReaderScreen(vm, state.pdfReader!!, pdf, Modifier.weight(1f))
                else if (state.reader != null) ReaderScreen(vm, state.reader!!, txt, Modifier.weight(1f))
                else {
                    pageState.SaveableStateProvider("list:${state.screen}") {
                        when (state.screen) {
                            "settings" -> AppSettings(vm, Modifier.weight(1f))
                            "about" -> AboutScreen(vm.updates, { vm.screen("settings") }, Modifier.weight(1f))
                            "updates" -> UpdatesScreen(vm.updates, { vm.screen("settings") }, Modifier.weight(1f))
                            "recent" -> RecentScreen(vm, books, progress, Modifier.weight(1f))
                            else -> LibraryScreen(vm, state, app, books.firstOrNull(), progress, chooseFolder, Modifier.weight(1f))
                        }
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
        val p = progress[book.id] ?: ReadingProgress.Unknown
        AlertDialog(shape = RoundedCornerShape(8.dp), modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { vm.chooseResume(false) }, icon = { Icon(Icons.Outlined.MenuBook, null) },
            title = { Heading("이어서\n읽으시겠습니까?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = appColors.alt, shape = RoundedCornerShape(4.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Filename(book.name); Small("마지막 읽은 위치 · ${p.label}") } }
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
    state.changedEpub?.let {
        AlertDialog(onDismissRequest = { vm.acceptEpubChange(false) }, title = { Text("EPUB이 변경되었어요") },
            text = { Text("새 본문을 처음부터 열까요? 이전 위치와 책갈피는 보관합니다.") },
            confirmButton = { PaperButton("처음부터 열기", "epub_change_first", onClick = { vm.acceptEpubChange(true) }) },
            dismissButton = { PaperButton("취소", "epub_change_cancel", outlined = true, onClick = { vm.acceptEpubChange(false) }) })
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // A nested-scroll fling during show() can settle a still-Hidden sheet and dismiss it.
        // The content remains scrollable while the sheet finishes opening.
        ModalBottomSheet(onDismissRequest = { vm.sheet(null) }, containerColor = appColors.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), sheetState = sheetState, sheetGesturesEnabled = sheetState.isVisible, modifier = Modifier.testTag("sheet_$kind").semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Small(if (kind in listOf("move", "encoding")) "TXT 전용" else "여백"); Text(when (kind) { "epub_contents" -> "목차"; "quick_settings" -> "빠른 읽기 설정"; "image_crop" -> "여백 자르기"; "move", "pdf_move" -> "읽기 위치 이동"; "search", "pdf_search", "epub_search" -> "본문 검색"; "bookmarks", "pdf_bookmarks", "image_bookmarks", "epub_bookmarks" -> "책갈피"; "encoding" -> "인코딩 선택"; "info" -> "책 정보"; else -> "독서 기록 관리" }, fontFamily = Serif, fontSize = 24.sp) }
                    Tool(Icons.Outlined.Close, "닫기", "sheet_close", onClick = { vm.sheet(null) })
                }
                Spacer(Modifier.height(20.dp))
                when (kind) {
                    "quick_settings" -> QuickReaderSettings(vm)
                    "image_crop" -> ImageCropSettings(vm)
                    "pdf_move", "pdf_search", "pdf_bookmarks" -> PdfSheet(vm, kind)
                    "epub_contents", "epub_search", "epub_bookmarks" -> EpubSheet(vm, kind)
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

@Composable private fun LibraryScreen(vm: AppViewModel, state: UiState, app: AppPreferences, recent: Book?, progress: Map<String, ReadingProgress>, chooseFolder: () -> Unit, modifier: Modifier) {
    val p = LocalPaper.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val knownBooks by vm.books.collectAsStateWithLifecycle()
    var query by rememberSaveable(state.path.lastOrNull()?.uri) { mutableStateOf("") }
    val entries = remember(state.entries, query, settings.sort) { sortEntries(state.entries.filter { it.name.contains(query, ignoreCase = true) }, settings.sort) }
    val booksBySource = remember(knownBooks) { knownBooks.associateBy { it.sourceKey } }
    val libraryList = key(state.path.lastOrNull()?.uri) { rememberLazyListState() }
    LazyColumn(modifier.fillMaxWidth().testTag("library_list"), state = libraryList, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Small("나의 읽는 자리")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol(); Text("여백", Modifier.padding(start = 8.dp), fontFamily = Serif, fontSize = 31.sp)
                Text("이야기가 머무는 곳", Modifier.weight(1f).padding(start = 12.dp), fontSize = 9.sp, color = p.muted)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (app.tree.isEmpty() && state.path.isEmpty()) item {
            EmptyState("읽을 이야기가 있는\n폴더를 골라 주세요.", "파일은 기기에 그대로 두고 읽습니다.\n다음 실행에도 이 폴더에서 시작해요.") {
                PaperButton("시작 폴더 선택", "connect_folder", Modifier.fillMaxWidth(), onClick = chooseFolder)
            }
        } else {
            if (recent != null) item {
                val position = progress[recent.id] ?: ReadingProgress.Unknown
                Surface(onClick = { vm.openBook(recent) }, color = p.soft, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, p.line), modifier = Modifier.fillMaxWidth().testTag("continue_card")) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.MenuBook, null, Modifier.size(16.dp), tint = p.accent); Small("다시 펼치는 이야기", Modifier.padding(start = 8.dp)) }
                        Text(if (recent.format == "IMAGE_FOLDER") recent.name else recent.name.substringBeforeLast(".", recent.name), Modifier.fillMaxWidth().padding(vertical = 12.dp), fontFamily = Serif, fontSize = 23.sp, lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(position.label, Modifier.weight(1f), color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp)) }
                        LinearProgressIndicator(progress = { position.fraction?.toFloat() ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp), color = p.accent, trackColor = p.line)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.path.size > 1) Tool(Icons.Outlined.ArrowUpward, "상위 폴더", "folder_up", onClick = vm::up) else Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp), tint = p.accent)
                    Text(state.path.joinToString(" / ") { it.name }.ifEmpty { app.label }, Modifier.weight(1f).padding(start = 8.dp), color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Tool(Icons.Outlined.Refresh, "새로고침", "folder_refresh", onClick = vm::refresh)
                }
                Rule()
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("폴더와 파일", Modifier.weight(1f), fontSize = 13.sp)
                    Tool(
                        if (state.showAll) Icons.Outlined.FilterAltOff else Icons.Outlined.FilterAlt,
                        if (state.showAll) "읽을 수 있는 파일만 보기" else "모든 파일 보기",
                        "show_all",
                        Modifier.semantics { stateDescription = if (state.showAll) "모든 파일 표시 중" else "TXT·EPUB·PDF·이미지·압축파일 표시 중" },
                        onClick = vm::showAll
                    )
                }
                LibrarySearchRow(query = query, onQueryChange = { query = it },
                    options = listOf(FolderSort.NAME to "이름순", FolderSort.MODIFIED to "최신순", FolderSort.SIZE to "큰 파일순"),
                    selection = settings.sort, onSelectionChange = vm::setFolderSort,
                    selectorTag = "folder_sort", selectorDescription = "폴더와 파일 정렬", optionTag = { "sort_$it" },
                    searchTag = "folder_search", searchDescription = "현재 폴더에서 찾기", modifier = Modifier.padding(bottom = 12.dp))
                if (state.folderBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.folderError != null) item { EmptyState("폴더를 다시 연결해 주세요.", state.folderError) { PaperButton("폴더 다시 연결", "reconnect_folder", onClick = chooseFolder) } }
            else {
                val folders = entries.filter { it.directory }
                items(folders, key = { it.uri }) { entry ->
                    Surface(onClick = { vm.enter(entry) }, color = p.alt, shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth().testTag("folder_${entry.name}")) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { FolderFileThumbnail(vm, entry) }
                            Text(entry.name, Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), fontSize = 13.sp,
                                lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val book = booksBySource[vm.entryKey(entry.uri)]?.takeIf { it.format == "IMAGE_FOLDER" }
                            book?.let { ReadingProgressCircle(progress[it.id] ?: ReadingProgress.Unknown, Modifier.testTag("status_${entry.name}")) }
                            Spacer(Modifier.width(48.dp))
                        }
                    }
                }
                val files = entries.filter { !it.directory && (state.showAll || it.supported) }
                if (files.isEmpty() && folders.isEmpty() && !state.folderBusy) item { EmptyState(if (query.isEmpty()) "아직 책이 없어요." else "검색 결과가 없어요.", if (query.isEmpty()) "이 폴더에 TXT·EPUB·PDF·이미지 또는 압축파일을 넣고 새로고침해 주세요." else "파일명이나 검색어를 확인해 주세요.") }
                items(files, key = { it.uri }) { file ->
                    Column {
                        val book = booksBySource[vm.entryKey(file.uri)]
                        BookFileRow(file.name, fileMetadata(file), progress = if (file.supported) book?.let { progress[it.id] } ?: ReadingProgress.Unread else null,
                            tag = "file_${file.name}", menuTag = "file_info_${file.name}", onOpen = { vm.enter(file) }, onInfo = { vm.info(file) },
                            onManage = book?.let { { vm.sheet("forget:${it.id}") } }) { FolderFileThumbnail(vm, file) }
                        Rule()
                    }
                }
            }
        }
    }
}

@Composable private fun AppSettings(vm: AppViewModel, modifier: Modifier) {
    SettingsPage("설정", "settings_screen", { vm.screen("folder") }, modifier, "app_settings") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Small("나에게 맞는 여백"); Heading("편안한 읽기를 위해.")
        }
        SettingsCard(contentPadding = PaddingValues(4.dp)) {
            SettingsRow(Icons.Outlined.Tune, "일반 설정", "앱 테마 · 백업 · 저장 공간", "settings_general", { vm.screen("general_settings") })
        }
        SettingsCard(contentPadding = PaddingValues(4.dp), spacing = 0.dp) {
            SettingsRow(Icons.Outlined.TextFields, "TXT 뷰어", "글꼴 · 크기 · 간격 · 본문 색상", "settings_txt", vm::openTxtSettings)
            Rule()
            SettingsRow(Icons.Outlined.PictureAsPdf, "PDF 뷰어", "화면 맞춤 · 페이지 간격 · 색상 반전", "settings_pdf", vm::openPdfSettings)
            Rule()
            SettingsRow(Icons.Outlined.MenuBook, "EPUB 뷰어", "읽기 방식 · 글꼴 · 본문 색상", "settings_epub", { vm.openEpubSettings() })
            Rule()
            SettingsRow(Icons.Outlined.Collections, "이미지 뷰어", "넘김 방식 · 페이지 배치 · 여백", "settings_image", vm::openImageSettings)
        }
        SettingsCard(contentPadding = PaddingValues(4.dp), spacing = 0.dp) {
            SettingsRow(Icons.Outlined.SystemUpdate, "업데이트", "현재 버전 ${BuildConfig.VERSION_NAME}", "settings_updates", { vm.screen("updates") })
            Rule()
            SettingsRow(Icons.Outlined.Info, "앱 정보·문의", "제작자 · GitHub · 변경 내역", "settings_about", { vm.screen("about") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Symbol(22.dp); Small("여백 · ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 8.dp)) }
    }
}

@Composable private fun GeneralSettingsScreen(vm: AppViewModel, app: AppPreferences, chooseFolder: () -> Unit) {
    SettingsPage("일반 설정", "general_settings_screen", { vm.screen("settings") }, contentTag = "general_settings_options") {
        SettingsSection("앱 테마", Icons.Outlined.Palette, Modifier.testTag("theme_settings")) {
            ChoiceGroup(AppTheme.entries, app.theme, label = { when (it) { AppTheme.SYSTEM -> "기기 설정"; AppTheme.LIGHT -> "라이트"; AppTheme.DARK -> "다크" } },
                tag = { "theme_$it" }) { vm.action { vm.prefs.setTheme(it) } }
        }
        BackupSettings(vm)
        StorageSettings(vm, app, chooseFolder)
    }
}

@Composable private fun RecentScreen(vm: AppViewModel, books: List<Book>, progress: Map<String, ReadingProgress>, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    val filters = listOf("ALL" to "전체", "READING" to "읽는 중", "COMPLETED" to "완독")
    val visible = remember(books, progress, query, filter) { books.filter {
        it.name.contains(query, ignoreCase = true) && (filter == "ALL" || progress[it.id]?.status?.name == filter)
    } }
    Column(modifier) {
        PageHeader("최근 읽기") { vm.screen("folder") }
        LazyColumn(Modifier.fillMaxSize().testTag("recent_list"), contentPadding = PaddingValues(24.dp)) {
            item {
                LibrarySearchRow(query = query, onQueryChange = { query = it }, options = filters,
                    selection = filter, onSelectionChange = { filter = it },
                    selectorTag = "recent_filter", selectorDescription = "독서 상태 필터", optionTag = { "recent_filter_$it" },
                    searchTag = "recent_search", searchDescription = "최근 읽은 파일 검색", modifier = Modifier.padding(bottom = 12.dp))
                Small("${visible.size}권 · 최근 읽은 순서", Modifier.padding(bottom = 12.dp))
                Rule()
            }
            if (visible.isEmpty()) item { EmptyState(if (books.isEmpty()) "첫 이야기를 펼쳐 보세요." else "검색 결과가 없어요.",
                if (books.isEmpty()) "읽기 시작한 책은 여기에 남습니다." else "검색어나 독서 상태 필터를 확인해 주세요.") }
            items(visible, key = { it.id }) { book ->
                RecentBookRow(vm, book, progress[book.id] ?: ReadingProgress.Unknown)
                Rule()
            }
        }
    }
}

@Composable private fun TxtSettingsScreen(vm: AppViewModel, prefs: TxtPreferences) {
    SettingsPage("TXT 읽기 설정", "txt_settings_screen", vm::closeTxtSettings, contentTag = "txt_options") {
        TxtSettings(vm, prefs)
    }
}

@Composable private fun TxtSettings(vm: AppViewModel, prefs: TxtPreferences) {
    val colors = readerColors(prefs.paper, LocalPaper.current)
    SettingsSection("설정 적용 범위", Icons.Outlined.Tune) { SettingsScopePicker(vm, "TXT", showTitle = false, showReset = false) }
    Surface(color = colors.surface, border = BorderStroke(1.dp, LocalPaper.current.line), shape = RoundedCornerShape(12.dp)) {
        Text("어떤 이야기는\n잠시 머물러도 좋았다.", Modifier.fillMaxWidth().padding(20.dp), style = prefs.textStyle(), color = colors.ink)
    }
    ReaderGestureSettings(vm, "TXT", prefs.menuVisibility)
    SettingsSection("글꼴과 크기", Icons.Outlined.TextFields) {
        TxtFontPicker(prefs.font) { font -> vm.changeSetting("TXT", "font", font) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("글자 크기", Modifier.weight(1f), fontSize = 13.sp); Tool(Icons.Outlined.Remove, "글자 작게", "font_smaller", outlined = true, onClick = { vm.changeSetting("TXT", "size", prefs.size - 1) }); Text(prefs.size.toInt().toString(), Modifier.testTag("font_size")); Tool(Icons.Outlined.Add, "글자 크게", "font_larger", outlined = true, onClick = { vm.changeSetting("TXT", "size", prefs.size + 1) }) }
    }
    SettingsSection("간격과 여백", Icons.Outlined.FormatLineSpacing) {
        OptionSlider("줄간격", prefs.line, 1.3f..2.1f, "line_spacing") { vm.changeSetting("TXT", "line", it) }
        OptionSlider("좌우 여백", prefs.margin, 16f..40f, "text_margin") { vm.changeSetting("TXT", "margin", it) }
    }
    SettingsSection("본문 색상", Icons.Outlined.Palette) {
        ChoiceGroup(TxtPaper.entries, prefs.paper, label = { when (it) { TxtPaper.FOLLOW_APP -> "앱 테마"; TxtPaper.LIGHT -> "라이트"; TxtPaper.SEPIA -> "세피아"; TxtPaper.DARK -> "다크" } },
            tag = { "paper_$it" }) { vm.changeSetting("TXT", "paper", it) }
    }
    SettingsSection("설정 초기화", Icons.Outlined.RestartAlt) {
        ReaderSettingsReset(vm, "TXT")
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
    val resultsList = key(state.searchQuery, state.searchPageStart) { rememberLazyListState() }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp).testTag("search_results"), state = resultsList) {
        itemsIndexed(state.searchHits, key = { _, hit -> hit.first }) { index, (offset, excerpt) ->
            Column(Modifier.fillMaxWidth().clickable {
                if (state.searchResult != null) vm.searchResult(state.searchHitIndex(index))
                else { vm.seek(offset); vm.sheet(null) }
            }.testTag("search_result_${state.searchHitIndex(index)}").padding(vertical = 16.dp)) { Text(excerpt, fontSize = 14.sp); Rule() }
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
        Small(if (reader != null) "TXT · ${reader.document.charset}\n${reader.document.length} 문자\n${reader.book.folder}" else "${entry?.let(::fileMetadata) ?: "파일 정보 없음"}\n${entry?.mime ?: ""}")
        if (reader != null) PaperButton("글자가 깨져 보이나요?", "info_encoding", Modifier.fillMaxWidth(), true, onClick = { vm.sheet("encoding") })
        Small("원본 파일은 기기의 폴더에 그대로 있습니다.")
    }
}
@Composable private fun StorageSettings(vm: AppViewModel, app: AppPreferences, chooseFolder: () -> Unit) {
    val p = LocalPaper.current
    var confirm by remember { mutableStateOf(false) }
    SettingsSection("폴더와 저장 공간", Icons.Outlined.FolderOpen, Modifier.testTag("storage_settings")) {
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
