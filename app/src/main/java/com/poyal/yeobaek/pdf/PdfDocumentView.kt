package com.poyal.yeobaek.pdf

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.LruCache
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.ReadingEndSignal
import kotlinx.coroutines.*
import kotlin.math.*

/** Viewport tiles stay bounded at every zoom; highlights are a separate, non-inverted layer. */
class PdfDocumentView(context: Context) : View(context) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var document: PdfDocumentHandle? = null
    private var settings = PdfPreferences()
    private var starts = FloatArray(0)
    private var widths = FloatArray(0)
    private var heights = FloatArray(0)
    private var total = 0f
    private var scroll = 0f
    private var pan = 0f
    private var zoom = 1f
    private var pendingLocator: PdfLocator? = null
    private var ready = false
    private var disposed = false
    private var canvasColor = Color.LTGRAY
    private val density = resources.displayMetrics.density
    private var topPad = 60f * density
    private var bottomPad = 76f * density
    private val contentHeight get() = (height - topPad - bottomPad).coerceAtLeast(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x665D91DE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * density; textAlign = Paint.Align.CENTER }
    private val scroller = OverScroller(context)
    private data class Tile(val page: Int, val pw: Int, val ph: Int, val x: Int, val y: Int, val w: Int, val h: Int)
    private val cache = object : LruCache<Tile, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: Tile, value: Bitmap) = value.byteCount
    }
    private val jobs = mutableMapOf<Tile, Job>()
    private val failed = mutableSetOf<Int>()
    private var visibleTiles = emptySet<Tile>()
    private var renderedTiles = 0L
    internal data class RenderStatus(val visible: Int, val complete: Int, val cacheBytes: Int,
        val cacheLimit: Int, val queued: Int, val rendered: Long, val failures: Int, val settled: Boolean)
    internal fun renderStatus() = RenderStatus(visibleTiles.size, visibleTiles.count { cache.get(it) != null },
        cache.size(), cache.maxSize(), jobs.size, renderedTiles, failed.size, scroller.isFinished && !scaleDetector.isInProgress)
    var onReady: () -> Unit = {}
    var onPosition: (PdfLocator) -> Unit = {}
    var onEndReached: () -> Unit = {}
    private val endSignal = ReadingEndSignal()
    private var touching = false
    var onTap: () -> Unit = {}
    var onFailure: (String) -> Unit = {}
    var onSelection: (Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> }
    var onClearSelection: () -> Unit = {}
    var highlight: PdfTextRange? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var selection: PdfTextRange? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    private var selecting: Triple<Int, Float, Float>? = null
    private var selectionEnd: Pair<Float, Float>? = null
    private var lastSelection = 0L
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (selection != null) onClearSelection() else onTap(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { scaleTo(if (zoom > 1.1f) 1f else 2f, e.x, e.y); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaleDetector.isInProgress && selecting == null) {
                if (abs(dy) > abs(dx)) endSignal.advance(dy > 0)
                scroll = (scroll + dy).coerceIn(0f, maxScroll())
                val page = pageAt(scroll)
                val overflow = ((widths.getOrNull(page) ?: 0f) - width).coerceAtLeast(0f)
                if (overflow > 0) pan = (pan + dx / overflow).coerceIn(0f, 1f)
                changed()
            }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (selecting != null || scaleDetector.isInProgress) return false
            scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt()); postInvalidateOnAnimation(); return true
        }
        override fun onLongPress(e: MotionEvent) {
            endSignal.cancel()
            hit(e.x, e.y)?.let { point -> selecting = point; onSelection(point.first, point.second, point.third, point.second, point.third) }
        }
    })
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { endSignal.cancel(); selecting = null; onClearSelection(); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleTo((zoom * detector.scaleFactor).coerceIn(1f, 5f), detector.focusX, detector.focusY); return true
        }
    })
    init { scaleDetector.isQuickScaleEnabled = false; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun open(doc: PdfDocumentHandle, initial: PdfLocator) {
        if (document === doc) return
        jobs.values.forEach { it.cancel() }; jobs.clear(); cache.evictAll(); failed.clear()
        document = doc; ready = false; pendingLocator = initial; zoom = initial.zoom.coerceIn(1f, 5f)
        endSignal.cancel()
        layoutPages(); restorePending(); invalidate()
    }
    fun configure(value: PdfPreferences, background: Int) {
        if (settings == value && canvasColor == background) return
        val current = if (starts.isNotEmpty()) locator() else pendingLocator
        val relayout = settings.fit != value.fit || settings.spacing != value.spacing
        settings = value; canvasColor = background
        if (relayout) { pendingLocator = current; layoutPages(); restorePending() }
        invalidate()
    }
    fun setContentInsets(top: Int, bottom: Int) {
        if (topPad == top.toFloat() && bottomPad == bottom.toFloat()) return
        if (pendingLocator == null && starts.isNotEmpty()) pendingLocator = locator()
        topPad = top.toFloat(); bottomPad = bottom.toFloat()
        layoutPages(); restorePending(); invalidate()
    }
    fun move(value: PdfLocator) {
        endSignal.advance(true)
        scroller.forceFinished(true); pendingLocator = value; zoom = value.zoom.coerceIn(1f, 5f)
        layoutPages(); restorePending(); changed()
    }
    fun retry() { failed.clear(); invalidate() }
    private fun layoutPages() {
        visibleTiles = emptySet()
        val doc = document ?: return
        if (width <= 0 || height <= 0) return
        starts = FloatArray(doc.pages.size); widths = FloatArray(doc.pages.size); heights = FloatArray(doc.pages.size)
        var y = 0f
        doc.pages.forEachIndexed { i, size ->
            val ratio = size.height.toFloat() / size.width
            val available = (width - 16 * density).coerceAtLeast(1f)
            val base = if (settings.fit == PdfFit.PAGE) min(available, contentHeight / ratio) else available
            starts[i] = y; widths[i] = base * zoom; heights[i] = widths[i] * ratio
            y += heights[i] + settings.spacing * density
        }
        total = y - settings.spacing * density
    }
    private fun restorePending() {
        val value = pendingLocator ?: return
        if (starts.isEmpty()) return
        val p = value.constrained(starts.size)
        scroll = if (p.completed) maxScroll() else (starts[p.page] + heights[p.page] * p.y).coerceIn(0f, maxScroll())
        pan = if (widths[p.page] > width) (p.x * widths[p.page] / (widths[p.page] - width)).coerceIn(0f, 1f) else 0f
        pendingLocator = null
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (pendingLocator == null && starts.isNotEmpty()) pendingLocator = locator()
        layoutPages(); restorePending()
    }
    private fun maxScroll() = max((total - contentHeight).coerceAtLeast(0f), starts.lastOrNull() ?: 0f)
    private fun pageAt(y: Float): Int {
        if (starts.isEmpty()) return 0
        val found = starts.binarySearch(y)
        return if (found >= 0) found else (-found - 2).coerceIn(starts.indices)
    }
    private fun left(page: Int) = if (widths[page] <= width) (width - widths[page]) / 2 else -pan * (widths[page] - width)
    fun locator(): PdfLocator {
        if (starts.isEmpty()) return pendingLocator ?: PdfLocator()
        val page = pageAt(scroll)
        return PdfLocator(page, (-left(page) / widths[page]).coerceAtLeast(0f), ((scroll - starts[page]) / heights[page]).coerceIn(0f, 1f), zoom,
            total <= contentHeight || scroll >= maxScroll() - 1)
    }
    private fun changed() {
        visibleTiles = emptySet()
        if (ready && pendingLocator == null) onPosition(locator())
        val page = locator().page + 1
        contentDescription = "PDF $page / ${starts.size} 페이지 · ${(zoom * 100).toInt()}%"
        invalidate()
    }
    private fun hit(x: Float, y: Float): Triple<Int, Float, Float>? {
        if (starts.isEmpty()) return null
        val at = y + scroll - topPad; val page = pageAt(at)
        val nx = (x - left(page)) / widths[page]; val ny = (at - starts[page]) / heights[page]
        return if (nx in 0f..1f && ny in 0f..1f) Triple(page, nx, ny) else null
    }
    private fun scaleTo(value: Float, x: Float, y: Float) {
        endSignal.cancel()
        if (starts.isEmpty()) return
        val point = hit(x, y) ?: Triple(pageAt(scroll), .5f, 0f)
        zoom = value; layoutPages()
        val page = point.first
        scroll = (starts[page] + point.third * heights[page] - y + topPad).coerceIn(0f, maxScroll())
        pan = if (widths[page] > width) ((point.second * widths[page] - x) / (widths[page] - width)).coerceIn(0f, 1f) else 0f
        changed()
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(canvasColor)
        val doc = document ?: return
        if (starts.isEmpty()) return
        val needed = linkedSetOf<Tile>()
        paint.colorFilter = if (settings.inverted) ColorMatrixColorFilter(PDF_INVERSION) else null
        val first = pageAt((scroll - topPad).coerceAtLeast(0f))
        for (page in first until starts.size) {
            val y = topPad + starts[page] - scroll
            if (y > height) break
            if (y + heights[page] < 0) continue
            val x = left(page)
            paint.color = Color.WHITE
            canvas.drawRect(x, y, x + widths[page], y + heights[page], paint)
            // Keep the previous raster visible while a new zoom level is being rendered.
            cache.snapshot().filterKeys { it.page == page }.forEach { (tile, bitmap) ->
                val sx = widths[page] / tile.pw; val sy = heights[page] / tile.ph
                canvas.drawBitmap(bitmap, null, RectF(x + tile.x * sx, y + tile.y * sy, x + (tile.x + tile.w) * sx, y + (tile.y + tile.h) * sy), paint)
            }
            // Stable half-step raster sizes prevent one cache entry per pinch event.
            val rasterZoom = ceil(zoom * 2) / 2
            val pw = (widths[page] / zoom * rasterZoom).roundToInt().coerceIn(1, 200_000)
            val ph = (heights[page] / zoom * rasterZoom).roundToInt().coerceIn(1, 200_000)
            val sx = widths[page] / pw; val sy = heights[page] / ph
            val fromX = (max(0f, -x) / sx / 512).toInt() * 512
            val fromY = (max(0f, -y) / sy / 512).toInt() * 512
            val untilX = min(pw, ceil((width - x) / sx).toInt())
            val untilY = min(ph, ceil((height - y) / sy).toInt())
            var painted = false
            for (ty in fromY until untilY step 512) for (tx in fromX until untilX step 512) {
                val tile = Tile(page, pw, ph, tx, ty, min(512, pw - tx), min(512, ph - ty))
                needed += tile
                val bitmap = cache.get(tile)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, RectF(x + tx * sx, y + ty * sy, x + (tx + tile.w) * sx, y + (ty + tile.h) * sy), paint)
                    painted = true
                }
            }
            if (!painted) {
                label.color = if (settings.inverted) Color.LTGRAY else Color.DKGRAY
                canvas.drawText(if (page in failed) "페이지 표시 실패 · 다시 시도해 주세요" else "${page + 1} 페이지 불러오는 중", width / 2f, max(32 * density, y + 48 * density), label)
            }
            listOfNotNull(highlight, selection).filter { it.page == page }.forEach { range ->
                shade.color = if (range === selection) 0x665D91DE else 0x66EAB54D
                range.rects.forEach { r -> canvas.drawRect(x + r.left * widths[page], y + r.top * heights[page], x + r.right * widths[page], y + r.bottom * heights[page], shade) }
            }
        }
        visibleTiles = needed.toSet()
        if (ready) {
            // One small preview on either side; visible full-resolution tiles enter the queue first.
            listOf(first - 1, pageAt(scroll + contentHeight) + 1).filter { it in doc.pages.indices }.forEach { page ->
                val size = doc.pages[page]
                val scale = 512f / max(size.width, size.height)
                val w = (size.width * scale).roundToInt().coerceIn(1, 512)
                val h = (size.height * scale).roundToInt().coerceIn(1, 512)
                needed += Tile(page, w, h, 0, 0, w, h)
            }
        }
        jobs.keys.filter { it !in needed }.forEach { jobs.remove(it)?.cancel() }
        needed.filter { cache.get(it) == null && it !in jobs && it.page !in failed }.forEach { tile ->
            jobs[tile] = scope.launch {
                try {
                    val bitmap = doc.render(tile.page, tile.pw, tile.ph, tile.x, tile.y, tile.w, tile.h)
                    ensureActive()
                    cache.put(tile, bitmap); renderedTiles++
                    if (!ready) { ready = true; onReady(); changed() }
                    invalidate()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (failed.add(tile.page)) onFailure(e.message ?: "PDF 페이지를 표시하지 못했습니다."); invalidate() }
                finally { if (jobs[tile] == coroutineContext[Job]) jobs.remove(tile) }
            }
        }
        if (endSignal.take(locator().completed, !touching && scroller.isFinished && !scaleDetector.isInProgress,
                ready && visibleTiles.isNotEmpty() && visibleTiles.all { cache.get(it) != null })) {
            if (!disposed) onEndReached()
        }
    }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scroll = scroller.currY.toFloat(); changed(); postInvalidateOnAnimation() }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touching = false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) endSignal.cancel()
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        val anchor = selecting
        if (anchor != null && event.actionMasked in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val page = anchor.first
            val x = ((event.x - left(page)) / widths[page]).coerceIn(0f, 1f)
            val y = ((event.y + scroll - topPad - starts[page]) / heights[page]).coerceIn(0f, 1f)
            if (event.actionMasked == MotionEvent.ACTION_MOVE) selectionEnd = x to y
            if (selectionEnd != null && (event.eventTime - lastSelection > 100 || event.actionMasked == MotionEvent.ACTION_UP)) {
                lastSelection = event.eventTime; onSelection(page, anchor.second, anchor.third, x, y)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { selecting = null; selectionEnd = null; changed() }
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            endSignal.advance(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scroll = (scroll + contentHeight * if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1).coerceIn(0f, maxScroll()); changed(); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
    fun dispose() {
        if (disposed) return
        disposed = true; scope.cancel(); cache.evictAll(); jobs.clear(); scroller.forceFinished(true)
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); dispose() }
}
