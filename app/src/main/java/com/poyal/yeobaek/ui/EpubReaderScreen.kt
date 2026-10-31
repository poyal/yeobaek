@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek.ui

import android.widget.FrameLayout
import androidx.core.view.doOnAttach
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
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
    val navigation by reader.navigation.collectAsStateWithLifecycle()
    val returnLocation by reader.returnLocation.collectAsStateWithLifecycle()
    val colors = epubColors(prefs, opened.document.fixed, LocalPaper.current)
    val density = LocalDensity.current
    var top by remember { mutableStateOf(0.dp) }
    var bottom by remember { mutableStateOf(0.dp) }
    var bodyHeight by remember { mutableIntStateOf(0) }
    val chapterNavigation = !opened.document.fixed && prefs.mode == EpubMode.SCROLL
    val showChapterButtons = when (prefs.chapterButtons) {
        EpubChapterButtons.ALWAYS -> true
        EpubChapterButtons.WITH_MENU -> chrome
        EpubChapterButtons.HIDDEN -> false
    }
    val resourceIndex = navigation.resourceIndex.takeIf { it >= 0 } ?: opened.document.resourceIndex(at)
    val chapterTitle = opened.document.outline.getOrNull(navigation.outlineIndex)?.link?.title
        ?.takeIf { it.isNotBlank() } ?: opened.document.chapterTitle(resourceIndex)
    val measuring = top == 0.dp || bottom == 0.dp
    SideEffect { reader.startInsetHeight.value = with(density) { top.roundToPx() } }
    CompositionLocalProvider(LocalPaper provides colors) {
        Box(modifier.fillMaxSize().background(colors.surface).testTag("epub_reader")) {
            // Page mode needs the measured toolbar heights before restoring. Scroll mode uses
            // the full viewport with document-edge clearance, so toggling chrome never reflows it.
            if (!measuring) key(opened.token) {
                Box(Modifier.fillMaxSize().then(if (chapterNavigation) Modifier else Modifier.padding(top = top, bottom = bottom))
                    .clipToBounds().onSizeChanged { bodyHeight = it.height }) {
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
                }, modifier = Modifier.fillMaxSize().testTag("epub_body"), onRelease = { view ->
                    val fm = (view.context as FragmentActivity).supportFragmentManager
                    fm.findFragmentByTag(opened.token)?.let { fm.beginTransaction().remove(it).commitNowAllowingStateLoss() }
                })
                  if (chapterNavigation) {
                      val actionTop = navigation.endActionTop
                      val visible = actionTop != null && !navigation.moving && ready
                      EpubChapterEnd(
                          nextTitle = if (resourceIndex < opened.document.publication.readingOrder.lastIndex)
                              opened.document.chapterTitle(resourceIndex + 1) else null,
                          enabled = visible,
                          bottomInset = bottom + 56.dp,
                          modifier = Modifier.fillMaxWidth().offset {
                              IntOffset(0, if (visible) (actionTop!! * bodyHeight).roundToInt() else bodyHeight + 1)
                          }.wrapContentHeight(Alignment.Top, unbounded = true)
                              .onSizeChanged { reader.endActionHeight.value = it.height }
                              .then(if (!visible) Modifier.clearAndSetSemantics {} else Modifier),
                          onNext = { reader.renderer?.advance(true) },
                      )
                  }
                }
            }
            if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("epub_loading"))
            if (chrome || measuring) {
                Surface(color = colors.surface, contentColor = colors.ink, modifier = Modifier.align(Alignment.TopCenter).testTag("epub_header").alpha(if (chrome) 1f else 0f).onSizeChanged {
                    top = with(density) { it.height.toDp() }
                }) {
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tool(Icons.Outlined.ArrowBack, "닫기", "epub_back", onClick = { vm.closeReader() })
                            Column(Modifier.weight(1f)) {
                                Filename(opened.book.name)
                                val progress = ReadingProgress.at(opened.document.progress(at), completed).label
                                if (opened.document.fixed) {
                                    Text("${resourceIndex + 1} / ${opened.document.publication.readingOrder.size} 페이지 · $progress",
                                        Modifier.testTag("epub_progress"), fontSize = 11.sp, color = colors.muted, maxLines = 1)
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${resourceIndex + 1} / ${opened.document.publication.readingOrder.size} · $progress",
                                            Modifier.testTag("epub_progress"), fontSize = 11.sp, color = colors.muted)
                                        Text(chapterTitle, Modifier.weight(1f).testTag("epub_current_chapter"), fontSize = 11.sp,
                                            color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            val marked = at?.let { loc -> marks.any { it.version == opened.book.version && it.locationKey == epubLocationKey(loc) } } == true
                            Tool(if (marked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (marked) "책갈피 해제" else "책갈피 추가", "epub_bookmark", Modifier.semantics { selected = marked }, onClick = reader::toggleBookmark)
                        }
                        Rule()
                    }
                }
                Surface(color = colors.surface, contentColor = colors.ink, modifier = Modifier.align(Alignment.BottomCenter).alpha(if (chrome) 1f else 0f).onSizeChanged { bottom = with(density) { it.height.toDp() } }) {
                    Column {
                        if (!chapterNavigation) EpubNavigationControls(
                            chapter = false, previous = ready && navigation.canGoPrevious, next = ready && navigation.canGoNext,
                            showReturn = returnLocation != null, onPrevious = { reader.renderer?.advance(false) },
                            onNext = { reader.renderer?.advance(true) }, onReturn = reader::returnFromLink,
                        )
                        ReaderToolbar(listOf(
                            ReaderToolbarItem(ReaderAction.CONTENTS, "epub_contents") { vm.sheet("epub_contents") },
                            ReaderToolbarItem(ReaderAction.BOOKMARKS, "epub_bookmarks") { vm.sheet("epub_bookmarks") },
                            ReaderToolbarItem(ReaderAction.SEARCH, "epub_search") { vm.sheet("epub_search") },
                            ReaderToolbarItem(ReaderAction.SETTINGS, "epub_settings") { vm.openEpubSettings() },
                        ), Modifier.testTag("epub_footer"))
                    }
                }
            }
            if (chapterNavigation && (showChapterButtons || returnLocation != null)) EpubNavigationControls(
                chapter = true,
                showArrows = showChapterButtons,
                previous = ready && !navigation.moving && navigation.canGoPrevious,
                next = ready && !navigation.moving && navigation.canGoNext,
                showReturn = returnLocation != null,
                onPrevious = { reader.renderer?.advance(false) }, onNext = { reader.renderer?.advance(true) },
                onReturn = reader::returnFromLink,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (chrome) bottom else 0.dp),
            )
        }
    }
}

