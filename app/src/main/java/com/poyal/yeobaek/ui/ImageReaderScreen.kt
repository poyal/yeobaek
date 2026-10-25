package com.poyal.yeobaek.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.data.MenuVisibility
import com.poyal.yeobaek.data.ReadingProgress
import com.poyal.yeobaek.image.*
import com.poyal.yeobaek.pdf.PDF_INVERSION
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

fun imageBackground(prefs: ImagePreferences, app: PaperColors): Color = when (prefs.canvas) {
    ImageCanvas.LIGHT -> LightPaper.alt
    ImageCanvas.DARK -> DarkPaper.alt
    ImageCanvas.AUTO -> if (prefs.inverted) DarkPaper.alt else app.alt
}

@Composable fun ImageReaderScreen(vm: AppViewModel, opened: OpenImages, prefs: ImagePreferences, modifier: Modifier = Modifier) {
    val reader = vm.imageReader
    val zoomReset by reader.zoomReset.collectAsStateWithLifecycle()
    var consumedZoomReset by remember(opened.token) { mutableLongStateOf(zoomReset) }
    val locator by reader.locator.collectAsStateWithLifecycle()
    val seek by reader.seek.collectAsStateWithLifecycle()
    val marks by reader.marks.collectAsStateWithLifecycle()
    val activity by opened.source.activity.collectAsStateWithLifecycle()
    val pages by opened.source.pages.collectAsStateWithLifecycle()
    val p = LocalPaper.current
    val density = LocalDensity.current
    val nativeMenu by rememberUpdatedState(prefs.menuVisibility)
    var chrome by rememberSaveable(opened.token) { mutableStateOf(true) }
    LaunchedEffect(prefs.menuVisibility) { if (prefs.menuVisibility == MenuVisibility.ALWAYS) chrome = true }
    var view by remember(opened.token) { mutableStateOf<ImageDocumentView?>(null) }
    var headerHeight by remember { mutableIntStateOf(with(density) { 60.dp.roundToPx() }) }
    var footerHeight by remember { mutableIntStateOf(with(density) { 76.dp.roundToPx() }) }
    var consumed by remember(opened.token) { mutableLongStateOf(-1) }
    var failure by remember(opened.token) { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxSize()) {
        key(opened.token) {
            AndroidView(factory = { ctx -> ImageDocumentView(ctx).also { native ->
                native.onReady = reader::ready
                native.onPosition = { reader.position(it); vm.readingProgress(opened.token, it.completed) }
                native.onEndReached = { vm.readingFinished(opened.token) }
                native.onTap = { if (nativeMenu == MenuVisibility.TAP) chrome = !chrome }; native.onFailure = { failure = it }
                native.configure(prefs, imageBackground(prefs, p).toArgb()); native.open(opened.source, locator)
                view = native
            } }, modifier = Modifier.fillMaxSize().clipToBounds().testTag("image_body"), onRelease = { it.dispose() }, update = { native ->
                if (zoomReset != consumedZoomReset) { native.resetZoom(); consumedZoomReset = zoomReset }
                native.setContentInsets(headerHeight, footerHeight)
                native.configure(prefs, imageBackground(prefs, p).toArgb())
                seek?.takeIf { it.serial != consumed }?.let { native.move(it.locator); consumed = it.serial; reader.seek.compareAndSet(it, null) }
            })
        }
        if (chrome || prefs.menuVisibility == MenuVisibility.ALWAYS) {
            Surface(color = p.surface, contentColor = p.ink, modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeight = it.height }) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tool(Icons.Outlined.ArrowBack, "닫기", "image_back", onClick = { vm.closeReader() })
                        Column(Modifier.weight(1f)) {
                            Text(opened.book.name, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${locator.page + 1} / ${pages.size} 페이지 · ${ReadingProgress.page(locator.page, locator.y, pages.size, locator.completed).label}${if (prefs.splitWide && prefs.mode == ImageMode.PAGED && locator.segment >= 0) if (locator.segment == 0) " · 왼쪽" else " · 오른쪽" else ""}", Modifier.testTag("image_page"), fontSize = 11.sp, color = p.muted)
                        }
                        val marked = marks.any { it.version == opened.book.version && it.pageKey == locator.key }
                        Tool(if (marked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (marked) "책갈피 해제" else "책갈피 추가",
                            "image_bookmark", Modifier.semantics { selected = marked }, onClick = reader::toggleBookmark)
                    }; Rule()
                }
            }
            Surface(color = p.surface, contentColor = p.ink, modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { footerHeight = it.height }) {
                ReaderToolbar(listOf(
                    ReaderToolbarItem(ReaderAction.MOVE, "image_move", onClick = vm::openImagePages),
                    ReaderToolbarItem(ReaderAction.BOOKMARKS, "image_bookmarks") { vm.sheet("image_bookmarks") },
                    ReaderToolbarItem(ReaderAction.INVERT, "image_invert", prefs.inverted) { vm.toggleInversion("IMAGE") },
                    ReaderToolbarItem(ReaderAction.SETTINGS, "image_settings", onClick = { vm.sheet("quick_settings") }),
                ), Modifier.testTag("image_footer"))
            }
        }
        activity?.let { message ->
            Surface(color = p.raised, contentColor = p.ink, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp, start = 16.dp, end = 16.dp)) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(message, Modifier.weight(1f).padding(8.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Tool(Icons.Outlined.Close, "이미지 준비 취소", "image_prepare_cancel", onClick = { vm.closeReader() })
                }
            }
        }
        failure?.let { message ->
            Surface(color = p.raised, contentColor = p.ink, modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message)
                    PaperButton("다시 시도", "image_retry", Modifier.fillMaxWidth(), onClick = { failure = null; view?.retry() })
                    PaperButton("다음 이미지", "image_skip", Modifier.fillMaxWidth(), outlined = true,
                        enabled = locator.page < pages.lastIndex,
                        onClick = { failure = null; reader.move(ImageLocator(page = locator.page + 1)) })
                    PaperButton("문서 닫기", "image_failure_close", Modifier.fillMaxWidth(), outlined = true, onClick = { vm.closeReader() })
                }
            }
        }
    }
}

