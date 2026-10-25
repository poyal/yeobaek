package com.poyal.yeobaek.image

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.LruCache
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import com.poyal.yeobaek.pdf.PDF_INVERSION
import com.poyal.yeobaek.ReadingEndSignal
import com.poyal.yeobaek.ui.ReaderTouchGuard
import kotlinx.coroutines.*
import kotlin.math.*

/** Tiled drawing in both continuous and paged layouts, with no full-size bitmap in the UI heap. */
class ImageDocumentView(context: Context) : View(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var source: ImageSource? = null
    private var pages = emptyList<ImagePage>()
    private var preferences = ImagePreferences()
    private var starts = FloatArray(0)
    private var widths = FloatArray(0)
    private var heights = FloatArray(0)
    private var spreads = emptyList<List<Int>>()
    private var focus = 0
    private var segment = -1
    private val autoCrops = mutableMapOf<Int, CropRect>()
    private val cropping = mutableMapOf<Int, Job>()
    private var deferredPages: List<ImagePage>? = null
    private fun split(index: Int) = preferences.splitWide && preferences.mode == ImageMode.PAGED && height > width && pages.getOrNull(index)?.wide == true
    private fun firstHalf() = if (preferences.direction == ImageDirection.LTR) 0 else 1
    private fun crop(index: Int): CropRect {
        val base = preferences.pageCrops[pages[index].key] ?: when (preferences.cropMode) {
            CropMode.OFF -> CropRect()
            CropMode.MANUAL -> preferences.crop
            CropMode.AUTO -> autoCrops[index] ?: CropRect()
        }
        return if (index == focus && split(index)) base.half(segment == 1) else base
    }
    private fun applyPages(updated: List<ImagePage>) {
        val position = locator(); pages = updated; pending = position
        layoutPages(); restore(); changed()
    }

    private var scroll = 0f
    private var pan = 0f
    private var zoom = 1f
    private var total = 0f
    private var groupWidth = 0f
    private var pending: ImageLocator? = null
    private var ready = false
    private var disposed = false
    private val density = resources.displayMetrics.density
    private var topPad = 60f * density
    private var bottomPad = 76f * density
    private val contentHeight get() = (height - topPad - bottomPad).coerceAtLeast(1f)
    private var backgroundColor = Color.LTGRAY
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * density; textAlign = Paint.Align.CENTER }
    private val scroller = OverScroller(context)
    private data class Tile(val page: Int, val pw: Int, val ph: Int, val x: Int, val y: Int, val w: Int, val h: Int)
    private val cache = object : LruCache<Tile, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: Tile, value: Bitmap) = value.byteCount
    }
    private val jobs = mutableMapOf<Tile, Job>()
    private val measuring = mutableMapOf<Int, Job>()
    private val failures = mutableSetOf<Int>()
    private var visible = emptySet<Tile>()
    private var rendered = 0L
    internal data class RenderStatus(val visible: Int, val complete: Int, val cacheBytes: Int, val cacheLimit: Int,
        val queued: Int, val rendered: Long, val failures: Int, val settled: Boolean)
    internal fun renderStatus() = RenderStatus(visible.size, visible.count { cache.get(it) != null }, cache.size(), cache.maxSize(),
        jobs.size + measuring.size, rendered, failures.size, scroller.isFinished && !scales.isInProgress)
    var onReady: () -> Unit = {}
    var onPosition: (ImageLocator) -> Unit = {}
    var onEndReached: () -> Unit = {}
    private val endSignal = ReadingEndSignal()
    private var touching = false
    var onTap: () -> Unit = {}
    var onFailure: (String) -> Unit = {}
    private var downX = 0f
    private var downY = 0f
    private var pinched = false
    private val touchGuard = ReaderTouchGuard(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var doubleTapPending = false
    private fun tap(e: MotionEvent) {
        if (preferences.tapToTurn && preferences.mode == ImageMode.PAGED && zoom <= 1.01f && (e.x < width * .25f || e.x > width * .75f)) {
            turn((if (e.x > width * .75f) 1 else -1) * (if (preferences.direction == ImageDirection.LTR) 1 else -1))
        } else performClick()
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!disposed && touchGuard.isTap(e.downTime)) tap(e)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { doubleTapPending = touchGuard.isTap(e.downTime); return true }
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (!disposed && doubleTapPending && touchGuard.isTap(e.downTime)) {
                    if (preferences.doubleTapZoom) scaleTo(if (zoom > 1.1f) 1f else 2f, e.x, e.y) else tap(e)
                }
                doubleTapPending = false
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) { touchGuard.reject() }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!touchGuard.multiplePointers && !scales.isInProgress) {
                if (preferences.mode == ImageMode.VERTICAL && abs(dy) > abs(dx)) endSignal.advance(dy > 0)
                scroll = (scroll + dy).coerceIn(0f, maxScroll())
                val overflow = (if (preferences.mode == ImageMode.VERTICAL) widths.getOrElse(pageAt(scroll)) { 0f } else groupWidth) - width
                if (overflow > 0f) pan = (pan + dx / overflow).coerceIn(0f, 1f)
                changed()
            }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (touchGuard.multiplePointers) return false
            if (scales.isInProgress || abs(vx) > abs(vy)) return false
            scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt()); postInvalidateOnAnimation(); return true
        }
    })
    private val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { endSignal.cancel(); pinched = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleTo((zoom * detector.scaleFactor).coerceIn(1f, 5f), detector.focusX, detector.focusY); return true
        }
    })
    init { scales.isQuickScaleEnabled = false; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun open(value: ImageSource, initial: ImageLocator) {
        endSignal.cancel()
        source = value; pages = value.pages.value; pending = initial.constrained(pages); zoom = pending!!.zoom; focus = pending!!.page
        layoutPages(); restore(); invalidate()
        scope.launch {
            value.pages.collect { updated ->
                if (updated != pages) {
                    if (touching || !scroller.isFinished || scales.isInProgress) deferredPages = updated
                    else applyPages(updated)
                }
            }
        }
    }
    fun configure(value: ImagePreferences, background: Int) {
        if (preferences == value && background == backgroundColor) return
        val position = locator()
        val relayout = preferences.copy(inverted = value.inverted, canvas = value.canvas, doubleTapZoom = value.doubleTapZoom, pinchZoom = value.pinchZoom, tapToTurn = value.tapToTurn, swipeToTurn = value.swipeToTurn, menuVisibility = value.menuVisibility) != value
        preferences = value; backgroundColor = background
        if (relayout) { pending = position; layoutPages(); restore() }
        invalidate()
    }
    fun setContentInsets(top: Int, bottom: Int) {
        if (topPad == top.toFloat() && bottomPad == bottom.toFloat()) return
        pending = locator(); topPad = top.toFloat(); bottomPad = bottom.toFloat(); layoutPages(); restore(); invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (pending == null) pending = locator()
        layoutPages(); restore(); invalidate()
    }
    private fun double() = preferences.mode == ImageMode.PAGED && (preferences.spread == ImageSpread.DOUBLE ||
        preferences.spread == ImageSpread.LANDSCAPE && width > height)
    private fun group() = if (split(focus)) listOf(focus) else spreads.firstOrNull { focus in it } ?: listOf(focus)
    private fun layoutPages() {
        visible = emptySet()
        if (width <= 0 || height <= 0 || pages.isEmpty()) return
        focus = (pending?.constrained(pages)?.page ?: focus).coerceIn(pages.indices)
        segment = if (split(focus)) pending?.let { if (it.segment >= 0) it.segment else if (it.x > 0f) (if (it.x >= .5f) 1 else 0) else firstHalf() } ?: segment.takeIf { it >= 0 } ?: firstHalf()
            else pending?.segment ?: segment
        starts = FloatArray(pages.size); widths = FloatArray(pages.size); heights = FloatArray(pages.size)
        spreads = imageSpreads(pages, double(), preferences.coverAlone)
        val gap = preferences.spacing * density
        if (preferences.mode == ImageMode.VERTICAL) {
            var y = 0f
            pages.forEachIndexed { i, size ->
                val c = crop(i)
                val ratio = size.height * c.height / (size.width * c.width)
                val available = width.toFloat()
                val base = if (preferences.fit == ImageFit.PAGE) min(available, contentHeight / ratio) else available
                starts[i] = y; widths[i] = base * zoom; heights[i] = widths[i] * ratio
                y += heights[i] + gap
            }
            total = (y - gap).coerceAtLeast(0f)
        } else {
            val group = group()
            val available = (width - gap * (group.size - 1)) / group.size
            group.forEach { i ->
                val c = crop(i)
                val ratio = pages[i].height * c.height / (pages[i].width * c.width)
                val base = if (preferences.fit == ImageFit.PAGE) min(available, contentHeight / ratio) else available
                widths[i] = base * zoom; heights[i] = widths[i] * ratio
            }
            total = group.maxOf { heights[it] }
            groupWidth = group.sumOf { widths[it].toDouble() }.toFloat() + gap
            if (group.size == 1) groupWidth -= gap
        }
    }
    private fun maxScroll() = if (preferences.mode == ImageMode.VERTICAL) max((total - contentHeight).coerceAtLeast(0f), starts.lastOrNull() ?: 0f)
        else (total - contentHeight).coerceAtLeast(0f)
    private fun pageAt(y: Float): Int {
        if (starts.isEmpty()) return focus
        val index = starts.binarySearch(y)
        return if (index >= 0) index else (-index - 2).coerceIn(starts.indices)
    }
    private fun rect(index: Int): RectF {
        val w = widths[index]; val h = heights[index]
        if (preferences.mode == ImageMode.VERTICAL) {
            val left = if (w <= width) (width - w) / 2 else -pan * (w - width)
            return RectF(left, topPad + starts[index] - scroll, left + w, topPad + starts[index] - scroll + h)
        }
        val group = group().let { if (preferences.direction == ImageDirection.RTL) it.reversed() else it }
        var left = if (groupWidth <= width) (width - groupWidth) / 2 else -pan * (groupWidth - width)
        for (i in group) { if (i == index) break; left += widths[i] + preferences.spacing * density }
        val top = topPad + (if (total <= contentHeight) (contentHeight - total) / 2 else -scroll) + (total - h) / 2
        return RectF(left, top, left + w, top + h)
    }
    private fun restore() {
        val p = pending?.constrained(pages) ?: return
        if (widths.isEmpty() || widths[p.page] <= 0) return
        focus = p.page
        val c = crop(p.page)
        val localX = ((p.x - c.left) / c.width).coerceIn(0f, 1f)
        val localY = ((p.y - c.top) / c.height).coerceIn(0f, 1f)
        if (preferences.mode == ImageMode.VERTICAL) {
            scroll = if (p.completed) maxScroll() else (starts[p.page] + heights[p.page] * localY).coerceIn(0f, maxScroll())
            pan = if (widths[p.page] > width) (localX * widths[p.page] / (widths[p.page] - width)).coerceIn(0f, 1f) else 0f
        } else {
            scroll = ((total - heights[focus]) / 2 + heights[focus] * localY).coerceIn(0f, maxScroll())
            pan = 0f
            if (groupWidth > width) pan = ((rect(focus).left + widths[focus] * localX) / (groupWidth - width)).coerceIn(0f, 1f)
        }
        pending = null
    }
    fun locator(): ImageLocator {
        pending?.let { return it }
        if (widths.isEmpty() || pages.isEmpty()) return ImageLocator()
        val page = if (preferences.mode == ImageMode.VERTICAL) pageAt(scroll) else focus
        val r = rect(page)
        val c = crop(page)
        return ImageLocator(page, pages[page].key, c.left + (-r.left / r.width()).coerceIn(0f, 1f) * c.width,
            c.top + ((topPad - r.top) / r.height()).coerceIn(0f, 1f) * c.height, zoom,
            if (preferences.mode == ImageMode.VERTICAL) scroll >= maxScroll() - 1
            else group().last() == pages.lastIndex && scroll >= maxScroll() - 1 && (!split(page) || segment != firstHalf()), segment)
    }
    fun move(value: ImageLocator, announceEnd: Boolean = true) {
        if (pages.isEmpty()) return
        endSignal.advance(announceEnd)
        scroller.forceFinished(true); pending = value.constrained(pages); zoom = pending!!.zoom; focus = pending!!.page
        layoutPages(); restore(); changed()
    }
    fun turn(delta: Int) {
        if (pages.isEmpty()) return
        if (preferences.mode == ImageMode.VERTICAL) {
            endSignal.advance(delta > 0)
            scroll = (scroll + contentHeight * delta).coerceIn(0f, maxScroll()); changed()
        } else {
            if (split(focus) && ((delta > 0 && segment == firstHalf()) || (delta < 0 && segment != firstHalf()))) {
                move(ImageLocator(page = focus, segment = 1 - segment), announceEnd = false)
                return
            }
            val current = spreads.indexOfFirst { focus in it }
            val target = (current + delta).coerceIn(spreads.indices)
            if (target != current) move(ImageLocator(page = spreads[target].first(), segment = if (delta < 0 && split(spreads[target].first())) 1 - firstHalf() else -1), announceEnd = false)
            else { endSignal.advance(delta > 0); changed() }
        }
    }
    fun resetZoom() { scroller.forceFinished(true); scaleTo(1f, width / 2f, topPad + contentHeight / 2f) }
    fun retry() { failures.clear(); invalidate() }
    private fun changed() {
        visible = emptySet()
        if (ready && pending == null) onPosition(locator())
        contentDescription = "이미지 ${locator().page + 1} / ${pages.size} 페이지${if (split(focus)) if (segment == 0) " · 왼쪽" else " · 오른쪽" else ""} · ${(zoom * 100).toInt()}%"
        invalidate()
    }
    private fun hit(x: Float, y: Float): Triple<Int, Float, Float>? {
        val candidates = if (preferences.mode == ImageMode.VERTICAL) listOf(pageAt(scroll + y - topPad)) else group()
        candidates.forEach { page ->
            if (page in widths.indices) { val r = rect(page); if (r.contains(x, y)) return Triple(page, (x - r.left) / r.width(), (y - r.top) / r.height()) }
        }
        return null
    }
    private fun scaleTo(value: Float, x: Float, y: Float) {
        endSignal.cancel()
        if (widths.isEmpty()) return
        val point = hit(x, y) ?: Triple(locator().page, .5f, 0f)
        focus = point.first; zoom = value; layoutPages()
        pan = 0f; scroll = 0f
        val r = rect(point.first)
        scroll = (r.top + point.third * r.height() - y).coerceIn(0f, maxScroll())
        val overflow = (if (preferences.mode == ImageMode.VERTICAL) r.width() else groupWidth) - width
        if (overflow > 0) pan = ((r.left + point.second * r.width() - x) / overflow).coerceIn(0f, 1f)
        changed()
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        val doc = source ?: return
        if (widths.isEmpty()) return
        val needed = linkedSetOf<Tile>()
        var visibleMeasured = true
        val indices = if (preferences.mode == ImageMode.PAGED) group() else {
            val first = pageAt((scroll - topPad).coerceAtLeast(0f))
            (first..pageAt(scroll + height)).toList()
        }
        paint.colorFilter = if (preferences.inverted) ColorMatrixColorFilter(PDF_INVERSION) else null
        indices.forEach { page ->
            val area = rect(page)
            if (preferences.cropMode == CropMode.AUTO && page !in autoCrops && page !in failures && preferences.pageCrops[pages[page].key] == null) {
                visibleMeasured = false
                prepareCrop(page)
                label.color = Color.GRAY
                canvas.drawText("여백 확인 중", width / 2f, topPad + 48 * density, label)
                return@forEach
            }
            val c = crop(page)
            val rw = area.width() / c.width; val rh = area.height() / c.height
            val r = RectF(area.left - c.left * rw, area.top - c.top * rh, area.left + (1 - c.left) * rw, area.top + (1 - c.top) * rh)
            if (!area.intersects(0f, 0f, width.toFloat(), height.toFloat())) return@forEach
            if (!pages[page].measured) visibleMeasured = false
            val save = canvas.save()
            canvas.clipRect(area)
            paint.color = Color.WHITE; canvas.drawRect(r, paint)
            if (!pages[page].measured && page !in failures) measure(page)
            cache.snapshot().filterKeys { it.page == page }.forEach { (tile, bitmap) -> drawTile(canvas, tile, bitmap, r) }
            if (pages[page].measured) {
                val rasterZoom = ceil(zoom * 2) / 2
                val pw = (r.width() / zoom * rasterZoom).roundToInt().coerceIn(1, 200_000)
                val ph = (r.height() / zoom * rasterZoom).roundToInt().coerceIn(1, 200_000)
                val sx = r.width() / pw; val sy = r.height() / ph
                val fromX = (max(0f, max(0f, area.left) - r.left) / sx / 512).toInt() * 512
                val fromY = (max(0f, max(0f, area.top) - r.top) / sy / 512).toInt() * 512
                val untilX = min(pw, ceil((min(width.toFloat(), area.right) - r.left) / sx).toInt())
                val untilY = min(ph, ceil((min(height.toFloat(), area.bottom) - r.top) / sy).toInt())
                for (y in fromY until untilY step 512) for (x in fromX until untilX step 512) {
                    val tile = Tile(page, pw, ph, x, y, min(512, pw - x), min(512, ph - y)); needed += tile
                    cache.get(tile)?.let { drawTile(canvas, tile, it, r) }
                }
            }
            if (page in failures || needed.none { it.page == page && cache.get(it) != null }) {
                label.color = if (preferences.inverted) Color.LTGRAY else Color.DKGRAY
                canvas.drawText(if (page in failures) "이미지를 읽지 못했습니다" else "${page + 1} 페이지 준비 중", r.centerX().coerceIn(0f, width.toFloat()),
                    (r.top + 48 * density).coerceIn(topPad + 24 * density, height - bottomPad), label)
            }
            canvas.restoreToCount(save)
        }
        visible = needed.toSet()
        jobs.keys.filter { it !in needed }.forEach { jobs.remove(it)?.cancel() }
        needed.filter { cache.get(it) == null && it !in jobs && it.page !in failures }.forEach { tile ->
            jobs[tile] = scope.launch {
                try {
                    val bitmap = doc.render(tile.page, tile.pw, tile.ph, tile.x, tile.y, tile.w, tile.h)
                    ensureActive(); cache.put(tile, bitmap); rendered++
                    if (!ready) { ready = true; onReady(); changed() }; invalidate()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (failures.add(tile.page)) onFailure(e.message ?: "이미지를 표시하지 못했습니다."); invalidate() }
                finally { if (jobs[tile] == coroutineContext[Job]) jobs.remove(tile) }
            }
        }
        // Headers of the next two pages allow wide spreads to become single pages before a turn.
        if (ready && needed.all { cache.get(it) != null }) {
            listOf(indices.last() + 1, indices.last() + 2).filter { it in pages.indices && !pages[it].measured && it !in failures }.forEach(::measure)
        }
        if (endSignal.take(locator().completed, !touching && scroller.isFinished && !scales.isInProgress,
                ready && visibleMeasured && visible.isNotEmpty() && visible.all { cache.get(it) != null })) {
            if (!disposed) onEndReached()
        }
    }
    private fun prepareCrop(page: Int) {
        if (page in cropping) return
        cropping[page] = scope.launch {
            try {
                val bitmap = source?.thumbnail(page) ?: return@launch
                val result = withContext(Dispatchers.Default) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    detectImageCrop(pixels, bitmap.width, bitmap.height)
                }
                bitmap.recycle()
                // Apply only at rest; an asynchronous crop must not move the current gesture.
                while (touching || !scroller.isFinished || scales.isInProgress) delay(32)
                val position = locator(); autoCrops[page] = result; pending = position
                layoutPages(); restore(); changed()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { autoCrops[page] = CropRect() }
            finally { cropping.remove(page); invalidate() }
        }
    }
    private fun applyDeferred() {
        if (!touching && scroller.isFinished && !scales.isInProgress) deferredPages?.let { deferredPages = null; applyPages(it) }
    }
    private fun measure(page: Int) {
        if (page in measuring) return
        measuring[page] = scope.launch {
            try { source?.measure(page) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (failures.add(page) && page == locator().page) onFailure(e.message ?: "이미지를 읽지 못했습니다.") }
            finally { measuring.remove(page); invalidate() }
        }
    }
    private fun drawTile(canvas: Canvas, tile: Tile, bitmap: Bitmap, r: RectF) {
        val sx = r.width() / tile.pw; val sy = r.height() / tile.ph
        canvas.drawBitmap(bitmap, null, RectF(r.left + tile.x * sx, r.top + tile.y * sy,
            r.left + (tile.x + tile.w) * sx, r.top + (tile.y + tile.h) * sy), paint)
    }
    override fun computeScroll() { if (scroller.computeScrollOffset()) { scroll = scroller.currY.toFloat(); changed(); postInvalidateOnAnimation() }; applyDeferred() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchGuard.begin(event.downTime, event.x, event.y, !scroller.isFinished)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) touchGuard.move(event.getHistoricalX(i), event.getHistoricalY(i))
                touchGuard.move(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> touchGuard.move(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> { touchGuard.reject(); doubleTapPending = false }
        }
        if (event.pointerCount > 1) touchGuard.multiple()
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touching = false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) endSignal.cancel()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y; pinched = false }
        if (event.pointerCount > 1) pinched = true
        if (preferences.pinchZoom) scales.onTouchEvent(event); gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX; val dy = event.y - downY
            if (preferences.swipeToTurn && !pinched && zoom <= 1.01f && preferences.mode == ImageMode.PAGED && abs(dx) > 48 * density && abs(dx) > abs(dy) * 1.2f) {
                turn((if (dx < 0) 1 else -1) * if (preferences.direction == ImageDirection.LTR) 1 else -1)
            }
            changed()
        }
        applyDeferred()
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            turn(if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
    fun dispose() { if (!disposed) { touchGuard.clear(); doubleTapPending = false; disposed = true; scope.cancel(); cache.evictAll(); scroller.forceFinished(true) } }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); dispose() }
}
