@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek.ui

import android.widget.FrameLayout
import androidx.core.view.doOnAttach
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.R
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.epub.*
import org.readium.r2.shared.publication.Link
import kotlin.math.roundToInt

fun epubColors(p: EpubReaderPreferences, fixed: Boolean, app: PaperColors) = if (!fixed) readerColors(p.paper, app)
    else when (p.canvas) { PdfCanvas.AUTO -> app; PdfCanvas.LIGHT -> LightPaper; PdfCanvas.DARK -> DarkPaper }

@Composable fun EpubReaderScreen(vm: AppViewModel, opened: OpenEpub, prefs: EpubReaderPreferences, modifier: Modifier = Modifier) {
    val reader = vm.epubReader
    val completed by reader.completed.collectAsStateWithLifecycle()
    val at by reader.locator.collectAsStateWithLifecycle()
    val marks by reader.marks.collectAsStateWithLifecycle()
    val toggledChrome by reader.chrome.collectAsStateWithLifecycle()
    val chrome = toggledChrome || prefs.menuVisibility == MenuVisibility.ALWAYS
    LaunchedEffect(prefs.menuVisibility) { if (prefs.menuVisibility == MenuVisibility.ALWAYS) reader.chrome.value = true }
    val ready by reader.ready.collectAsStateWithLifecycle()
    val returnLocation by reader.returnLocation.collectAsStateWithLifecycle()
    val colors = epubColors(prefs, opened.document.fixed, LocalPaper.current)
    val density = LocalDensity.current
    var top by remember { mutableStateOf(0.dp) }
    var bottom by remember { mutableStateOf(0.dp) }
    val measuring = top == 0.dp || bottom == 0.dp
    CompositionLocalProvider(LocalPaper provides colors) {
        Box(modifier.fillMaxSize().background(colors.surface)) {
            // Restore the navigator only after the real toolbar heights are known. An estimated
            // first viewport can reflow a paginated chapter and restore the preceding page.
            if (!measuring) key(opened.token) {
                AndroidView(factory = { context ->
                    FrameLayout(context).apply {
                        id = R.id.epub_host_container
                        doOnAttach {
                            post {
                                val fm = (context as FragmentActivity).supportFragmentManager
                                if (isAttachedToWindow && !fm.isStateSaved && fm.findFragmentByTag(opened.token) == null) {
                                    fm.beginTransaction().replace(id, EpubHostFragment(), opened.token).commitNow()
                                }
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxSize().padding(top = top, bottom = bottom).testTag("epub_body"), onRelease = { view ->
                    val fm = (view.context as FragmentActivity).supportFragmentManager
                    fm.findFragmentByTag(opened.token)?.let { fm.beginTransaction().remove(it).commitNowAllowingStateLoss() }
                })
            }
            if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("epub_loading"))
            if (chrome || measuring) {
                Surface(color = colors.surface, contentColor = colors.ink, modifier = Modifier.align(Alignment.TopCenter).alpha(if (chrome) 1f else 0f).onSizeChanged { top = with(density) { it.height.toDp() } }) {
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tool(Icons.Outlined.ArrowBack, "닫기", "epub_back", onClick = { vm.closeReader() })
                            Column(Modifier.weight(1f)) {
                                Filename(opened.book.name)
                                val progress = ReadingProgress.at(opened.document.progress(at), completed).label
                                val status = if (opened.document.fixed) "${opened.document.resourceIndex(at) + 1} / ${opened.document.publication.readingOrder.size} 페이지 · $progress"
                                    else "${at?.title ?: "본문"} · $progress"
                                Text(status, Modifier.testTag("epub_progress"), fontSize = 11.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val marked = at?.let { loc -> marks.any { it.version == opened.book.version && it.locationKey == epubLocationKey(loc) } } == true
                            Tool(if (marked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (marked) "책갈피 해제" else "책갈피 추가", "epub_bookmark", Modifier.semantics { selected = marked }, onClick = reader::toggleBookmark)
                        }
                        Rule()
                    }
                }
                Surface(color = colors.surface, contentColor = colors.ink, modifier = Modifier.align(Alignment.BottomCenter).alpha(if (chrome) 1f else 0f).onSizeChanged { bottom = with(density) { it.height.toDp() } }) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ reader.renderer?.advance(false) }, Modifier.testTag("epub_previous"), enabled = ready) { Text(if (!opened.document.fixed && prefs.mode == EpubMode.SCROLL) "이전 장" else "이전 페이지") }
                            if (returnLocation != null) TextButton(reader::returnFromLink, Modifier.testTag("epub_link_return")) { Text("이전 위치") }
                            TextButton({ reader.renderer?.advance(true) }, Modifier.testTag("epub_next"), enabled = ready) { Text(if (!opened.document.fixed && prefs.mode == EpubMode.SCROLL) "다음 장" else "다음 페이지") }
                        }
                        ReaderToolbar(listOf(
                            ReaderToolbarItem(ReaderAction.CONTENTS, "epub_contents") { vm.sheet("epub_contents") },
                            ReaderToolbarItem(ReaderAction.BOOKMARKS, "epub_bookmarks") { vm.sheet("epub_bookmarks") },
                            ReaderToolbarItem(ReaderAction.SEARCH, "epub_search") { vm.sheet("epub_search") },
                            ReaderToolbarItem(ReaderAction.SETTINGS, "epub_settings") { vm.openEpubSettings() },
                        ), Modifier.testTag("epub_footer"))
                    }
                }
            }
        }
    }
}

