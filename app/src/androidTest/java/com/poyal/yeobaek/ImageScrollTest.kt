package com.poyal.yeobaek

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.image.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImageScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var directory: File
    private lateinit var source: ImageSource
    private lateinit var view: ImageDocumentView

    private fun open(preferences: ImagePreferences, initial: ImageLocator = ImageLocator()) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "strip-reader-test").apply { mkdirs() }
        val archive = File(directory, "mixed.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            repeat(12) { page ->
                val width = if (page == 1) 720 else 1435
                val image = Bitmap.createBitmap(width, 1092, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                canvas.drawColor(if (page == 1) Color.GREEN else Color.RED)
                val paint = Paint().apply { color = Color.BLUE }
                if (page != 1) canvas.drawRect(width / 2f, 0f, width.toFloat(), 1092f, paint)
                paint.color = Color.BLACK
                for (x in 0 until width step 8) canvas.drawRect(x.toFloat(), 0f, x + 3f, 1092f, paint)
                paint.color = Color.WHITE
                paint.textSize = 24f
                canvas.drawText("왼쪽 ${page + 1}", 24f, 80f, paint)
                if (page != 1) canvas.drawText("오른쪽 ${page + 1}", width / 2f + 24f, 80f, paint)
                zip.putNextEntry(ZipEntry("${page.toString().padStart(2, '0')}.jpg"))
                image.compress(Bitmap.CompressFormat.JPEG, 95, zip)
                image.recycle()
                zip.closeEntry()
            }
        }
        source = runBlocking { ImageSource.archive(context, ImageStore(directory), { archive.inputStream() }) }
        compose.setContent { AndroidView(factory = { ctx -> ImageDocumentView(ctx).also {
            view = it
            it.setContentInsets(0, 0)
            it.configure(preferences, Color.WHITE)
            it.open(source, initial)
        } }, modifier = Modifier.fillMaxSize()) }
        waitForTiles()
    }

    private fun waitForTiles() = compose.waitUntil(15_000) {
        compose.runOnUiThread { ::view.isInitialized && view.renderStatus().let {
            it.settled && it.visible > 0 && it.visible == it.complete
        } }
    }

    // A 64-pixel scan line distinguishes the fine stripes from a stretched thumbnail.
    private fun scan(y: Float): IntArray {
        val bitmap = Bitmap.createBitmap(64, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-view.width * .4f, -view.height * y)
        view.draw(canvas)
        return IntArray(64).also { bitmap.getPixels(it, 0, 64, 0, 0, 64, 1); bitmap.recycle() }
    }

    private fun assertSide(page: Int, half: Int, color: Int, y: Float = .35f) {
        val position = view.locator()
        assertEquals(page, position.page)
        assertEquals(half, position.segment)
        val pixels = scan(y)
        val red = pixels.map(Color::red).average()
        val green = pixels.map(Color::green).average()
        val blue = pixels.map(Color::blue).average()
        // JPEG chroma subsampling changes individual RGB values at the black stripes.
        val matches = when (color) {
            Color.RED -> red > maxOf(green, blue) + 80
            Color.GREEN -> green > maxOf(red, blue) + 80
            Color.BLUE -> blue > maxOf(red, green) + 80
            else -> false
        }
        assertTrue("Unexpected half at y=$y, mean RGB ($red, $green, $blue)", matches)
    }

    @Test fun splitRowsFollowBothOrdersAndRestoreTheOriginalHalf() {
        val prefs = ImagePreferences(splitWide = true, direction = ImageDirection.RTL)
        open(prefs)
        for (direction in listOf(ImageDirection.RTL, ImageDirection.LTR)) {
            val rightFirst = direction == ImageDirection.RTL
            val first = if (rightFirst) 1 else 0
            val firstColor = if (rightFirst) Color.BLUE else Color.RED
            val secondColor = if (rightFirst) Color.RED else Color.BLUE
            compose.runOnIdle {
                view.configure(prefs.copy(direction = direction), Color.WHITE)
                view.move(ImageLocator())
            }
            waitForTiles()
            compose.runOnIdle {
                assertSide(0, first, firstColor)
                assertFalse(view.locator().completed)
                // Leave half of the first row visible, then sample across the seam.
                view.move(ImageLocator(segment = first, y = .5f))
            }
            waitForTiles()
            compose.runOnIdle {
                assertSide(0, first, firstColor, .08f)
                val boundary = view.width * 1092f / (1435f / 2) * .5f / view.height
                assertSide(0, first, secondColor, boundary + .08f)
                view.move(ImageLocator(page = 1))
            }
            waitForTiles()
            compose.runOnIdle {
                assertSide(1, -1, Color.GREEN)
                view.turn(-1)
            }
            waitForTiles()
            compose.runOnIdle {
                assertEquals(0, view.locator().page)
                view.move(ImageLocator(page = 0, segment = 1 - first, y = .24f, zoom = 2f))
            }
            waitForTiles()
            compose.runOnIdle {
                val saved = view.locator()
                assertSide(0, 1 - first, secondColor)
                view.move(ImageLocator(page = 2))
                view.move(saved)
                val restored = view.locator()
                assertEquals(saved.key, restored.key)
                assertEquals(saved.segment, restored.segment)
                assertEquals(saved.y, restored.y, .001f)
                assertEquals(saved.x, restored.x, .001f)
                assertEquals(saved.zoom, restored.zoom, .001f)
                view.move(ImageLocator(page = 11, segment = first))
            }
            waitForTiles()
            compose.runOnIdle {
                assertSide(11, first, firstColor)
                assertFalse(view.locator().completed)
                view.turn(1)
            }
            waitForTiles()
            compose.runOnIdle {
                assertSide(11, 1 - first, secondColor)
                assertTrue(view.locator().completed)
            }
        }
    }

    private fun touch(action: Int, down: Long, y: Float) {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width / 2f, y * view.height, 0)
            .also { view.onTouchEvent(it); it.recycle() }
    }

    @Test fun newPagesSharpenWhileFingerIsStillDown() {
        open(ImagePreferences())
        val down = SystemClock.uptimeMillis()
        compose.runOnIdle { touch(MotionEvent.ACTION_DOWN, down, .95f) }
        try {
            compose.runOnUiThread { touch(MotionEvent.ACTION_MOVE, down, .83f) }
            compose.runOnUiThread { touch(MotionEvent.ACTION_MOVE, down, .04f) }
            compose.waitUntil(5_000) { compose.runOnUiThread {
                val pixels = scan(.88f)
                val levels = pixels.map { maxOf(Color.red(it), Color.green(it), Color.blue(it)) }
                levels.zipWithNext().count { (a, b) -> kotlin.math.abs(a - b) > 85 } >= 7
            } }
        } finally {
            compose.runOnUiThread { touch(MotionEvent.ACTION_CANCEL, down, .04f) }
        }
    }

    @After fun close() {
        if (::view.isInitialized) compose.runOnIdle { view.dispose() }
        if (::source.isInitialized) source.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }
}