@Composable fun ImageSettingsScreen(vm: AppViewModel, prefs: ImagePreferences) {
    fun set(value: ImagePreferences) { vm.setImage(value) }
    SettingsPage("이미지 읽기 설정", "image_settings_screen", vm::closeImageSettings, contentTag = "image_options") {
        SettingsSection("설정 적용 범위", Icons.Outlined.Tune) { SettingsScopePicker(vm, "IMAGE", showTitle = false, showReset = false) }
        SettingsSection("읽기 방식", Icons.Outlined.AutoStories) {
            ChoiceGroup(ImageMode.entries, prefs.mode, label = { if (it == ImageMode.VERTICAL) "세로 연속" else "좌우 넘김" }, tag = { "image_mode_$it" }) { set(prefs.copy(mode = it)) }
            if (prefs.mode == ImageMode.PAGED) {
                Rule(); Text("넘김 방향")
                ChoiceGroup(ImageDirection.entries, prefs.direction, label = { if (it == ImageDirection.LTR) "왼쪽 → 오른쪽" else "오른쪽 → 왼쪽" }, tag = { "image_direction_$it" }) { set(prefs.copy(direction = it)) }
                Text("페이지 배치")
                ChoiceGroup(ImageSpread.entries, prefs.spread,
                    label = { when (it) { ImageSpread.SINGLE -> "한 쪽"; ImageSpread.DOUBLE -> "두 쪽"; ImageSpread.LANDSCAPE -> "가로 화면에서 자동" } },
                    tag = { "image_spread_$it" }) { set(prefs.copy(spread = it)) }
                LabeledSwitch("표지는 한 쪽으로", prefs.coverAlone, "image_cover") { set(prefs.copy(coverAlone = it)) }
                Small("가로로 긴 이미지는 한 쪽으로 표시합니다.")
            }
        }
        ReaderGestureSettings(vm, "IMAGE", prefs.menuVisibility, prefs.doubleTapZoom, prefs.pinchZoom, prefs.tapToTurn.takeIf { prefs.mode == ImageMode.PAGED })
        SettingsSection("화면 맞춤과 간격", Icons.Outlined.FitScreen) {
            ChoiceGroup(ImageFit.entries, prefs.fit, label = { if (it == ImageFit.WIDTH) "너비에 맞춤" else "전체 이미지" }, tag = { "image_fit_$it" }) {
                set(if (prefs.mode == ImageMode.VERTICAL) prefs.copy(verticalFit = it) else prefs.copy(pagedFit = it))
            }
            Rule()
            var spacing by remember(prefs.spacing) { mutableFloatStateOf(prefs.spacing) }
            Text("이미지 간격 · ${spacing.roundToInt()} dp")
            Slider(spacing, { spacing = it }, Modifier.testTag("image_spacing").semantics { contentDescription = "이미지 간격" }, valueRange = 0f..24f, steps = 23, onValueChangeFinished = { set(prefs.copy(spacing = spacing)) })
        }
        SettingsSection("페이지 여백", Icons.Outlined.Crop) {
            ImageCropOptions(vm, prefs)
        }
        SettingsSection("페이지 색상과 배경", Icons.Outlined.Palette) {
            LabeledSwitch("색상 반전", prefs.inverted, "image_inversion_switch", "이미지와 미리보기의 색상을 반전합니다.") { vm.changeSetting("IMAGE", "inverted", it) }
            Text("이미지 바깥 배경")
            ChoiceGroup(ImageCanvas.entries, prefs.canvas, label = { when (it) { ImageCanvas.AUTO -> "자동"; ImageCanvas.LIGHT -> "라이트"; ImageCanvas.DARK -> "다크" } }, tag = { "image_canvas_$it" }) { set(prefs.copy(canvas = it)) }
            Small("자동은 앱 테마를 따르며, 색상 반전 중에는 어두운 배경을 사용합니다.")
        }
        SettingsSection("설정 초기화", Icons.Outlined.RestartAlt) {
            ReaderSettingsReset(vm, "IMAGE")
        }
    }
}