@Composable fun EpubSettingsScreen(vm: AppViewModel, prefs: EpubReaderPreferences) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val fixed = state.epubReader?.document?.fixed == true
    SettingsPage("EPUB 읽기 설정", "epub_settings_screen", vm::closeEpubSettings, contentTag = "epub_options") {
        SettingsSection("설정 적용 범위", Icons.Outlined.Tune) { SettingsScopePicker(vm, "EPUB", showTitle = false) }
        ReaderGestureSettings(vm, "EPUB", prefs.menuVisibility)
        if (!fixed) {
            SettingsSection("읽기 방식", Icons.Outlined.MenuBook) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("장별 스크롤", prefs.mode == EpubMode.SCROLL, "epub_mode_scroll", Modifier.weight(1f)) { vm.changeSetting("EPUB", "mode", EpubMode.SCROLL) }
                    Choice("페이지 넘김", prefs.mode == EpubMode.PAGED, "epub_mode_paged", Modifier.weight(1f)) { vm.changeSetting("EPUB", "mode", EpubMode.PAGED) }
                }
                Small("스크롤은 장 안에서 이어지며, 이전·다음 장 버튼으로 이동합니다. 세로쓰기 책은 책의 쓰기 방향을 따릅니다.")
            }
            SettingsSection("글꼴과 크기", Icons.Outlined.TextFields) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                    EpubFont.entries.forEach { font -> Choice(font.label, prefs.font == font, "epub_font_${font.name}", Modifier.weight(1f)) { vm.changeSetting("EPUB", "font", font) } }
                }
                EpubSlider("글자 크기", "epub_size", prefs.size, .75f..2.5f, "${(prefs.size * 100).roundToInt()}%") { vm.changeSetting("EPUB", "size", it) }
            }
            SettingsSection("본문 서식", Icons.Outlined.FormatLineSpacing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("원본 서식"); Small("책에 지정된 줄 간격과 여백을 사용합니다.") }
                    Switch(prefs.publisherStyles, { vm.changeSetting("EPUB", "publisherStyles", it) }, Modifier.testTag("epub_publisher_styles"))
                }
                if (!prefs.publisherStyles) {
                    EpubSlider("줄 간격", "epub_line", prefs.line, 1.2f..2.2f, String.format(java.util.Locale.US, "%.1f", prefs.line)) { vm.changeSetting("EPUB", "line", it) }
                    EpubSlider("좌우 여백", "epub_margin", prefs.margin, .5f..2f, String.format(java.util.Locale.US, "%.1f", prefs.margin)) { vm.changeSetting("EPUB", "margin", it) }
                }
            }
            SettingsSection("본문 색상", Icons.Outlined.Palette) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                    TxtPaper.entries.forEach { paper -> Choice(when (paper) { TxtPaper.FOLLOW_APP -> "앱 테마 따름"; TxtPaper.LIGHT -> "라이트"; TxtPaper.SEPIA -> "세피아"; TxtPaper.DARK -> "다크" }, prefs.paper == paper, "epub_paper_${paper.name}", Modifier.weight(1f)) { vm.changeSetting("EPUB", "paper", paper) } }
                }
            }
        }
        if (fixed || state.epubReader == null) SettingsSection("고정 지면의 바깥 배경", Icons.Outlined.Wallpaper) {
            Small("고정 지면 책은 한 페이지씩 넘기고 두 손가락으로 확대합니다. 글꼴과 본문 색상은 원본을 따릅니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PdfCanvas.entries.forEach { canvas -> Choice(when (canvas) { PdfCanvas.AUTO -> "앱 따름"; PdfCanvas.LIGHT -> "라이트"; PdfCanvas.DARK -> "다크" }, prefs.canvas == canvas, "epub_canvas_${canvas.name}", Modifier.weight(1f)) { vm.changeSetting("EPUB", "canvas", canvas) } }
            }
        }
        SettingsSection("설정 초기화", Icons.Outlined.RestartAlt) {
            PaperButton("EPUB 설정 기본값 복원", "epub_reset", Modifier.fillMaxWidth(), outlined = true, destructive = true, onClick = { vm.resetSettings("EPUB") })
        }
    }
}

