package com.poyal.yeobaek.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyal.yeobaek.*
import com.poyal.yeobaek.data.TxtPreferences
import com.poyal.yeobaek.rendering.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

@Composable fun ReaderScreen(vm: AppViewModel, opened: OpenBook, prefs: TxtPreferences, modifier: Modifier = Modifier,
    list: LazyListState = key(opened.token) { rememberLazyListState() }) {
    val colors = readerColors(prefs.paper, LocalPaper.current)
    var chrome by rememberSaveable(opened.token) { mutableStateOf(true) }
    val seek by vm.seek.collectAsStateWithLifecycle()
    val searchQuery by remember(vm) { vm.ui.map { it.searchQuery }.distinctUntilChanged() }.collectAsStateWithLifecycle("")
    var highlights by remember(opened.token) { mutableStateOf<Map<Long, List<IntRange>>>(emptyMap()) }
    var anchor by remember(opened.token) { mutableLongStateOf((vm.currentPosition ?: opened.initial)?.offset ?: 0) }
    var fraction by remember(opened.token) { mutableFloatStateOf((vm.currentPosition ?: opened.initial)?.fraction ?: 0f) }
    var layoutKey by remember(opened.token) { mutableStateOf((vm.currentPosition ?: opened.initial)?.layoutKey ?: "") }
    var consumedSeek by rememberSaveable(opened.token) { mutableLongStateOf(-1L) }
    var window by remember(opened.token) { mutableStateOf(TextWindow("", emptyList())) }
    val blocks = window.blocks
    var generation by remember(opened.token) { mutableLongStateOf(0L) }
    var loading by remember(opened.token) { mutableStateOf(true) }
    var failure by remember(opened.token) { mutableStateOf<String?>(null) }
    var forwardIntent by remember(opened.token) { mutableStateOf(false) }
    val flingGuard = remember(opened.token) { ReaderFlingGuard() }
    val diagnostics = vm.scrollDiagnostics
    DisposableEffect(opened.token) {
        diagnostics.record("reader_open", "session=${opened.token} length=${opened.document.length} anchor=$anchor")
        onDispose { diagnostics.record("reader_close", "session=${opened.token} anchor=$anchor") }
    }
    val endScroll = remember(opened.token) { object : NestedScrollConnection {
        var lastScrollLog = 0L
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) forwardIntent = available.y < 0f
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastScrollLog >= 100) {
                diagnostics.record("scroll_input", "session=${opened.token} dy=${available.y} source=$source", now)
                lastScrollLog = now
            }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            val suppressed = flingGuard.suppressFling(android.os.SystemClock.uptimeMillis())
            diagnostics.record("fling_start", "session=${opened.token} vx=${available.x} vy=${available.y} forward=$forwardIntent " +
                "suppressed=$suppressed touchTravelPx=${flingGuard.maxTravel}")
            return if (suppressed) Velocity(0f, available.y) else Velocity.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            diagnostics.record("fling_end", "session=${opened.token} consumedY=${consumed.y} remainingY=${available.y}")
            return Velocity.Zero
        }
    } }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val style = prefs.textStyle()
    CompositionLocalProvider(LocalPaper provides colors) {
        BoxWithConstraints(modifier.fillMaxSize().background(colors.surface)) {
            val width = with(density) { (maxWidth - prefs.margin.dp * 2).roundToPx() }.coerceAtLeast(1)
            val key = remember(width, prefs.font, prefs.size, prefs.line, density.density, density.fontScale) {
                prefs.layoutKey(width, density.density, density.fontScale)
            }
            val engine = remember(opened.token, key) { TextLayoutEngine(opened.document, key, style, width, textMeasurer) }
            val imeBottom = WindowInsets.ime.getBottom(density)
            LaunchedEffect(key, maxHeight, imeBottom) {
                diagnostics.record("viewport", "session=${opened.token} layout=$key widthPx=$width heightDp=$maxHeight imeBottomPx=$imeBottom " +
                    "font=${prefs.font} size=${prefs.size} line=${prefs.line} density=${density.density} fontScale=${density.fontScale}")
            }
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
                val request = seek
                val activeGeneration = ++generation
                loading = true; failure = null
                try {
                    val newSeek = request?.takeIf { it.serial != consumedSeek }
                    val target = (newSeek?.offset ?: anchor).coerceIn(0, opened.document.length)
                    diagnostics.record("restore_start", "session=${opened.token} generation=$activeGeneration layout=$key target=$target seek=${newSeek?.serial}")
                    forwardIntent = newSeek != null && target == opened.document.length
                    val next = engine.around(target)
                    currentCoroutineContext().ensureActive()
                    val replacement = TextWindow("${opened.token}:$key:$activeGeneration", next)
                    window = replacement
                    val index = next.indexOfLast { it.start <= target }.coerceAtLeast(0)
                    val block = next[index]
                    val local = (target - block.start).toInt().coerceIn(0, block.text.length)
                    val line = block.layout.getLineForOffset(local)
                    val lineHeight = block.layout.getLineBottom(line) - block.layout.getLineTop(line)
                    val pixel = block.layout.getLineTop(line) + if (layoutKey == key && newSeek == null) fraction * lineHeight else 0f
                    if (opened.document.length > 0) {
                        // Apply the data set and the requested position in the SAME measure pass.
                        // scrollToItem can otherwise measure/clamp against the previous window.
                        list.requestScrollToItem(index, pixel.toInt())
                        val measured = snapshotFlow { replacement.viewport(list) }.filterNotNull().first()
                        anchor = measured.anchor
                        fraction = measured.fraction
                        vm.position(anchor, key, fraction, measured.atEnd && next.last().end == opened.document.length)
                    } else {
                        anchor = 0; fraction = 0f
                        vm.position(0, key, 0f, false)
                    }
                    layoutKey = key
                    consumedSeek = request?.serial ?: -1L
                    diagnostics.record("restore_end", "session=${opened.token} generation=$activeGeneration anchor=$anchor fraction=$fraction")
                    vm.readerReady(opened.token)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    diagnostics.record("restore_error", "session=${opened.token} type=${e.javaClass.simpleName}")
                    failure = e.message ?: "본문을 배치하지 못했습니다."
                }
                finally { if (generation == activeGeneration) loading = false }
            }
            // A seek cancels pending prefetch before replacing the visible window.
            LaunchedEffect(engine, seek?.serial) {
                snapshotFlow { if (loading || failure != null) null else window.viewport(list) }
                    .collect { viewport ->
                        if (viewport == null) return@collect
                        try {
                            val base = window
                            val before = base.viewport(list)
                            base.prefetch(engine, { base.viewport(list) }, { window === base && !loading })
                                ?.let {
                                    val atCommit = base.viewport(list)
                                    diagnostics.record("window_commit", "session=${opened.token} generation=${base.generation} " +
                                        "old=${base.blocks.first().start}..${base.blocks.last().end} new=${it.blocks.first().start}..${it.blocks.last().end} " +
                                        "requestKey=${before?.block?.start} requestPx=${before?.pixels} currentKey=${atCommit?.block?.start} currentPx=${atCommit?.pixels}")
                                    window = it
                                }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            diagnostics.record("prefetch_error", "session=${opened.token} type=${e.javaClass.simpleName}")
                            failure = e.message ?: "본문을 불러오지 못했습니다."
                        }
                    }
            }
            LaunchedEffect(engine) {
                var lastLog = 0L
                var lastBlock = -1L
                var previousAnchor = anchor
                snapshotFlow { if (loading || failure != null) null else window.viewport(list) }
                    .collect { viewport ->
                        if (viewport == null) return@collect
                        anchor = viewport.anchor
                        fraction = viewport.fraction
                        layoutKey = key
                        val complete = window.blocks.lastOrNull()?.end == opened.document.length && viewport.atEnd && opened.document.length > 0
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastLog >= 250 || lastBlock != viewport.block.start || anchor < previousAnchor && forwardIntent) {
                            diagnostics.record("position", "session=${opened.token} generation=${window.generation} block=${viewport.block.start} " +
                                "pixels=${viewport.pixels} anchor=$anchor fraction=$fraction previous=$previousAnchor " +
                                "scrolling=${list.isScrollInProgress} forward=$forwardIntent complete=$complete", now)
                            lastLog = now; lastBlock = viewport.block.start
                        }
                        previousAnchor = anchor
                        vm.position(anchor, key, fraction, complete)
                    }
            }
            LaunchedEffect(engine) {
                snapshotFlow {
                    Triple(!loading && failure == null && opened.document.length > 0 &&
                        window.blocks.lastOrNull()?.end == opened.document.length && window.viewport(list)?.atEnd == true,
                        list.isScrollInProgress, forwardIntent)
                }.collect { (complete, scrolling, advancing) ->
                    if (!loading) vm.readingProgress(opened.token, complete)
                    if (complete && !scrolling && advancing) {
                        forwardIntent = false
                        vm.position(anchor, layoutKey, fraction, true)
                        vm.readingFinished(opened.token)
                    }
                }
            }
            if (opened.document.length == 0L) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                EmptyState("빈 문서입니다.", "파일에 표시할 본문이 없습니다.")
            } else LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("reader_body").nestedScroll(endScroll)
                    .observeReaderFlingGesture(flingGuard)
                    .semantics { stateDescription = if (list.isScrollInProgress) "스크롤 중" else "정지"; customActions = listOf(CustomAccessibilityAction(if (chrome) "독서 메뉴 숨기기" else "독서 메뉴 표시") { chrome = !chrome; true }) }
                    .onKeyEvent { if (it.type == KeyEventType.KeyUp && it.key == Key.Enter) { chrome = !chrome; true } else false }
                    .pointerInput(opened.token) { detectTapGestures(onTap = { chrome = !chrome }, onLongPress = {}) },
                contentPadding = PaddingValues(start = prefs.margin.dp, end = prefs.margin.dp,
                    top = (64f * density.fontScale.coerceAtLeast(1f)).dp, bottom = (84f * density.fontScale.coerceAtLeast(1f)).dp),
            ) {
                items(blocks, key = window::itemKey, contentType = { "text" }) { block ->
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
                    ReaderToolbar(listOf(
                        ReaderToolbarItem(ReaderAction.MOVE, "reader_move") { vm.sheet("move") },
                        ReaderToolbarItem(ReaderAction.BOOKMARKS, "reader_bookmarks") { vm.sheet("bookmarks") },
                        ReaderToolbarItem(ReaderAction.SEARCH, "reader_search") { vm.sheet("search") },
                        ReaderToolbarItem(ReaderAction.INFO, "reader_info") { vm.sheet("info") },
                        ReaderToolbarItem(ReaderAction.SETTINGS, "reader_txt", onClick = { vm.sheet("quick_settings") }),
                    ), Modifier.testTag("reader_footer"))
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
