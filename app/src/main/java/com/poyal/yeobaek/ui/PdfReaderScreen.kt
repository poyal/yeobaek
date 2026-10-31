package com.poyal.yeobaek.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.pdf.*
import kotlin.math.roundToInt

fun pdfColors(prefs: PdfPreferences, app: PaperColors) = when (prefs.canvas) {
    PdfCanvas.LIGHT -> LightPaper
    PdfCanvas.DARK -> DarkPaper
    PdfCanvas.AUTO -> if (prefs.inverted) DarkPaper else app
}

@Composable fun PdfReaderScreen(vm: AppViewModel, opened: OpenPdf, prefs: PdfPreferences, modifier: Modifier = Modifier) {
    val reader = vm.pdfReader
    val zoomReset by reader.zoomReset.collectAsStateWithLifecycle()
    var consumedZoomReset by remember(opened.token) { mutableLongStateOf(zoomReset) }
    val locator by reader.locator.collectAsStateWithLifecycle()
    val seek by reader.seek.collectAsStateWithLifecycle()
    val marks by reader.marks.collectAsStateWithLifecycle()
    val highlight by reader.highlight.collectAsStateWithLifecycle()
    val selection by reader.selection.collectAsStateWithLifecycle()
    val p = pdfColors(prefs, LocalPaper.current)
    val context = LocalContext.current
    val nativeMenu by rememberUpdatedState(prefs.menuVisibility)
    var chrome by rememberSaveable(opened.token) { mutableStateOf(true) }
    LaunchedEffect(prefs.menuVisibility) { if (prefs.menuVisibility == MenuVisibility.ALWAYS) chrome = true }
    var view by remember(opened.token) { mutableStateOf<PdfDocumentView?>(null) }
    val density = LocalDensity.current
    var headerHeight by remember { mutableIntStateOf(with(density) { 60.dp.roundToPx() }) }
    var footerHeight by remember { mutableIntStateOf(with(density) { 76.dp.roundToPx() }) }
    var consumed by remember(opened.token) { mutableLongStateOf(-1) }
    var failure by remember(opened.token) { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalPaper provides p) {
        Box(modifier.fillMaxSize().background(p.surface)) {
            key(opened.token) {
                AndroidView(factory = { ctx -> PdfDocumentView(ctx).also { native ->
                    native.onReady = reader::ready
                    native.onPosition = { reader.position(it); vm.readingProgress(opened.token, it.completed) }
                    native.onEndReached = { vm.readingFinished(opened.token) }
                    native.onTap = { if (nativeMenu == MenuVisibility.TAP) chrome = !chrome }
                    native.onSelection = reader::select
                    native.onClearSelection = reader::clearSelection
                    native.onFailure = { failure = it }
                    native.configure(prefs, p.alt.toArgb())
                    native.open(opened.document, locator)
                    view = native
                } }, modifier = Modifier.fillMaxSize().clipToBounds().testTag("pdf_body"), onRelease = { it.dispose() }, update = { native ->
                    if (zoomReset != consumedZoomReset) { native.resetZoom(); consumedZoomReset = zoomReset }
                    native.setContentInsets(headerHeight, footerHeight)
                    native.configure(prefs, p.alt.toArgb()); native.highlight = highlight; native.selection = selection
                    seek?.takeIf { it.serial != consumed }?.let { native.move(it.locator); consumed = it.serial; reader.seek.compareAndSet(it, null) }
                })
            }
            if (chrome || prefs.menuVisibility == MenuVisibility.ALWAYS) {
                Surface(color = p.surface, contentColor = p.ink, modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeight = it.height }) {
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tool(Icons.Outlined.ArrowBack, "닫기", "pdf_back", onClick = { vm.closeReader() })
                            Column(Modifier.weight(1f)) {
                                Text(opened.book.name, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${locator.page + 1} / ${opened.document.pages.size} 페이지 · ${ReadingProgress.page(locator.page, locator.y, opened.document.pages.size, locator.completed).label}", Modifier.testTag("pdf_page"), fontSize = 11.sp, lineHeight = 16.sp, color = p.muted)
                            }
                            val marked = marks.any { it.version == opened.book.version && it.page == locator.page }
                            Tool(if (marked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (marked) "책갈피 해제" else "책갈피 추가", "pdf_bookmark", Modifier.semantics { selected = marked }, onClick = reader::toggleBookmark)
                        }; Rule()
                    }
                }
                Surface(color = p.surface, contentColor = p.ink, modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { footerHeight = it.height }) {
                    ReaderToolbar(listOf(
                        ReaderToolbarItem(ReaderAction.MOVE, "pdf_move") { vm.sheet("pdf_move") },
                        ReaderToolbarItem(ReaderAction.BOOKMARKS, "pdf_bookmarks") { vm.sheet("pdf_bookmarks") },
                        ReaderToolbarItem(ReaderAction.SEARCH, "pdf_search") { vm.sheet("pdf_search") },
                        ReaderToolbarItem(ReaderAction.INVERT, "pdf_invert", prefs.inverted) { vm.toggleInversion("PDF") },
                        ReaderToolbarItem(ReaderAction.SETTINGS, "pdf_settings", onClick = { vm.sheet("quick_settings") }),
                    ), Modifier.testTag("pdf_footer"))
                }
            }
            selection?.takeIf { it.text.isNotEmpty() }?.let { range ->
                Surface(color = p.raised, contentColor = p.ink, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp), shadowElevation = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PaperButton("복사", "pdf_copy", onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF 텍스트", range.text)); reader.clearSelection()
                        })
                        Tool(Icons.Outlined.Close, "선택 해제", "pdf_selection_clear", onClick = reader::clearSelection)
                    }
                }
            }
            failure?.let { message ->
                Surface(color = p.raised, contentColor = p.ink, modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message)
                        PaperButton("다시 시도", "pdf_retry", onClick = { failure = null; view?.retry() })
                        PaperButton("문서 닫기", "pdf_failure_close", outlined = true, onClick = { vm.closeReader() })
                    }
                }
            }
        }
    }
}
@Composable fun PdfSettingsScreen(vm: AppViewModel, prefs: PdfPreferences) {
    SettingsPage("PDF 읽기 설정", "pdf_settings_screen", vm::closePdfSettings, contentTag = "pdf_options") {
        SettingsSection("설정 적용 범위", Icons.Outlined.Tune) { SettingsScopePicker(vm, "PDF", showTitle = false, showReset = false) }
        ReaderGestureSettings(vm, "PDF", prefs.menuVisibility, prefs.doubleTapZoom, prefs.pinchZoom)
        SettingsSection("화면 맞춤과 간격", Icons.Outlined.FitScreen) {
            ChoiceGroup(PdfFit.entries, prefs.fit, label = { if (it == PdfFit.WIDTH) "너비에 맞춤" else "페이지에 맞춤" }, tag = { "pdf_fit_$it" }) { vm.changeSetting("PDF", "fit", it) }
            Rule()
            var spacing by remember(prefs.spacing) { mutableFloatStateOf(prefs.spacing) }
            Text("페이지 간격 · ${spacing.roundToInt()} dp", fontSize = 15.sp)
            Slider(spacing, { spacing = it }, Modifier.testTag("pdf_spacing").semantics { contentDescription = "페이지 간격" }, valueRange = 0f..24f, steps = 23, onValueChangeFinished = { vm.changeSetting("PDF", "spacing", spacing) })
        }
        SettingsSection("페이지 색상", Icons.Outlined.Palette) {
            LabeledSwitch("색상 반전", prefs.inverted, "pdf_inversion_switch", "글자·이미지·도표의 색상을 함께 반전합니다.") { vm.changeSetting("PDF", "inverted", it) }
            Small("원본 파일과 메뉴 색상에는 영향을 주지 않습니다.")
        }
        SettingsSection("페이지 바깥 배경", Icons.Outlined.Wallpaper) {
            ChoiceGroup(PdfCanvas.entries, prefs.canvas, label = { when (it) { PdfCanvas.AUTO -> "자동"; PdfCanvas.LIGHT -> "라이트"; PdfCanvas.DARK -> "다크" } }, tag = { "pdf_canvas_$it" }) { vm.changeSetting("PDF", "canvas", it) }
            Small("자동은 앱 테마를 따르며, 색상 반전 중에는 어두운 배경을 사용합니다.")
        }
        SettingsSection("설정 초기화", Icons.Outlined.RestartAlt) {
            ReaderSettingsReset(vm, "PDF")
        }
    }
}

