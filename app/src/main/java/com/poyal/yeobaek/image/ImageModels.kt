package com.poyal.yeobaek.image

import com.poyal.yeobaek.data.Book
import com.poyal.yeobaek.data.ImagePosition
import java.io.IOException
import java.util.UUID

class ImageFailure(message: String) : IOException(message)
enum class ImageMode { VERTICAL, PAGED }
enum class ImageDirection { LTR, RTL }
enum class ImageSpread { SINGLE, DOUBLE, LANDSCAPE }
enum class ImageFit { WIDTH, PAGE }
enum class ImageCanvas { AUTO, LIGHT, DARK }
data class ImagePreferences(
    val mode: ImageMode = ImageMode.VERTICAL, val direction: ImageDirection = ImageDirection.LTR,
    val spread: ImageSpread = ImageSpread.SINGLE, val coverAlone: Boolean = true,
    val verticalFit: ImageFit = ImageFit.WIDTH, val pagedFit: ImageFit = ImageFit.PAGE,
    val spacing: Float = 0f, val inverted: Boolean = false, val canvas: ImageCanvas = ImageCanvas.AUTO,
) { val fit get() = if (mode == ImageMode.VERTICAL) verticalFit else pagedFit }
data class ImagePage(val key: String, val name: String, val path: String, val size: Long = -1,
    val width: Int = 1000, val height: Int = 1414, val measured: Boolean = false) {
    val wide get() = measured && width > height
}
data class ImageLocator(val page: Int = 0, val key: String = "", val x: Float = 0f, val y: Float = 0f,
    val zoom: Float = 1f, val completed: Boolean = false) {
    fun constrained(pages: List<ImagePage>): ImageLocator {
        if (pages.isEmpty()) return copy(page = 0, key = "")
        val index = pages.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: page.coerceIn(pages.indices)
        return copy(page = index, key = pages[index].key, x = x.finite(0f).coerceIn(0f, 1f),
            y = y.finite(0f).coerceIn(0f, 1f), zoom = zoom.finite(1f).coerceIn(1f, 5f))
    }
    fun progress(count: Int): Float = if (completed) 1f else ((page + y) / count.coerceAtLeast(1)).coerceIn(0f, 1f)
}
private fun Float.finite(fallback: Float) = if (isFinite()) this else fallback
fun ImagePosition.locator() = ImageLocator(page, pageKey, x, y, zoom, completed)
data class ImageSeek(val locator: ImageLocator, val serial: Long = System.nanoTime())
data class OpenImages(val book: Book, val source: ImageSource, val initial: ImageLocator,
    val token: String = UUID.randomUUID().toString())

/** Logical page order never changes for RTL: only the positions inside a spread do. */
fun imageSpreads(pages: List<ImagePage>, double: Boolean, coverAlone: Boolean): List<List<Int>> {
    val result = ArrayList<List<Int>>()
    var index = 0
    while (index < pages.size) {
        val single = !double || (index == 0 && coverAlone) || pages[index].wide ||
            index + 1 == pages.size || pages[index + 1].wide
        result += if (single) listOf(index++) else listOf(index++, index++)
    }
    return result
}

object ImageFormats {
    val extensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    val archives = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7")
    fun image(name: String) = name.substringAfterLast('.', "").lowercase() in extensions
    fun archive(name: String) = name.substringAfterLast('.', "").lowercase() in archives
    fun safePath(value: String): String? {
        val path = value.replace('\\', '/')
        val parts = path.split('/')
        if (path.startsWith('/') || path.contains('\u0000') || parts.any { it == ".." } ||
            parts.firstOrNull()?.contains(':') == true) return null
        if (parts.any { it == "__MACOSX" || it.startsWith("._") }) return null
        return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/").takeIf { it.isNotEmpty() }
    }
}
