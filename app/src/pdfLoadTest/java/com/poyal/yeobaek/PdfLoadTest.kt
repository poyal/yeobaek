package com.poyal.yeobaek

import android.graphics.Color
import android.os.Debug
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Opt-in real-byte-size stress suite; fixtures live outside both production and test APKs. */
class PdfLoadTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val events = JSONArray()
    private lateinit var output: File
    private val failures = AtomicReference<String?>(null)
    private fun event(kind: String, vararg fields: Pair<String, Any>) {
        val row = JSONObject().put("phase", kind).put("uptime_ms", SystemClock.elapsedRealtime())
        fields.forEach { (key, value) -> row.put(key, value) }; events.put(row)
        output.writeText(events.toString(2))
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }
    private fun memory(): JSONObject {
        val rendererPid = shell("pidof ${context.packageName}:pdf:com.poyal.yeobaek.pdf.PdfRenderService ${context.packageName}:pdf")
            .trim().split(Regex("\\s+")).first().toIntOrNull() ?: error("Renderer process is missing")
        val native = shell("dumpsys meminfo -s $rendererPid")
        val nativePss = Regex("TOTAL PSS:\\s+(\\d+)").find(native)?.groupValues?.get(1)?.toLong()
            ?: Regex("TOTAL:\\s+(\\d+)").find(native)?.groupValues?.get(1)?.toLong()
            ?: error("Renderer process is missing: $native")
        val main = Debug.getPss()
        assertTrue("App PSS ${main}KiB exceeds 384MiB", main <= 384 * 1024)
        assertTrue("Renderer PSS ${nativePss}KiB exceeds 512MiB", nativePss <= 512 * 1024)
        assertTrue("Combined PSS exceeds 768MiB", main + nativePss <= 768 * 1024)
        return JSONObject().put("app_pss_kib", main).put("renderer_pss_kib", nativePss)
            .put("java_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
            .put("app_fds", File("/proc/self/fd").list()?.size ?: -1)
    }
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun waitForViewport(view: PdfDocumentView) {
        compose.waitUntil(30_000) {
            failures.get()?.let { error(it) }
            var complete = false
            instrumentation.runOnMainSync {
                val status = view.renderStatus()
                assertTrue("Bitmap cache exceeded its budget", status.cacheBytes <= status.cacheLimit)
                assertEquals("Tile rendering failed", 0, status.failures)
                complete = status.visible > 0 && status.visible == status.complete && status.settled
            }
            complete
        }
    }
    @Test fun mixed100MiB() = exercise("100MiB-mixed.pdf")
    @Test fun scanned300MiB() = exercise("300MiB-scan.pdf")

    private fun exercise(name: String) = runBlocking {
        val source = File(context.filesDir, "pdf-load/$name")
        val manifest = JSONObject(File(context.filesDir, "pdf-load/manifest.json").readText()).getJSONArray("fixtures")
        val fixture = (0 until manifest.length()).map { manifest.getJSONObject(it) }.single { it.getString("file") == name }
        assertEquals(fixture.getLong("bytes"), source.length())
        assertTrue(source.length() >= fixture.getLong("target_mib") * 1024 * 1024)
        assertTrue("Fixture must consist of used image streams", fixture.getLong("image_stream_bytes") > source.length() * .99)
        output = File(context.filesDir, "pdf-load-results/$name.json").apply { parentFile!!.mkdirs() }
        val root = File(context.cacheDir, "pdf-load-$name").apply { deleteRecursively(); mkdirs() }
        val store = PdfStore(root)
        val engine = PdfEngine(context)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PdfReaderController(scope, db.library(), root) { failures.set(it) }
        var doc: PdfDocumentHandle? = null
        var view: PdfDocumentView? = null
        var display by mutableStateOf(true)
        val cycleMemory = mutableListOf<JSONObject>()
        val viewportTimes = mutableListOf<Long>()
        val reopenTimes = mutableListOf<Long>()
        try {
            event("start", "fixture" to fixture, "api" to android.os.Build.VERSION.SDK_INT,
                "heap_limit_bytes" to Runtime.getRuntime().maxMemory(), "cycles" to 30, "reopens" to 10)
            // Real source-copy/hash path, not direct engine-open timings alone. Empty app cache each trial.
            var prepared = source
            repeat(3) { trial ->
                store.clear()
                val begin = SystemClock.elapsedRealtime()
                prepared = withTimeout(60_000) { store.prepare { source.inputStream() } }
                val copied = SystemClock.elapsedRealtime()
                assertEquals(fixture.getString("sha256"), prepared.nameWithoutExtension)
                val opened = engine.open(prepared)
                try {
                    assertEquals(fixture.getInt("pages"), opened.pages.size)
                    val parsed = SystemClock.elapsedRealtime()
                    opened.render(0, 720, 960, 0, 0, 512, 512).recycle()
                    val painted = SystemClock.elapsedRealtime()
                    assertTrue("Open plus first tile exceeded 60s", painted - begin < 60_000)
                    event("open_trial", "trial" to trial, "prepare_ms" to copied - begin,
                        "metadata_ms" to parsed - copied, "first_tile_ms" to painted - parsed,
                        "total_ms" to painted - begin, "memory" to memory())
                } finally { opened.close() }
            }
            // Cancellation must clean partial copies, even when source bytes exceed the Java heap.
            val cancelRoot = File(root, "cancel")
            val cancelStore = PdfStore(cancelRoot)
            val started = CompletableDeferred<Unit>()
            val copying = launch { cancelStore.prepare { object : FilterInputStream(source.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { started.complete(Unit) }
            } } }
            started.await(); copying.cancelAndJoin()
            assertTrue(cancelRoot.walkTopDown().none { it.isFile })
            event("copy_cancelled", "partial_files" to 0)

            val current = engine.open(prepared); doc = current
            val book = Book("pdf-load", "pdf-load", source.toURI().toString(), name, "test", version = current.version,
                sourceHash = current.version, format = "PDF", pageCount = current.pages.size)
            withContext(Dispatchers.Main) { controller.show(OpenPdf(book, current, PdfLocator())) }
            val firstViewport = SystemClock.elapsedRealtime()
            compose.setContent {
                if (display) AndroidView(factory = { ctx -> PdfDocumentView(ctx).also { native ->
                    view = native; native.onReady = controller::ready; native.onPosition = controller::position
                    native.onFailure = { failures.set(it) }; native.open(current, PdfLocator())
                } }, onRelease = { it.dispose() }, modifier = Modifier.fillMaxSize().testTag("pdf_load_view"))
            }
            compose.waitUntil(10_000) { view != null }
            val native = view!!
            waitForViewport(native)
            event("first_viewport", "ms" to SystemClock.elapsedRealtime() - firstViewport, "memory" to memory())
            val searchBegin = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { controller.startSearch("LOADTOKEN") }
            // UI test gestures block this test thread; observe completion on a separate dispatcher.
            val searchCompletion = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                withTimeout(60_000) { controller.search.first { !it.running } }
                SystemClock.elapsedRealtime() - searchBegin
            }
            repeat(30) { cycle ->
                val page = cycle * 7 % current.pages.size
                val begin = SystemClock.elapsedRealtime()
                compose.runOnIdle {
                    native.configure(PdfPreferences(inverted = cycle % 2 == 0), if (cycle % 2 == 0) Color.BLACK else Color.LTGRAY)
                    native.move(PdfLocator(page, y = .1f, zoom = listOf(1f, 2f, 5f)[cycle % 3]))
                }
                waitForViewport(native)
                compose.onNodeWithTag("pdf_load_view").performTouchInput { advanceEventTime(500); swipeUp(durationMillis = 300) }
                waitForViewport(native)
                if (cycle % 5 == 0) {
                    compose.onNodeWithTag("pdf_load_view").performTouchInput {
                        advanceEventTime(500)
                        pinch(start0 = center * .7f, end0 = center * .3f, start1 = center * 1.3f, end1 = center * 1.7f)
                    }
                    waitForViewport(native)
                }
                if (cycle % 5 == 1) {
                    compose.onNodeWithTag("pdf_load_view").performTouchInput { advanceEventTime(500); doubleClick(center) }
                    waitForViewport(native)
                }
                val elapsed = SystemClock.elapsedRealtime() - begin
                viewportTimes += elapsed
                val sample = memory(); cycleMemory += sample
                var stats: PdfDocumentView.RenderStatus? = null
                compose.runOnIdle { stats = native.renderStatus() }
                event("cycle", "cycle" to cycle, "page" to page, "ms" to elapsed, "memory" to sample,
                    "cache_bytes" to stats!!.cacheBytes, "cache_limit_bytes" to stats!!.cacheLimit, "rendered_tiles" to stats!!.rendered)
            }
            val searchMs = searchCompletion.await()
            val search = controller.search.value
            assertNull(search.error)
            assertEquals(if (fixture.getBoolean("searchable")) current.pages.size.toLong() else 0L, search.count)
            assertEquals(!fixture.getBoolean("searchable"), search.noText)
            event("search_complete", "ms" to searchMs, "matches" to search.count, "scanned_pages" to search.scanned, "no_text" to search.noText)

            compose.runOnIdle { native.move(PdfLocator(current.pages.lastIndex, .15f, .25f, 2f)) }
            waitForViewport(native)
            withContext(Dispatchers.Main) { controller.flush() }
            val saved = db.library().pdfPosition(book.id, book.version) ?: error("Reading position missing")
            assertEquals(current.pages.lastIndex, saved.page); assertEquals(2f, saved.zoom, .01f)
            compose.runOnIdle { display = false }; compose.waitForIdle()
            withContext(Dispatchers.Main) { controller.close() }; doc = null
            repeat(10) { iteration ->
                val begin = SystemClock.elapsedRealtime()
                val reopened = engine.open(prepared)
                try {
                    reopened.render(saved.page, 1440, 1920, 512, 512, 512, 512).recycle()
                    val elapsed = SystemClock.elapsedRealtime() - begin; reopenTimes += elapsed
                    event("reopen", "iteration" to iteration, "ms" to elapsed, "memory" to memory())
                } finally { reopened.close() }
            }
            // Compare a warm cache with the final five cycles, not a cold-start baseline.
            fun median(values: List<Long>) = values.sorted()[values.size / 2]
            for (key in listOf("app_pss_kib", "renderer_pss_kib")) {
                val early = median(cycleMemory.subList(5, 10).map { it.getLong(key) })
                val late = median(cycleMemory.takeLast(5).map { it.getLong(key) })
                assertTrue("Sustained memory growth: $key ${late - early}KiB", late - early <= 96 * 1024)
            }
            assertTrue("File descriptor growth", cycleMemory.last().getInt("app_fds") - cycleMemory[5].getInt("app_fds") <= 24)
            assertEquals(fixture.getString("sha256"), withContext(Dispatchers.IO) { digest(source) })
            fun p95(values: List<Long>) = values.sorted()[kotlin.math.ceil(values.size * .95).toInt() - 1]
            event("pass", "cycles" to viewportTimes.size, "cycle_p95_ms" to p95(viewportTimes),
                "reopens" to reopenTimes.size, "reopen_p95_ms" to p95(reopenTimes), "source_unchanged" to true)
        } catch (e: Throwable) {
            event("fail", "error" to e.toString()); throw e
        } finally {
            compose.runOnIdle { display = false; view?.dispose() }
            withContext(Dispatchers.Main) { controller.close() }
            doc?.close(); withContext(Dispatchers.Main) { engine.dispose() }; scope.cancel(); db.close(); root.deleteRecursively()
        }
    }
}