@Composable fun ImagePagesScreen(vm: AppViewModel, opened: OpenImages, prefs: ImagePreferences) {
    val pages by opened.source.pages.collectAsStateWithLifecycle()
    val position by vm.imageReader.locator.collectAsStateWithLifecycle()
    var number by rememberSaveable(opened.token) { mutableStateOf((position.page + 1).toString()) }
    var target by rememberSaveable(opened.token) { mutableFloatStateOf(position.page.toFloat()) }
    var chapters by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = position.page)
    fun go(index: Int) { vm.imageReader.move(ImageLocator(page = index)); vm.closeImagePages() }
    Column(Modifier.fillMaxSize().testTag("image_pages_screen")) {
        PageHeader("읽기 위치 이동", vm::closeImagePages)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            OutlinedTextField(number, { value -> number = value.filter(Char::isDigit).take(5); number.toIntOrNull()?.let { target = (it - 1).coerceIn(pages.indices).toFloat() } },
                Modifier.fillMaxWidth().testTag("image_page_input"), label = { Text("페이지 번호 · 1~${pages.size}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (pages.size > 1) Slider(target, { target = it; number = (it.roundToInt() + 1).toString() }, Modifier.testTag("image_page_slider"), valueRange = 0f..pages.lastIndex.toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton("이 페이지로 이동", "image_move_apply", Modifier.weight(1f), enabled = number.toIntOrNull() in 1..pages.size, onClick = { go(number.toInt() - 1) })
                if (pages.any { '/' in it.path }) Tool(if (chapters) Icons.Outlined.GridView else Icons.Outlined.List, if (chapters) "미리보기" else "폴더별 목차", "image_chapters", onClick = { chapters = !chapters })
            }
        }
        if (chapters) {
            val groups = remember(pages.map { it.path }) { pages.mapIndexed { index, page -> page.path.substringBeforeLast('/', "") to index }.distinctBy { it.first } }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                items(groups, key = { it.first }) { (name, index) ->
                    Row(Modifier.fillMaxWidth().clickable { go(index) }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, null); Column(Modifier.weight(1f).padding(start = 12.dp)) { Filename(name.ifEmpty { "첫 폴더" }); Small("${index + 1} 페이지부터") }
                        Icon(Icons.Outlined.ChevronRight, null)
                    }; Rule()
                }
            }
        } else LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f).testTag("image_thumbnails"), state = grid,
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages.size, key = { pages[it].key }) { index ->
                Column(Modifier.clickable { go(index) }.testTag("image_thumbnail_$index").border(if (position.page == index) 2.dp else 1.dp,
                    if (position.page == index) LocalPaper.current.accent else LocalPaper.current.line).padding(6.dp)) {
                    ImageThumbnail(opened.source, index, prefs.inverted)
                    Text("${index + 1} · ${pages[index].name}", fontSize = 11.sp, lineHeight = 15.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
@Composable private fun ImageThumbnail(source: ImageSource, index: Int, inverted: Boolean) {
    val bitmap by produceState<ImageBitmap?>(null, source, index) {
        try { value = source.thumbnail(index).asImageBitmap() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    Box(Modifier.fillMaxWidth().height(130.dp).background(if (inverted) Color.Black else Color.White), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            colorFilter = if (inverted) ColorFilter.colorMatrix(ColorMatrix(PDF_INVERSION)) else null)
        else Icon(Icons.Outlined.Image, "미리보기 준비 중", tint = Color.Gray)
    }
}

@Composable fun ImageBookmarksSheet(vm: AppViewModel) {
    val opened = vm.imageReader.opened ?: return
    val marks by vm.imageReader.marks.collectAsStateWithLifecycle()
    if (marks.isEmpty()) EmptyState("접어 둔 이미지가 없어요.", "독서 화면 위쪽의 책갈피로 남겨 보세요.")
    else LazyColumn(Modifier.heightIn(max = 420.dp).testTag("image_bookmark_list")) {
        items(marks, key = { it.id }) { mark ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { if (vm.imageReader.openBookmark(mark)) vm.sheet(null) }.padding(vertical = 16.dp)) {
                    Filename(mark.name); Small("${mark.page + 1} 페이지" + if (mark.version != opened.book.version) " · 변경 전 기록" else "")
                }
                Tool(Icons.Outlined.DeleteOutline, "책갈피 삭제", "image_bookmark_delete", destructive = true, onClick = { vm.imageReader.deleteBookmark(mark.id) })
            }; Rule()
        }
    }
}