@Composable private fun EpubNavigationControls(
    chapter: Boolean, previous: Boolean, next: Boolean, showReturn: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onReturn: () -> Unit, modifier: Modifier = Modifier, showArrows: Boolean = true,
) {
    val paper = LocalPaper.current
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showArrows) FilledTonalIconButton(onPrevious, Modifier.size(48.dp).testTag("epub_previous"), enabled = previous,
            shape = RoundedCornerShape(8.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = paper.raised, contentColor = paper.accent, disabledContainerColor = paper.surface,
                disabledContentColor = paper.muted.copy(alpha = .38f))) {
            Icon(Icons.Outlined.ChevronLeft, if (chapter) "이전 장" else "이전 페이지")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (showReturn) PaperButton("이전 위치", "epub_link_return", outlined = true, onClick = onReturn)
        }
        if (showArrows) FilledTonalIconButton(onNext, Modifier.size(48.dp).testTag("epub_next"), enabled = next,
            shape = RoundedCornerShape(8.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = paper.raised, contentColor = paper.accent, disabledContainerColor = paper.surface,
                disabledContentColor = paper.muted.copy(alpha = .38f))) {
            Icon(Icons.Outlined.ChevronRight, if (chapter) "다음 장" else "다음 페이지")
        }
    }
}

@Composable private fun EpubChapterEnd(nextTitle: String?, enabled: Boolean, bottomInset: Dp, modifier: Modifier = Modifier, onNext: () -> Unit) {
    Column(modifier.background(LocalPaper.current.surface).padding(bottom = bottomInset).padding(horizontal = 20.dp, vertical = 16.dp)
        .testTag("epub_chapter_end"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (nextTitle == null) {
            Text("책의 끝입니다", color = LocalPaper.current.muted, modifier = Modifier.testTag("epub_book_end"))
        } else {
            Text("다음 장 · $nextTitle", color = LocalPaper.current.muted, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("epub_next_chapter_title"))
            PaperButton("다음 장으로", "epub_next_chapter", Modifier.fillMaxWidth(), enabled = enabled, onClick = onNext)
        }
    }
}

@Composable fun EpubSettingsScreen(vm: AppViewModel, prefs: EpubReaderPreferences) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val fixed = state.epubReader?.document?.fixed == true
    SettingsPage("EPUB 읽기 설정", "epub_settings_screen", vm::closeEpubSettings, contentTag = "epub_options") {
        SettingsSection("설정 적용 범위", Icons.Outlined.Tune) { SettingsScopePicker(vm, "EPUB", showTitle = false, showReset = false) }
        ReaderGestureSettings(vm, "EPUB", prefs.menuVisibility,
            pinch = prefs.pinchZoom.takeIf { fixed || state.epubReader == null }, swipeToTurn = prefs.swipeToTurn.takeUnless { fixed },
            swipeLabel = if (prefs.mode == EpubMode.SCROLL) "좌우 스와이프로 장 이동" else "좌우 스와이프로 페이지 넘김",
            pinchDetail = "고정 지면 EPUB에 적용됩니다.")
        if (!fixed) {
            SettingsSection("읽기 방식", Icons.Outlined.MenuBook) {
                ChoiceGroup(EpubMode.entries, prefs.mode, label = { if (it == EpubMode.SCROLL) "장별 스크롤" else "페이지 넘김" },
                    tag = { "epub_mode_${it.name.lowercase()}" }) { vm.changeSetting("EPUB", "mode", it) }
                Small("스크롤은 장 안에서 이어집니다. 하단 화살표나 장 끝의 ‘다음 장으로’ 버튼으로 이동합니다. 세로쓰기 책은 책의 쓰기 방향을 따릅니다.")
            }
            if (prefs.mode == EpubMode.SCROLL) SettingsSection("장 이동 버튼", Icons.Outlined.SwapHoriz) {
                ChoiceGroup(EpubChapterButtons.entries, prefs.chapterButtons, label = { it.label },
                    tag = { "epub_chapter_buttons_$it" }) { vm.changeSetting("EPUB", "chapterButtons", it) }
                Small("장별 스크롤의 좌우 화살표에 적용됩니다. 화살표를 숨겨도 장 끝의 ‘다음 장으로’ 버튼은 표시됩니다.")
            }
            SettingsSection("글꼴과 크기", Icons.Outlined.TextFields) {
                ChoiceGroup(EpubFont.entries, prefs.font, label = { it.label }, tag = { "epub_font_$it" }) { vm.changeSetting("EPUB", "font", it) }
                EpubSlider("글자 크기", "epub_size", prefs.size, .75f..2.5f, { "${(it * 100).roundToInt()}%" }) { vm.changeSetting("EPUB", "size", it) }
            }
            SettingsSection("본문 서식", Icons.Outlined.FormatLineSpacing) {
                LabeledSwitch("원본 서식", prefs.publisherStyles, "epub_publisher_styles", "책에 지정된 줄 간격과 여백을 사용합니다.") { vm.changeSetting("EPUB", "publisherStyles", it) }
                if (!prefs.publisherStyles) {
                    EpubSlider("줄 간격", "epub_line", prefs.line, 1.2f..2.2f, { String.format(java.util.Locale.US, "%.1f배", it) }) { vm.changeSetting("EPUB", "line", it) }
                    EpubSlider("좌우 여백", "epub_margin", prefs.margin, .5f..2f, { String.format(java.util.Locale.US, "%.1f배", it) }) { vm.changeSetting("EPUB", "margin", it) }
                }
            }
            SettingsSection("본문 색상", Icons.Outlined.Palette) {
                ChoiceGroup(TxtPaper.entries, prefs.paper,
                    label = { when (it) { TxtPaper.FOLLOW_APP -> "앱 테마"; TxtPaper.LIGHT -> "라이트"; TxtPaper.SEPIA -> "세피아"; TxtPaper.DARK -> "다크" } },
                    tag = { "epub_paper_$it" }) { vm.changeSetting("EPUB", "paper", it) }
            }
        }
        if (fixed || state.epubReader == null) SettingsSection("고정 지면의 바깥 배경", Icons.Outlined.Wallpaper) {
            Small("고정 지면 책은 한 페이지씩 넘기고 두 손가락으로 확대합니다. 글꼴과 본문 색상은 원본을 따릅니다.")
            ChoiceGroup(PdfCanvas.entries, prefs.canvas, label = { when (it) { PdfCanvas.AUTO -> "앱 테마"; PdfCanvas.LIGHT -> "라이트"; PdfCanvas.DARK -> "다크" } },
                tag = { "epub_canvas_$it" }) { vm.changeSetting("EPUB", "canvas", it) }
        }
        SettingsSection("설정 초기화", Icons.Outlined.RestartAlt) {
            ReaderSettingsReset(vm, "EPUB")
        }
    }
}

