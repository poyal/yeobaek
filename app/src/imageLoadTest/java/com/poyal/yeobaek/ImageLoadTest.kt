package com.poyal.yeobaek

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.image.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in: -PimageLoadTest. Host runner supplies actual large fixtures, never APK padding. */
class ImageLoadTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeImagesRemainBoundedAcrossModesAndReopens(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val name = InstrumentationRegistry.getArguments().getString("imageFixture") ?: "dimensions.zip"
        val input = File(context.filesDir, "image-load/$name")
        // Same non-backed-up storage class as production: Android may evict cacheDir mid-read.
        val cache = File(context.noBackupFilesDir, "image-load-cache")
        cache.deleteRecursively()
        val store = ImageStore(cache)
        val folders = FolderSource(context.contentResolver)
        val folderCount = name.removePrefix("folder-").toIntOrNull()
        val folder = File(context.filesDir, "image-load-folder")
        val entries = if (folderCount != null) {
            folder.mkdirs()
            val bitmap = android.graphics.Bitmap.createBitmap(120, 160, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val output = java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output); bitmap.recycle()
            val bytes = output.toByteArray()
            (1..folderCount).map { index ->
                val file = File(folder, "$index.jpg"); file.writeBytes(bytes)
                DocumentEntry("$index", Uri.fromFile(file).toString(), file.name, false, file.length(), file.lastModified(), "image/jpeg")
            }
        } else emptyList()
        var shown by mutableStateOf<ImageSource?>(null)
        var currentView: ImageDocumentView? = null
        var ready = false
        var version: String? = null
        var latest = ImageLocator()
        val report = JSONObject().put("fixture", name).put("bytes", if (folderCount == null) input.length() else entries.sumOf { it.size ?: 0 })
        val cycles = JSONArray(); val reopens = JSONArray()
        compose.setContent {
            val source = shown
            if (source != null) key(source) {
                AndroidView(factory = { ctx -> ImageDocumentView(ctx).also {
                    currentView = it; it.onReady = { ready = true }; it.onPosition = { p -> latest = p }
                    it.open(source, latest)
                } }, modifier = Modifier.fillMaxSize(), onRelease = { it.dispose() })
            }
        }
        suspend fun open(): Pair<ImageSource, Long> {
            val start = SystemClock.elapsedRealtime()
            val source = if (folderCount != null) ImageSource.folder(context, store, entries, folders)
                else ImageSource.archive(context, store, { input.inputStream() }, version)
            version = source.version
            compose.runOnIdle { ready = false; shown = source }
            compose.waitUntil(180_000) { ready }
            return source to (SystemClock.elapsedRealtime() - start)
        }
        fun settled() {
            compose.waitUntil(90_000) { currentView?.renderStatus()?.let { it.visible > 0 && it.complete == it.visible && it.settled } == true }
        }
        fun sample(): JSONObject {
            var row = JSONObject()
            compose.runOnIdle {
                val view = requireNotNull(currentView); val status = view.renderStatus(); val p = view.locator()
                assertTrue(status.cacheBytes <= status.cacheLimit); assertEquals(0, status.failures)
                row = JSONObject().put("page", p.page).put("zoom", p.zoom).put("y", p.y).put("cache_bytes", status.cacheBytes)
                    .put("cache_limit", status.cacheLimit).put("pss_kib", Debug.getPss()).put("fd_count", File("/proc/self/fd").list()?.size ?: 0)
            }
            return row
        }
        var active: ImageSource? = null
        try {
            val first = open(); active = first.first
            settled(); report.put("first_display_ms", first.second).put("pages", first.first.pages.value.size)
            val count = first.first.pages.value.size
            repeat(30) { iteration ->
                val page = when (iteration % 5) { 0 -> count - 1; 1 -> 0; else -> (iteration * 7919) % count }
                val start = SystemClock.elapsedRealtime()
                compose.runOnIdle {
                    currentView!!.configure(ImagePreferences(mode = if (iteration % 2 == 0) ImageMode.VERTICAL else ImageMode.PAGED,
                        spread = if (iteration % 3 == 0) ImageSpread.DOUBLE else ImageSpread.SINGLE,
                        direction = if (iteration % 3 == 1) ImageDirection.RTL else ImageDirection.LTR,
                        inverted = iteration % 2 == 0, spacing = (iteration % 3 * 8).toFloat()), android.graphics.Color.DKGRAY)
                    currentView!!.move(ImageLocator(page = page, y = .3f, zoom = if (iteration % 4 == 0) 3f else 1f))
                }
                settled(); cycles.put(sample().put("iteration", iteration).put("display_ms", SystemClock.elapsedRealtime() - start))
            }
            repeat(10) {
                val prior = latest
                compose.runOnIdle { shown = null }; compose.waitForIdle(); active?.close()
                val next = open(); active = next.first; settled()
                compose.runOnIdle { assertEquals(prior.key, currentView!!.locator().key) }
                reopens.put(sample().put("open_ms", next.second))
            }
            report.put("cycles", cycles).put("reopens", reopens).put("passed", true)
        } finally {
            compose.runOnIdle { shown = null }; compose.waitForIdle(); active?.close()
            val output = File(context.filesDir, "image-load-results").apply { mkdirs() }
            File(output, "$name.json").writeText(report.toString(2))
            cache.deleteRecursively(); folder.deleteRecursively()
        }
    }
}