@Composable private fun EpubSlider(label: String, tag: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, change: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text("$label · $display")
    Slider(draft, { draft = it }, Modifier.testTag(tag), valueRange = range, onValueChangeFinished = { change(draft) })
}

private fun epubOutline(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> = links.flatMap { listOf(it to depth) + epubOutline(it.children, depth + 1) }

@Composable fun EpubSheet(vm: AppViewModel, kind: String) {
    val reader = vm.epubReader
    val opened = reader.opened ?: return
    when (kind) {
        "epub_contents" -> {
            val publication = opened.document.publication
            val outline = remember(opened.token) { epubOutline(publication.tableOfContents.ifEmpty { publication.readingOrder }) }
            LazyColumn(Modifier.heightIn(max = 460.dp).testTag("epub_outline")) {
                itemsIndexed(outline) { index, (link, depth) ->
                    Column(Modifier.fillMaxWidth().clickable { reader.follow(link); vm.sheet(null) }.testTag("epub_chapter_$index").padding(start = (depth.coerceAtMost(6) * 12).dp, top = 14.dp, bottom = 14.dp)) {
                        Text(link.title?.takeIf { it.isNotBlank() } ?: "${index + 1}장", maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }; Rule()
                }
            }
        }
        "epub_bookmarks" -> {
            val marks by reader.marks.collectAsStateWithLifecycle()
            if (marks.isEmpty()) Small("저장한 책갈피가 없습니다.")
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                itemsIndexed(marks, key = { _, mark -> mark.id }) { index, mark ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { reader.openBookmark(mark); vm.sheet(null) }.testTag("epub_mark_$index")) {
                            Text(mark.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (mark.version != opened.book.version) Small("변경 전 EPUB")
                        }
                        Tool(Icons.Outlined.DeleteOutline, "책갈피 삭제", "epub_mark_delete_$index", onClick = { reader.deleteBookmark(mark.id) })
                    }; Rule()
                }
            }
        }
        "epub_search" -> {
            val state by reader.search.collectAsStateWithLifecycle()
            var query by rememberSaveable { mutableStateOf(state.query) }
            OutlinedTextField(query, { query = it.take(256) }, Modifier.fillMaxWidth().testTag("epub_search_input"), label = { Text("EPUB에서 찾을 문장") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                PaperButton("검색", "epub_search_submit", onClick = { reader.startSearch(query) })
                if (state.running) PaperButton("중지", "epub_search_cancel", outlined = true, onClick = reader::cancelSearch)
                else PaperButton("원래 위치로", "epub_search_return", outlined = true, onClick = { reader.returnFromSearch(); vm.sheet(null) })
            }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Small("${state.count}개${if (state.running) " · 검색 중" else ""}")
            state.error?.let { Text(it, color = LocalPaper.current.danger) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(state.rows) { index, match ->
                    Column(Modifier.fillMaxWidth().clickable { reader.move(match); vm.sheet(null) }.testTag("epub_result_$index").padding(vertical = 12.dp)) {
                        Small(match.title ?: "본문")
                        Text("${match.text.before.orEmpty().takeLast(50)}${match.text.highlight.orEmpty()}${match.text.after.orEmpty().take(50)}", maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }; Rule()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.start > 0) PaperButton("이전 결과", "epub_search_previous", outlined = true, onClick = { reader.searchPage((state.start - 100).coerceAtLeast(0)) })
                if (state.start + state.rows.size < state.count) PaperButton("다음 결과", "epub_search_next", outlined = true, onClick = { reader.searchPage(state.start + 100) })
            }
        }
    }
}
