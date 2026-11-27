package com.poyal.yeobaek.image

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import com.poyal.yeobaek.pdf.PDF_INVERSION
import com.poyal.yeobaek.ReadingEndSignal
import com.poyal.yeobaek.ui.ReaderTouchGuard
import com.poyal.yeobaek.image.ImageRaster.Preview
import com.poyal.yeobaek.image.ImageRaster.Tile
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
    // A vertical row is one whole image or one physical half of a spread.
    private var firstRows = IntArray(0)
    private var rowPages = IntArray(0)
    private var rowFirstHalf = 0
    private var spreads = emptyList<List<Int>>()
    private var currentGroup = emptyList<Int>()
    private var focus = 0
    private var segment = -1
    private var pagedHalf = -1
    private val uncropped = CropRect()
    private val autoCrops = mutableMapOf<Int, CropRect>()
    private val cropping = mutableMapOf<Int, Job>()
    private var deferredPages: List<ImagePage>? = null
    private fun splitEnabled() = preferences.splitWide && height > width
    private fun split(index: Int) = splitEnabled() && pages.getOrNull(index)?.wide == true
    private fun firstHalf() = if (preferences.direction == ImageDirection.LTR) 0 else 1
    private fun needsAutoCrop(page: Int) = preferences.cropMode == CropMode.AUTO &&
        page !in autoCrops && preferences.pageCrops[pages[page].key] == null
    private fun crop(index: Int, half: Int): CropRect {
        val base = preferences.pageCrops[pages[index].key] ?: when (preferences.cropMode) {
            CropMode.OFF -> uncropped
            CropMode.MANUAL -> preferences.crop
            CropMode.AUTO -> autoCrops[index] ?: uncropped
        }
        return if (half >= 0) base.half(half == 1) else base
    }

    // In paged mode the drawing slots already are original page indices.
    private fun pageOf(slot: Int) = if (preferences.mode == ImageMode.VERTICAL) rowPages[slot] else slot
    private fun halfOf(slot: Int): Int {
        if (preferences.mode == ImageMode.PAGED) return if (slot == focus) pagedHalf else -1
        val page = rowPages[slot]
        return if (firstRows[page + 1] - firstRows[page] == 1) -1
            else if (slot == firstRows[page]) rowFirstHalf else 1 - rowFirstHalf
    }

    private fun slotOf(page: Int, half: Int): Int {
        if (preferences.mode == ImageMode.PAGED) return page
        val first = firstRows[page]
        return if (firstRows[page + 1] - first == 2 && half == 1 - rowFirstHalf) first + 1 else first
    }

    private var scroll = 0f
    private var pan = 0f
    private var zoom = 1f
    private var pinchResolution: Float? = null
    private var lastPositionReport = 0L
    // Dimensions at 1x exclude page gaps, which stay the same size at every zoom.
    private var baseHeight = 0f
    private var baseGroupWidth = 0f
    private val gap get() = preferences.spacing * density
    private fun pageStart(index: Int) = starts[index] * zoom + index * gap
    private val total get() = baseHeight * zoom + if (preferences.mode == ImageMode.VERTICAL) rowPages.lastIndex * gap else 0f
    private val groupWidth get() = baseGroupWidth * zoom + (currentGroup.size - 1).coerceAtLeast(0) * gap
    private var pending: ImageLocator? = null
    private var unmeasuredPosition: ImageLocator? = null
    private var ready = false
    private var disposed = false
    private val density = resources.displayMetrics.density
    private var topPad = 60f * density
    private var bottomPad = 76f * density
    private val contentHeight get() = (height - topPad - bottomPad).coerceAtLeast(1f)
    private var backgroundColor = Color.LTGRAY
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val inversion = ColorMatrixColorFilter(PDF_INVERSION)
    private val scroller = OverScroller(context)
    private var lastFlingY = 0
    private val cache = ImageTileCache()
    private var queue = linkedSetOf<ImageRaster>()
    private var rendering = false
    private var renderJob: Job? = null
    private var foreground = emptySet<ImageRaster>()
    private var activePrefetch: ImageRaster? = null
    private val failures = mutableMapOf<Int, String>()
    private var reportedFailure: Int? = null
    private var scrollDirection = 1
    private var emptySince = -1L
    private val waitingTimer = Runnable { invalidate() }
    private var waiting = false
    var onWaiting: (Boolean) -> Unit = {}
    private var visible = emptySet<Tile>()
    private var visibleGeometryReady = false
    private var rendered = 0L
    internal data class RenderStatus(val visible: Int, val complete: Int, val cacheBytes: Int, val cacheLimit: Int,
        val queued: Int, val rendered: Long, val failures: Int, val settled: Boolean)
    internal fun renderStatus() = RenderStatus(visible.size, visible.count { cache.get(it) != null }, cache.size(), cache.maxSize(),
        queue.size + if (rendering) 1 else 0, rendered, failures.size,
        visibleGeometryReady && !touching && scroller.isFinished && !scales.isInProgress)
    var onReady: () -> Unit = {}
    var onPosition: (ImageLocator) -> Unit = {}
    var onEndReached: () -> Unit = {}
    private val endSignal = ReadingEndSignal()
    private var touching = false
    var onTap: () -> Unit = {}
    var onFailure: (Int, String) -> Unit = { _, _ -> }
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
                unmeasuredPosition = null
                if (preferences.mode == ImageMode.VERTICAL && abs(dy) > abs(dx)) {
                    endSignal.advance(dy > 0)
                    scrollDirection = if (dy > 0) 1 else -1
                }
                scroll = (scroll + dy).coerceIn(0f, maxScroll())
                val overflow = (if (preferences.mode == ImageMode.VERTICAL && rowPages.isNotEmpty()) widths[pageOf(slotAt(scroll))] * zoom else groupWidth) - width
                if (overflow > 0f) pan = (pan + dx / overflow).coerceIn(0f, 1f)
                changed(reportImmediately = false)
            }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (touchGuard.multiplePointers) return false
            if (scales.isInProgress || abs(vx) > abs(vy)) return false
            lastFlingY = 0
            scroller.fling(0, 0, 0, -vy.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
            postInvalidateOnAnimation()
            return true
        }
    })
    private val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var previousX = 0f
        private var previousY = 0f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            endSignal.cancel()
            pinched = true
            pinchResolution = ceil(zoom * 2) / 2
            previousX = detector.focusX
            previousY = detector.focusY
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleTo((zoom * detector.scaleFactor).coerceIn(1f, 5f), detector.focusX, detector.focusY, previousX, previousY)
            previousX = detector.focusX
            previousY = detector.focusY
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinchResolution = null
            changed()
        }
    })
    init { scales.isQuickScaleEnabled = false; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun open(value: ImageSource, initial: ImageLocator) {
        endSignal.cancel()
        source = value; pages = value.pages.value; pending = initial.constrained(pages); zoom = pending!!.zoom; focus = pending!!.page
        layoutPages(); restore(); invalidate()
        scope.launch {
            value.pages.collect { updated ->
                if (updated !== pages) {
                    deferredPages = updated
                    applyDeferred()
                }
            }
        }
    }
    fun configure(value: ImagePreferences, background: Int) {
        if (preferences == value && background == backgroundColor) return
        val current = locator()
        val position = if (value.splitWide && !preferences.splitWide && current.page == focus && segment >= 0)
            current.copy(segment = segment) else current
        val relayout = preferences.copy(inverted = value.inverted, canvas = value.canvas, doubleTapZoom = value.doubleTapZoom, pinchZoom = value.pinchZoom, tapToTurn = value.tapToTurn, swipeToTurn = value.swipeToTurn, menuVisibility = value.menuVisibility) != value
        preferences = value; backgroundColor = background
        if (relayout) { pending = position; layoutPages(); restore(); changed() }
        invalidate()
    }
    fun setContentInsets(top: Int, bottom: Int) {
        if (topPad == top.toFloat() && bottomPad == bottom.toFloat()) return
        pending = locator(); topPad = top.toFloat(); bottomPad = bottom.toFloat(); layoutPages(); restore(); changed()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (pending == null) pending = locator()
        layoutPages(); restore(); changed()
    }
    private fun double() = preferences.mode == ImageMode.PAGED && (preferences.spread == ImageSpread.DOUBLE ||
        preferences.spread == ImageSpread.LANDSCAPE && width > height)
    private fun group() = currentGroup
    private fun layoutPages() {
        visible = emptySet()
        if (width <= 0 || height <= 0 || pages.isEmpty()) return
        focus = (pending?.constrained(pages)?.page ?: focus).coerceIn(pages.indices)
        segment = if (split(focus)) pending?.let { if (it.segment >= 0) it.segment else if (it.x > 0f) (if (it.x >= .5f) 1 else 0) else firstHalf() } ?: segment.takeIf { it >= 0 } ?: firstHalf()
            else if (pages[focus].measured && !pages[focus].wide) -1
            else pending?.segment ?: segment
        if (widths.size != pages.size) {
            widths = FloatArray(pages.size)
            heights = FloatArray(pages.size)
        }
        if (preferences.mode == ImageMode.VERTICAL) {
            rowFirstHalf = firstHalf()
            if (firstRows.size != pages.size + 1) firstRows = IntArray(pages.size + 1)
            for (page in pages.indices) firstRows[page + 1] = firstRows[page] + if (split(page)) 2 else 1
            if (rowPages.size != firstRows.last()) {
                rowPages = IntArray(firstRows.last())
                starts = FloatArray(rowPages.size)
            }
            var y = 0f
            pages.forEachIndexed { i, size ->
                val c = crop(i, if (split(i)) rowFirstHalf else -1)
                val ratio = size.height * c.height / (size.width * c.width)
                val available = width.toFloat()
                val base = if (preferences.fit == ImageFit.PAGE) min(available, contentHeight / ratio) else available
                widths[i] = base; heights[i] = base * ratio
                for (row in firstRows[i] until firstRows[i + 1]) {
                    rowPages[row] = i
                    starts[row] = y
                    y += heights[i]
                }
            }
            baseHeight = y
        } else {
            spreads = imageSpreads(pages, double(), preferences.coverAlone)
            pagedHalf = if (split(focus)) segment else -1
            val group = if (split(focus)) listOf(focus) else spreads.first { focus in it }
            currentGroup = group
            val available = (width - gap * (group.size - 1)) / group.size
            group.forEach { i ->
                val c = crop(i, halfOf(i))
                val ratio = pages[i].height * c.height / (pages[i].width * c.width)
                val base = if (preferences.fit == ImageFit.PAGE) min(available, contentHeight / ratio) else available
                widths[i] = base; heights[i] = base * ratio
            }
            baseHeight = group.maxOf { heights[it] }
            baseGroupWidth = group.sumOf { widths[it].toDouble() }.toFloat()
        }
    }
    private fun maxScroll() = if (preferences.mode == ImageMode.VERTICAL && starts.isNotEmpty()) max((total - contentHeight).coerceAtLeast(0f), pageStart(starts.lastIndex))
        else (total - contentHeight).coerceAtLeast(0f)
    private fun slotAt(y: Float): Int {
        if (starts.isEmpty()) return focus
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (pageStart(mid) <= y) low = mid else high = mid - 1
        }
        return low
    }
    private fun rect(slot: Int): RectF {
        val index = pageOf(slot)
        val w = widths[index] * zoom; val h = heights[index] * zoom
        if (preferences.mode == ImageMode.VERTICAL) {
            val left = if (w <= width) (width - w) / 2 else -pan * (w - width)
            val top = topPad + pageStart(slot) - scroll
            return RectF(left, top, left + w, top + h)
        }
        val group = group().let { if (preferences.direction == ImageDirection.RTL) it.reversed() else it }
        var left = if (groupWidth <= width) (width - groupWidth) / 2 else -pan * (groupWidth - width)
        for (i in group) { if (i == index) break; left += widths[i] * zoom + gap }
        val top = topPad + (if (total <= contentHeight) (contentHeight - total) / 2 else -scroll) + (total - h) / 2
        return RectF(left, top, left + w, top + h)
    }
    private fun restore() {
        val p = pending?.constrained(pages) ?: return
        if (widths.isEmpty() || widths[p.page] <= 0) return
        focus = p.page
        val slot = slotOf(p.page, segment)
        val c = crop(p.page, halfOf(slot))
        val localX = ((p.x - c.left) / c.width).coerceIn(0f, 1f)
        val localY = ((p.y - c.top) / c.height).coerceIn(0f, 1f)
        if (preferences.mode == ImageMode.VERTICAL) {
            scroll = if (p.completed) maxScroll() else (pageStart(slot) + heights[p.page] * zoom * localY).coerceIn(0f, maxScroll())
            val w = widths[p.page] * zoom
            pan = if (w > width) (localX * w / (w - width)).coerceIn(0f, 1f) else 0f
        } else {
            scroll = ((total - heights[focus] * zoom) / 2 + heights[focus] * zoom * localY).coerceIn(0f, maxScroll())
            pan = 0f
            if (groupWidth > width) pan = ((rect(focus).left + widths[focus] * zoom * localX) / (groupWidth - width)).coerceIn(0f, 1f)
        }
        unmeasuredPosition = p.takeUnless { pages[it.page].measured }
        pending = null
    }
    fun locator(): ImageLocator {
        pending?.let { return it }
        unmeasuredPosition?.let { return it }
        if (widths.isEmpty() || pages.isEmpty()) return ImageLocator()
        val slot = if (preferences.mode == ImageMode.VERTICAL) slotAt(scroll) else focus
        val page = pageOf(slot)
        val half = halfOf(slot)
        val r = rect(slot)
        val c = crop(page, half)
        val savedHalf = if (half < 0 && page == focus && !pages[page].measured) segment else half
        return ImageLocator(page, pages[page].key, c.left + (-r.left / r.width()).coerceIn(0f, 1f) * c.width,
            c.top + ((topPad - r.top) / r.height()).coerceIn(0f, 1f) * c.height, zoom,
            if (preferences.mode == ImageMode.VERTICAL) scroll >= maxScroll() - 1 && (pages[page].measured || !splitEnabled())
            else group().last() == pages.lastIndex && scroll >= maxScroll() - 1 && (!split(page) || segment != firstHalf()), savedHalf)
    }
    fun move(value: ImageLocator, announceEnd: Boolean = true) {
        if (pages.isEmpty()) return
        endSignal.advance(announceEnd)
        scroller.forceFinished(true); pending = value.constrained(pages); zoom = pending!!.zoom; focus = pending!!.page
        layoutPages(); restore(); changed()
    }
    fun turn(delta: Int) {
        if (pages.isEmpty()) return
        scrollDirection = if (delta > 0) 1 else -1
        if (preferences.mode == ImageMode.VERTICAL) {
            unmeasuredPosition = null
            endSignal.advance(delta > 0)
            scroll = (scroll + contentHeight * delta).coerceIn(0f, maxScroll()); changed()
        } else {
            if (split(focus) && ((delta > 0 && segment == firstHalf()) || (delta < 0 && segment != firstHalf()))) {
                move(ImageLocator(page = focus, segment = 1 - segment), announceEnd = false)
                return
            }
            val current = spreads.indexOfFirst { focus in it }
            val target = (current + delta).coerceIn(spreads.indices)
            if (target != current) {
                // Preserve the entry side even when the destination's dimensions arrive after the turn.
                val entryHalf = if (delta < 0 && splitEnabled()) 1 - firstHalf() else -1
                move(ImageLocator(page = spreads[target].first(), segment = entryHalf), announceEnd = false)
            } else { endSignal.advance(delta > 0); changed() }
        }
    }
    fun resetZoom() { scroller.forceFinished(true); scaleTo(1f, width / 2f, topPad + contentHeight / 2f) }
    fun retry() { failures.clear(); reportedFailure = null; invalidate() }
    private fun changed(reportImmediately: Boolean = true) {
        visible = emptySet()
        invalidate()
        val now = SystemClock.uptimeMillis()
        if (!reportImmediately && now - lastPositionReport < 100) return
        lastPositionReport = now
        val position = locator()
        if (ready && pending == null) onPosition(position)
        contentDescription = "이미지 ${position.page + 1} / ${pages.size} 페이지${if (position.segment >= 0) if (position.segment == 0) " · 왼쪽" else " · 오른쪽" else ""} · ${(zoom * 100).toInt()}%"
    }
    private fun hit(x: Float, y: Float): Triple<Int, Float, Float>? {
        val candidates = if (preferences.mode == ImageMode.VERTICAL) listOf(slotAt(scroll + y - topPad)) else group()
        candidates.forEach { slot ->
            val r = rect(slot)
            if (r.contains(x, y)) return Triple(slot, (x - r.left) / r.width(), (y - r.top) / r.height())
        }
        return null
    }
    private fun scaleTo(value: Float, x: Float, y: Float, fromX: Float = x, fromY: Float = y) {
        endSignal.cancel()
        if (widths.isEmpty()) return
        if (value == zoom && fromX == x && fromY == y) return
        unmeasuredPosition = null
        val point = hit(fromX, fromY) ?: Triple(if (preferences.mode == ImageMode.VERTICAL) slotAt(scroll) else focus, .5f, 0f)
        focus = pageOf(point.first); zoom = value
        pan = 0f; scroll = 0f
        val r = rect(point.first)
        scroll = (r.top + point.third * r.height() - y).coerceIn(0f, maxScroll())
        val overflow = (if (preferences.mode == ImageMode.VERTICAL) r.width() else groupWidth) - width
        if (overflow > 0) pan = ((r.left + point.second * r.width() - x) / overflow).coerceIn(0f, 1f)
        changed(reportImmediately = pinchResolution == null)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        val doc = source ?: return
        if (disposed || widths.isEmpty()) return
        val indices: Iterable<Int> = if (preferences.mode == ImageMode.PAGED) group() else
            slotAt(scroll - topPad)..slotAt(scroll + height - topPad)
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val needed = linkedSetOf<Tile>()
        val requests = linkedSetOf<ImageRaster>()
        var first = -1
        var last = -1
        var painted = false
        var geometryReady = true
        var failedPage: Int? = null
        val resolution = pinchResolution ?: (ceil(zoom * 2) / 2)
        paint.color = Color.WHITE
        paint.colorFilter = if (preferences.inverted) inversion else null

        for (slot in indices) {
            val page = pageOf(slot)
            val area = rect(slot)
            val viewport = RectF(area)
            if (!viewport.intersect(screen)) continue
            if (first < 0) first = page
            last = page
            val c = crop(page, halfOf(slot))
            val full = fullImageRect(area, c)
            val needsCrop = needsAutoCrop(page)
            val measured = pages[page].measured
            if (!measured || needsCrop) geometryReady = false
            val exact = if (measured) viewportTiles(page, full, viewport, c, resolution) else emptyList()
            val save = canvas.save()
            canvas.clipRect(viewport)
            canvas.drawRect(viewport, paint)
            val hasPixels = cache.draw(canvas, page, full, viewport, exact, paint)
            canvas.restoreToCount(save)
            painted = painted || hasPixels
            if (page in failures) {
                if (failedPage == null || page == locator().page) failedPage = page
                continue
            }
            val preview = Preview(page)
            if (!hasPixels || !measured || needsCrop || (pinchResolution != null && exact.any { cache.get(it) == null })) {
                val bitmap = cache.get(preview)
                if (bitmap == null) requests.add(preview)
                else if (needsCrop) prepareCrop(page, bitmap)
            }
            if (pinchResolution == null) needed.addAll(exact)
        }

        if (painted && !ready && pending == null && locator().page.let { pages[it].measured && !needsAutoCrop(it) }) {
            ready = true
            onReady()
            changed()
        }
        updateWaiting(first >= 0 && !painted && failedPage == null)
        if (failedPage != null && failedPage != reportedFailure) {
            reportedFailure = failedPage
            onFailure(failedPage, failures.getValue(failedPage))
        } else if (failedPage == null) reportedFailure = null
        visible = needed
        visibleGeometryReady = geometryReady && failedPage == null
        val complete = geometryReady && needed.isNotEmpty() && needed.all { cache.get(it) != null }
        for (tile in needed) if (cache.get(tile) == null) requests.add(tile)
        val prefetch = linkedSetOf<ImageRaster>()
        if (complete && failedPage == null) {
            if (preferences.mode == ImageMode.VERTICAL) {
                val visibleBytes = needed.sumOf { it.w.toLong() * it.h * 4 }
                verticalPrefetch(resolution, cache.maxSize() - visibleBytes, prefetch)
            } else {
                val adjacent = if (scrollDirection > 0) last + 1 else first - 1
                if (adjacent in pages.indices && adjacent !in failures && cache.get(Preview(adjacent)) == null)
                    prefetch.add(Preview(adjacent))
            }
        }
        schedule(doc, requests, prefetch)
        if (endSignal.take(locator().completed, !touching && scroller.isFinished && !scales.isInProgress,
                ready && complete && failedPage == null)) onEndReached()
    }

    private fun fullImageRect(area: RectF, crop: CropRect): RectF {
        val w = area.width() / crop.width
        val h = area.height() / crop.height
        return RectF(area.left - crop.left * w, area.top - crop.top * h,
            area.left + (1 - crop.left) * w, area.top + (1 - crop.top) * h)
    }

    private fun verticalPrefetch(resolution: Float, spareBytes: Long, requests: MutableSet<ImageRaster>) {
        val ahead = min(height / 2f, 512f)
        val band = if (scrollDirection > 0) RectF(0f, height.toFloat(), width.toFloat(), height + ahead)
            else RectF(0f, -ahead, width.toFloat(), 0f)
        val rows = slotAt(scroll + band.top - topPad)..slotAt(scroll + band.bottom - topPad)
        var remaining = spareBytes
        for (slot in if (scrollDirection > 0) rows else rows.reversed()) {
            val page = pageOf(slot)
            val area = rect(slot)
            val viewport = RectF(area)
            if (page in failures || !viewport.intersect(band)) continue
            if (!pages[page].measured || needsAutoCrop(page)) {
                val preview = Preview(page)
                val bitmap = cache.get(preview)
                if (bitmap == null && remaining >= 192 * 192 * 4) {
                    requests.add(preview)
                    remaining -= 192 * 192 * 4
                } else if (bitmap != null && needsAutoCrop(page)) prepareCrop(page, bitmap)
                continue
            }
            val crop = crop(page, halfOf(slot))
            val tiles = viewportTiles(page, fullImageRect(area, crop), viewport, crop, resolution)
            for (tile in if (scrollDirection > 0) tiles else tiles.asReversed()) {
                if (tile in visible) continue
                remaining -= tile.w.toLong() * tile.h * 4
                if (remaining < 0) return
                if (cache.get(tile) == null) requests.add(tile)
            }
        }
    }

    private fun viewportTiles(page: Int, full: RectF, viewport: RectF, crop: CropRect, resolution: Float): List<Tile> {
        // Once native pixels are available, further magnification is a canvas transform.
        val image = pages[page]
        val pw = (widths[page] / crop.width * resolution).roundToInt().coerceIn(1, image.width)
        val ph = (heights[page] / crop.height * resolution).roundToInt().coerceIn(1, image.height)
        val sx = pw / full.width()
        val sy = ph / full.height()
        val left = ((viewport.left - full.left) * sx).toInt().coerceAtLeast(0) / 512 * 512
        val top = ((viewport.top - full.top) * sy).toInt().coerceAtLeast(0) / 512 * 512
        val right = ceil((viewport.right - full.left) * sx).toInt().coerceAtMost(pw)
        val bottom = ceil((viewport.bottom - full.top) * sy).toInt().coerceAtMost(ph)
        return buildList {
            for (y in top until bottom step 512) for (x in left until right step 512)
                add(Tile(page, pw, ph, x, y, min(512, pw - x), min(512, ph - y)))
        }
    }

    // The worker itself is serial. Only the current request is committed; each frame
    // replaces the remaining queue so a fling cannot enqueue a book's worth of IPC.
    private fun schedule(doc: ImageSource, requests: LinkedHashSet<ImageRaster>, prefetch: Set<ImageRaster>) {
        val background = activePrefetch
        if (background != null && requests.isNotEmpty() && background !in requests) renderJob?.cancel()
        foreground = requests
        queue = LinkedHashSet(requests)
        queue.addAll(prefetch)
        if (rendering || queue.isEmpty()) return
        rendering = true
        renderJob = scope.launch {
            try {
                while (queue.isNotEmpty()) {
                    val key = queue.first()
                    queue.remove(key)
                    if (key.page in failures || cache.get(key) != null) continue
                    activePrefetch = key.takeUnless { it in foreground }
                    try {
                        val bitmap = when (key) {
                            is Preview -> doc.thumbnail(key.page)
                            is Tile -> doc.render(key.page, key.pw, key.ph, key.x, key.y, key.w, key.h)
                        }
                        cache.store(key, bitmap)
                        rendered++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures[key.page] = e.message ?: "이미지를 읽지 못했습니다." }
                    // A completed offscreen tile is already usable on the next scroll frame.
                    // Only metadata/previews or a visible result need to redraw the viewport.
                    if (key is Preview || activePrefetch == null || key in foreground) postInvalidateOnAnimation()
                }
            } finally {
                activePrefetch = null
                rendering = false
                if (!disposed && queue.isNotEmpty()) postInvalidateOnAnimation()
            }
        }
    }

    private fun updateWaiting(empty: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (empty && emptySince < 0) {
            emptySince = now
            postDelayed(waitingTimer, 500)
        } else if (!empty && emptySince >= 0) {
            emptySince = -1L
            removeCallbacks(waitingTimer)
        }
        val show = empty && now - emptySince >= 500
        if (waiting != show) {
            waiting = show
            onWaiting(show)
        }
    }

    private val deferredCrops = mutableMapOf<Int, CropRect>()
    private fun prepareCrop(page: Int, bitmap: Bitmap) {
        if (page in cropping || page in deferredCrops) return
        cropping[page] = scope.launch {
            try {
                deferredCrops[page] = withContext(Dispatchers.Default) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    detectImageCrop(pixels, bitmap.width, bitmap.height)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { deferredCrops[page] = CropRect() }
            finally { cropping.remove(page) }
            applyDeferred()
            invalidate()
        }
    }

    private fun applyDeferred() {
        val idle = !touching && scroller.isFinished && !scales.isInProgress
        if (!idle && (preferences.mode != ImageMode.VERTICAL || scales.isInProgress)) return
        if (deferredPages == null && (!idle || deferredCrops.isEmpty())) return
        val position = locator()
        deferredPages?.let { pages = it }
        deferredPages = null
        if (idle) {
            autoCrops.putAll(deferredCrops)
            deferredCrops.clear()
        }
        pending = position
        layoutPages()
        restore()
        changed(reportImmediately = idle)
    }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            // Metadata can insert rows during a fling. Continue from the restored image anchor.
            val next = scroll + (scroller.currY - lastFlingY)
            if (next != scroll) unmeasuredPosition = null
            lastFlingY = scroller.currY
            scroll = next.coerceIn(0f, maxScroll())
            if (scroll != next) scroller.forceFinished(true)
            changed(reportImmediately = scroller.isFinished)
            postInvalidateOnAnimation()
        }
        applyDeferred()
    }
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
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) changed()
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
    fun dispose() { if (!disposed) { touchGuard.clear(); doubleTapPending = false; disposed = true; removeCallbacks(waitingTimer); scope.cancel(); cache.evictAll(); scroller.forceFinished(true) } }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); dispose() }
}
