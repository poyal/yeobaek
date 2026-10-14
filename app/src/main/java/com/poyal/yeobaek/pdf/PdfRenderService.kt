package com.poyal.yeobaek.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.*
import android.system.OsConstants
import io.legere.pdfiumandroid.*
import kotlin.math.*

/** Native PDF parsing has no app storage/network permissions. Only passed file descriptors are read. */
class PdfRenderService : Service() {
    private val worker = HandlerThread("pdf-native")
    private lateinit var handler: Handler
    private val documents = mutableMapOf<String, PdfDocument>()
    // Keep decoded images alive between tiles without retaining every visited page.
    // All access, eviction and document close run on the same worker thread.
    private val pages = LinkedHashMap<Pair<String, Int>, PdfPage>(2, .75f, true)
    private val core by lazy { PdfiumCore() }
    private fun page(id: String, index: Int): PdfPage {
        val key = id to index
        pages[key]?.let { return it }
        if (pages.size >= 2) pages.remove(pages.keys.first())?.close()
        return requireNotNull(documents.getValue(id).openPage(index)).also { pages[key] = it }
    }
    private fun closeDocument(id: String) {
        pages.keys.filter { it.first == id }.forEach { pages.remove(it)?.close() }
        documents.remove(id)?.close()
    }
    override fun onCreate() {
        super.onCreate(); worker.start()
        handler = Handler(worker.looper) { message -> handle(message); true }
    }
    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder
    override fun onDestroy() {
        handler.post {
            pages.values.forEach { runCatching { it.close() } }; pages.clear()
            documents.values.forEach { runCatching { it.close() } }; documents.clear(); worker.quitSafely()
        }
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    private fun handle(message: Message) {
        val input = message.data
        val result = Bundle()
        var shared: SharedMemory? = null
        try {
            val id = requireNotNull(input.getString("document"))
            when (message.what) {
                PdfProtocol.OPEN -> {
                    val fd = requireNotNull(input.getParcelable<ParcelFileDescriptor>("file"))
                    val doc = try { core.newDocument(fd) } catch (e: Exception) { fd.close(); throw e }
                    val count = doc.getPageCount()
                    if (count !in 1..50_000) { doc.close(); error("표시할 페이지가 없거나 페이지 수가 너무 많습니다.") }
                    closeDocument(id); documents[id] = doc
                    result.putInt("count", count)
                }
                PdfProtocol.CLOSE -> closeDocument(id)
                else -> {
                    val doc = documents[id] ?: error("문서가 닫혔습니다. 다시 열어 주세요.")
                    if (message.what == PdfProtocol.SIZES) {
                        val start = input.getInt("start")
                        val count = min(input.getInt("count").coerceIn(1, 64), doc.getPageCount() - start)
                        require(start >= 0 && count > 0)
                        val sizes = IntArray(count * 2)
                        repeat(count) { index -> doc.openPage(start + index).use { page ->
                            requireNotNull(page)
                            val size = page.getPageSize(72)
                            require(size.width in 1..200_000 && size.height in 1..200_000) { "페이지 크기를 읽을 수 없습니다." }
                            sizes[index * 2] = size.width; sizes[index * 2 + 1] = size.height
                        } }
                        result.putIntArray("sizes", sizes)
                    } else {
                        val page = page(id, input.getInt("page"))
                        if (message.what == PdfProtocol.RENDER) {
                            val width = input.getInt("width").coerceIn(1, 512)
                            val height = input.getInt("height").coerceIn(1, 512)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.renderPageBitmap(bitmap, -input.getInt("left"), -input.getInt("top"),
                                    input.getInt("pageWidth").coerceIn(1, 200_000), input.getInt("pageHeight").coerceIn(1, 200_000),
                                    renderAnnot = true, canvasColor = Color.WHITE, pageBackgroundColor = Color.WHITE)
                                shared = SharedMemory.create("pdf-tile", bitmap.byteCount)
                                val buffer = shared!!.mapReadWrite()
                                try { bitmap.copyPixelsToBuffer(buffer) } finally { SharedMemory.unmap(buffer) }
                                shared!!.setProtect(OsConstants.PROT_READ)
                                result.putParcelable("pixels", shared)
                            } finally { bitmap.recycle() }
                        } else page.openTextPage().use { text ->
                            val total = text.textPageCountChars()
                            result.putInt("textCount", total)
                            when (message.what) {
                                PdfProtocol.SEARCH -> {
                                    val matches = arrayListOf<Bundle>()
                                    text.findStart(input.getString("query").orEmpty(), emptySet(), input.getInt("start")).use { find ->
                                        if (find != null) while (matches.size < 200 && find.findNext()) {
                                            val start = find.getSchResultIndex(); val count = find.getSchCount()
                                            if (count <= 0) break
                                            matches += Bundle().apply {
                                                putInt("start", start); putInt("count", count)
                                                putString("excerpt", text.textPageGetText((start - 30).coerceAtLeast(0), min(120, total - (start - 30).coerceAtLeast(0))))
                                            }
                                        }
                                    }
                                    result.putParcelableArrayList("matches", matches)
                                    result.putInt("next", if (matches.size == 200) matches.last().getInt("start") + 1 else -1)
                                }
                                PdfProtocol.RANGE, PdfProtocol.SELECT -> {
                                    var start = input.getInt("start"); var count = input.getInt("count")
                                    if (message.what == PdfProtocol.SELECT) {
                                        val size = page.getPageSize(72)
                                        fun index(x: Float, y: Float): Int {
                                            val point = page.mapDeviceCoordsToPage(0, 0, size.width * 10, size.height * 10, 0,
                                                (x * size.width * 10).roundToInt(), (y * size.height * 10).roundToInt())
                                            return text.textPageGetCharIndexAtPos(point.x.toDouble(), point.y.toDouble(), 24.0, 24.0)
                                        }
                                        val a = index(input.getFloat("x1"), input.getFloat("y1"))
                                        val b = index(input.getFloat("x2"), input.getFloat("y2"))
                                        start = min(a, b); count = abs(b - a) + 1
                                        if (a < 0 || b < 0) count = 0
                                        else if (a == b) {
                                            var end = a + 1
                                            while (start > 0 && a - start < 256 && !text.textPageGetUnicode(start - 1).isWhitespace()) start--
                                            while (end < total && end - a < 256 && !text.textPageGetUnicode(end).isWhitespace()) end++
                                            count = end - start
                                        }
                                    }
                                    start = start.coerceIn(0, total)
                                    count = count.coerceIn(0, min(32_000, total - start))
                                    result.putInt("start", start); result.putInt("count", count)
                                    result.putString("text", if (count == 0) "" else text.textPageGetText(start, count).orEmpty())
                                    val size = page.getPageSize(72)
                                    val rectCount = if (count == 0) 0 else text.textPageCountRects(start, count).coerceAtMost(4000)
                                    val rects = ArrayList<Float>()
                                    repeat(rectCount) { i -> text.textPageGetRect(i)?.let { rect ->
                                        val mapped = page.mapRectToDevice(0, 0, size.width * 10, size.height * 10, 0, rect)
                                        rects.add(min(mapped.left, mapped.right).toFloat() / (size.width * 10))
                                        rects.add(min(mapped.top, mapped.bottom).toFloat() / (size.height * 10))
                                        rects.add(max(mapped.left, mapped.right).toFloat() / (size.width * 10))
                                        rects.add(max(mapped.top, mapped.bottom).toFloat() / (size.height * 10))
                                    } }
                                    result.putFloatArray("rects", rects.toFloatArray())
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val password = e.javaClass.simpleName.contains("Password", true) || e.message.orEmpty().contains("password", true)
            result.putString("error", if (password) "암호로 잠긴 PDF는 아직 지원하지 않습니다." else "PDF를 처리하지 못했습니다. 파일이 손상되었거나 지원하지 않는 문서일 수 있습니다.")
        }
        try { message.replyTo?.send(Message.obtain(null, message.what, message.arg1, 0).apply { data = result }) }
        catch (_: RemoteException) { }
        finally { shared?.close() }
    }
}