@Composable private fun EpubSlider(label: String, tag: String, value: Float, range: ClosedFloatingPointRange<Float>, display: (Float) -> String, change: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text("$label · ${display(draft)}", Modifier.testTag("${tag}_value"))
    Slider(draft, { draft = it }, Modifier.testTag(tag).semantics { contentDescription = label }, valueRange = range, onValueChangeFinished = { change(draft) })
}

@Composable fun EpubSheet(vm: AppViewModel, kind: String) {
    val reader = vm.epubReader
    val opened = reader.opened ?: return
    when (kind) {
        "epub_contents" -> {
            val outline = opened.document.outline
            val navigation by reader.navigation.collectAsStateWithLifecycle()
            val at by reader.locator.collectAsStateWithLifecycle()
            val selectedIndex = navigation.outlineIndex.takeIf { it in outline.indices }
                ?: epubOutlineIndex(outline, at?.href)
            val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
            LazyColumn(Modifier.heightIn(max = 460.dp).testTag("epub_outline"), state = list) {
                itemsIndexed(outline) { index, (link, depth) ->
                    val current = index == selectedIndex
                    Column(Modifier.fillMaxWidth().background(if (current) LocalPaper.current.soft else LocalPaper.current.surface)
                        .semantics { selected = current }.clickable { reader.follow(link); vm.sheet(null) }.testTag("epub_chapter_$index")
                        .padding(start = (depth.coerceAtMost(6) * 12 + 8).dp, end = 8.dp, top = 14.dp, bottom = 14.dp)) {
                        Text(link.title?.takeIf { it.isNotBlank() } ?: "${index + 1}장", maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (current) Small("현재 위치")
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
            val resultsList = key(state.query, state.start) { rememberLazyListState() }
            LazyColumn(Modifier.heightIn(max = 320.dp).testTag("epub_search_results"), state = resultsList) {
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