@Composable fun PdfSheet(vm: AppViewModel, kind: String) {
    val reader = vm.pdfReader
    val opened = reader.opened ?: return
    val locator by reader.locator.collectAsStateWithLifecycle()
    when (kind) {
        "pdf_move" -> {
            val count = opened.document.pages.size
            var number by rememberSaveable { mutableStateOf((locator.page + 1).toString()) }
            var target by rememberSaveable { mutableFloatStateOf(locator.page.toFloat()) }
            OutlinedTextField(number, { value -> number = value.filter(Char::isDigit).take(6); number.toIntOrNull()?.let { target = (it - 1).coerceIn(0, count - 1).toFloat() } }, Modifier.fillMaxWidth().testTag("pdf_page_input"), label = { Text("페이지 번호 · 1~$count") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (count > 1) Slider(target, { target = it; number = (it.roundToInt() + 1).toString() }, Modifier.testTag("pdf_page_slider"), valueRange = 0f..(count - 1).toFloat())
            Spacer(Modifier.height(16.dp))
            PaperButton("이 페이지로 이동", "pdf_move_apply", Modifier.fillMaxWidth(), enabled = number.toIntOrNull() in 1..count, onClick = { reader.move(PdfLocator(page = number.toInt() - 1)); vm.sheet(null) })
        }
        "pdf_search" -> {
            val state by reader.search.collectAsStateWithLifecycle()
            var query by rememberSaveable { mutableStateOf(state.query) }
            OutlinedTextField(query, { query = it.take(256) }, Modifier.fillMaxWidth().testTag("pdf_search_input"), label = { Text("PDF에서 찾을 문장") }, singleLine = true)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton("검색", "pdf_search_submit", onClick = { reader.startSearch(query) })
                if (state.running) PaperButton("중지", "pdf_search_cancel", outlined = true, onClick = reader::cancelSearch)
                else PaperButton("원래 위치로", "pdf_search_return", outlined = true, onClick = { reader.returnFromSearch(); vm.sheet(null) })
            }
            if (state.running) { LinearProgressIndicator(progress = { state.scanned.toFloat() / opened.document.pages.size }, modifier = Modifier.fillMaxWidth()); Small("${state.scanned} / ${opened.document.pages.size} 페이지 확인 중") }
            state.error?.let { Text(it, color = LocalPaper.current.danger) }
            if (state.noText) Small("검색할 텍스트가 없는 PDF입니다. 이미지로 된 페이지는 검색·선택할 수 없습니다.")
            else Small("${state.count}개 결과" + if (state.index >= 0) " · ${state.index + 1}번째" else "")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton("이전", "pdf_search_previous", Modifier.weight(1f), outlined = true, enabled = state.index > 0, onClick = { reader.result(state.index - 1); vm.sheet(null) })
                PaperButton("다음", "pdf_search_next", Modifier.weight(1f), outlined = true, enabled = state.index + 1 < state.count, onClick = { reader.result(state.index + 1); vm.sheet(null) })
            }
            val resultsList = key(state.query, state.rowStart) { rememberLazyListState() }
            LazyColumn(Modifier.heightIn(max = 280.dp).testTag("pdf_search_results"), state = resultsList) {
                itemsIndexed(state.rows) { i, hit ->
                    Column(Modifier.fillMaxWidth().clickable { reader.result(state.rowStart + i); vm.sheet(null) }.padding(vertical = 12.dp)) {
                        Text("${hit.page + 1} 페이지", fontSize = 14.sp)
                        if (hit.excerpt.isNotBlank()) Small(hit.excerpt.replace('\n', ' '))
                        Rule()
                    }
                }
                if (state.rowStart + 100 < state.count) item { PaperButton("다음 100개 결과", "pdf_search_more", onClick = reader::moreResults) }
            }
        }
        "pdf_bookmarks" -> {
            val marks by reader.marks.collectAsStateWithLifecycle()
            if (marks.isEmpty()) EmptyState("접어 둔 페이지가 없어요.", "위쪽 책갈피 버튼으로 현재 페이지를 남겨 보세요.")
            else LazyColumn(Modifier.heightIn(max = 420.dp).testTag("pdf_bookmark_list")) {
                itemsIndexed(marks, key = { _, mark -> mark.id }) { _, mark ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${mark.page + 1} 페이지" + if (mark.version != opened.book.version) " · 변경 전 문서" else "", Modifier.weight(1f).clickable { reader.openBookmark(mark); vm.sheet(null) }.padding(vertical = 20.dp))
                        Tool(Icons.Outlined.DeleteOutline, "책갈피 삭제", "pdf_bookmark_delete", destructive = true, onClick = { reader.deleteBookmark(mark.id) })
                    }; Rule()
                }
            }
        }
    }
}
