package com.poyal.yeobaek.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyal.yeobaek.*
import com.poyal.yeobaek.data.TxtPreferences
import com.poyal.yeobaek.rendering.*
import com.poyal.yeobaek.txt.sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

@Composable fun ReaderScreen(vm: AppViewModel, opened: OpenBook, prefs: TxtPreferences, modifier: Modifier = Modifier) {
    val colors = readerColors(prefs.paper, LocalPaper.current)
    var chrome by rememberSaveable(opened.token) { mutableStateOf(true) }
    val seek by vm.seek.collectAsStateWithLifecycle()
    val searchQuery by remember(vm) { vm.ui.map { it.searchQuery }.distinctUntilChanged() }.collectAsStateWithLifecycle("")
    var highlights by remember(opened.token) { mutableStateOf<Map<Long, List<IntRange>>>(emptyMap()) }
    var anchor by remember(opened.token) { mutableLongStateOf((vm.currentPosition ?: opened.initial)?.offset ?: 0) }
    var fraction by remember(opened.token) { mutableFloatStateOf((vm.currentPosition ?: opened.initial)?.fraction ?: 0f) }
    var layoutKey by remember(opened.token) { mutableStateOf((vm.currentPosition ?: opened.initial)?.layoutKey ?: "") }
    var consumedSeek by rememberSaveable(opened.token) { mutableLongStateOf(-1L) }
    val list = rememberLazyListState()
    var blocks by remember(opened.token) { mutableStateOf<List<TextBlock>>(emptyList()) }
    var loading by remember(opened.token) { mutableStateOf(true) }
    var failure by remember(opened.token) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val style = TextStyle(fontFamily = if (prefs.serif) Serif else FontFamily.SansSerif,
        fontSize = prefs.size.sp, lineHeight = (prefs.size * prefs.line).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        lineBreak = LineBreak.Simple, hyphens = Hyphens.None, textDirection = TextDirection.ContentOrLtr)
    CompositionLocalProvider(LocalPaper provides colors) {
        BoxWithConstraints(modifier.fillMaxSize().background(colors.surface)) {
            val width = with(density) { (maxWidth - prefs.margin.dp * 2).roundToPx() }.coerceAtLeast(1)
            val key = remember(width, prefs.serif, prefs.size, prefs.line, density.density, density.fontScale) {
                sha256("layout-1|$width|${prefs.serif}|${prefs.size}|${prefs.line}|${density.density}|${density.fontScale}".toByteArray()).take(20)
            }
            val engine = remember(opened.token, key) { TextLayoutEngine(opened.document, key, style, width, textMeasurer) }
            LaunchedEffect(blocks, searchQuery) {
                if (searchQuery.isEmpty()) { highlights = emptyMap(); return@LaunchedEffect }
                highlights = try { withContext(Dispatchers.IO) {
                    blocks.associate { block ->
                        val from = (block.start - searchQuery.length + 1).coerceAtLeast(0)
                        val until = (block.end + searchQuery.length - 1).coerceAtMost(opened.document.length)
                        val around = opened.document.read(from, (until - from).toInt())
                        val ranges = mutableListOf<IntRange>()
                        var at = around.indexOf(searchQuery)
                        while (at >= 0) {
                            val start = (from + at - block.start).toInt().coerceIn(0, block.text.length)
                            val end = (from + at + searchQuery.length - block.start).toInt().coerceIn(0, block.text.length)
                            if (end > start) ranges += start until end
                            at = around.indexOf(searchQuery, at + 1)
                        }
                        block.start to ranges
                    }
                } } catch (e: CancellationException) { throw e } catch (_: java.io.IOException) { emptyMap() }
            }
            LaunchedEffect(engine, seek?.serial) {
                loading = true; failure = null
                try {
                    val newSeek = seek?.takeIf { it.serial != consumedSeek }
                    val target = (newSeek?.offset ?: anchor).coerceIn(0, opened.document.length)
                    val next = engine.around(target)
                    blocks = next
                    val index = next.indexOfLast { it.start <= target }.coerceAtLeast(0)
                    val block = next[index]
                    val local = (target - block.start).toInt().coerceIn(0, block.text.length)
                    val line = block.layout.getLineForOffset(local)
                    val lineHeight = block.layout.getLineBottom(line) - block.layout.getLineTop(line)
                    val pixel = block.layout.getLineTop(line) + if (layoutKey == key && newSeek == null) fraction * lineHeight else 0f
                    if (opened.document.length > 0) list.scrollToItem(index, pixel.toInt())
                    withFrameNanos { }
                    anchor = block.start + block.layout.getLineStart(line)
                    layoutKey = key
                    consumedSeek = seek?.serial ?: -1L
                    vm.position(anchor, key, 0f, target == opened.document.length && opened.document.length > 0)
                    vm.readerReady(opened.token)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e.message ?: "본문을 배치하지 못했습니다." }
                finally { loading = false }
            }
            // A seek cancels pending prefetch before replacing the visible window.
            LaunchedEffect(engine, seek?.serial) {
                snapshotFlow { Triple(list.firstVisibleItemIndex, list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, loading) }
                    .distinctUntilChanged().collect { (first, last, busy) ->
                        if (busy || blocks.isEmpty() || failure != null) return@collect
                        try {
                            if (last >= blocks.lastIndex - 2 && blocks.last().end < opened.document.length) {
                                val more = mutableListOf<TextBlock>()
                                var end = blocks.last().end
                                repeat(4) { if (end < opened.document.length) { val block = engine.block(end); more += block; end = block.end } }
                                val combined = blocks + more
                                blocks = if (combined.size > 24 && first > 8) combined.drop(minOf(first - 4, combined.size - 24)) else combined
                            } else if (first <= 2 && blocks.first().start > 0) {
                                val more = mutableListOf<TextBlock>()
                                var start = blocks.first().start
                                repeat(4) { if (start > 0) { val block = engine.previous(start); more.add(0, block); start = block.start } }
                                val combined = more + blocks
                                blocks = if (combined.size > 24) combined.take(24) else combined
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = e.message ?: "본문을 불러오지 못했습니다." }
                    }
            }
            LaunchedEffect(engine) {
                snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, loading) }
                    .distinctUntilChanged().collect { (index, pixels, busy) ->
                        if (busy) return@collect
                        val block = blocks.getOrNull(index) ?: return@collect
                        val line = block.layout.getLineForVerticalPosition(pixels.toFloat())
                        anchor = block.start + block.layout.getLineStart(line)
                        val top = block.layout.getLineTop(line)
                        val height = block.layout.getLineBottom(line) - top
                        fraction = ((pixels - top) / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        layoutKey = key
                        val complete = blocks.lastOrNull()?.end == opened.document.length && !list.canScrollForward && opened.document.length > 0
                        vm.position(anchor, key, fraction, complete)
                    }
            }
            if (opened.document.length == 0L) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                EmptyState("빈 문서입니다.", "파일에 표시할 본문이 없습니다.")
            } else LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("reader_body")
                    .semantics { customActions = listOf(CustomAccessibilityAction(if (chrome) "독서 메뉴 숨기기" else "독서 메뉴 표시") { chrome = !chrome; true }) }
                    .onKeyEvent { if (it.type == KeyEventType.KeyUp && it.key == Key.Enter) { chrome = !chrome; true } else false }
                    .pointerInput(opened.token) { detectTapGestures(onTap = { chrome = !chrome }, onLongPress = {}) },
                contentPadding = PaddingValues(start = prefs.margin.dp, end = prefs.margin.dp,
                    top = (64f * density.fontScale.coerceAtLeast(1f)).dp, bottom = (84f * density.fontScale.coerceAtLeast(1f)).dp),
            ) {
                items(blocks, key = { it.start }, contentType = { "text" }) { block ->
                    val annotated = remember(block.text, highlights[block.start], colors) {
                        buildAnnotatedString { append(block.text); highlights[block.start]?.forEach { addStyle(SpanStyle(background = colors.soft, color = colors.ink), it.first, it.last + 1) } }
                    }
                    BasicText(annotated, Modifier.fillMaxWidth().testTag("text_${block.start}"), style = style.copy(color = colors.ink))
                }
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                Surface(color = colors.surface) {
                    Column { Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp).testTag("reader_header"), verticalAlignment = Alignment.CenterVertically) {
                        Tool(Icons.Outlined.ArrowBack, "폴더로 돌아가기", "reader_back", onClick = vm::closeReader)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Small("지금 읽는 책 · TXT"); Text(opened.book.name.removeSuffix(".txt"), fontSize = 12.sp, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        ReaderProgress(vm)
                        HeaderBookmark(vm, opened)
                    }; Rule() }
                }
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                Surface(color = colors.surface) {
                    Column { Rule(); Row(Modifier.fillMaxWidth().testTag("reader_footer")) {
                        listOf(Triple("search", "검색", Icons.Outlined.Search), Triple("bookmarks", "책갈피", Icons.Outlined.BookmarkBorder), Triple("txt", "읽기 설정", Icons.Outlined.TextFields), Triple("info", "책 정보", Icons.Outlined.MoreHoriz), Triple("move", "이동", Icons.Outlined.SwapHoriz)).forEach { (id, label, icon) ->
                            Column(Modifier.weight(1f).clickable { vm.sheet(id) }.testTag("reader_$id").heightIn(min = 64.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(icon, label, Modifier.size(22.dp), tint = colors.ink)
                                Text(if (id == "txt" && density.fontScale > 1.3f) "읽기\n설정" else label, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, color = colors.ink, maxLines = 2)
                            }
                        }
                    } }
                }
            }
            if (loading) Surface(Modifier.align(Alignment.Center), color = colors.surface.copy(alpha = .95f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(20.dp).testTag("layout_loading"), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent); Text("본문 배치 중", Modifier.padding(start = 12.dp), color = colors.ink) }
            }
            failure?.let { error -> Surface(Modifier.align(Alignment.Center).padding(24.dp), color = colors.surface) { Column(Modifier.padding(24.dp)) { Text(error, color = colors.ink); PaperButton("다시 시도", "layout_retry", onClick = { vm.openBook(opened.book) }) } } }
        }
    }
}

@Composable private fun HeaderBookmark(vm: AppViewModel, opened: OpenBook) {
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    val offset by vm.locator.collectAsStateWithLifecycle()
    val marked = marks.any { it.version == opened.document.version && it.offset == offset }
    Tool(if (marked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (marked) "현재 문장 책갈피 해제" else "현재 문장 책갈피", "reader_bookmark", Modifier.semantics { selected = marked }, onClick = vm::toggleBookmark)
}

@Composable private fun ReaderProgress(vm: AppViewModel) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    Text(String.format(Locale.US, "%.1f%%", progress * 100), Modifier.testTag("reader_percent"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LocalPaper.current.accent)
}
