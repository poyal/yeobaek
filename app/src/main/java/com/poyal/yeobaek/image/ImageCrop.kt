package com.poyal.yeobaek.image

import kotlinx.serialization.Serializable
import kotlin.math.abs

enum class CropMode { OFF, MANUAL, AUTO }
@Serializable data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && width >= .2f && height >= .2f
    fun half(rightHalf: Boolean) = if (rightHalf) copy(left = (left + right) / 2) else copy(right = (left + right) / 2)
}

/** Conservative monochrome-border detector. Full bleed, gradients and uncertain edges stay intact. */
fun detectImageCrop(pixels: IntArray, width: Int, height: Int): CropRect {
    if (width < 16 || height < 16 || pixels.size != width * height) return CropRect()
    fun gray(c: Int) = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
    val corners = listOf(pixels[0], pixels[width - 1], pixels[(height - 1) * width], pixels.last()).map(::gray)
    if (!(corners.all { it >= 245 } || corners.all { it <= 10 })) return CropRect()
    val background = corners.average().toInt()
    fun blank(c: Int): Boolean = abs(gray(c) - background) <= 12 &&
        abs((c shr 16 and 255) - (c shr 8 and 255)) <= 8 && abs((c shr 8 and 255) - (c and 255)) <= 8
    fun row(y: Int) = (0 until width).all { blank(pixels[y * width + it]) }
    fun column(x: Int) = (0 until height).all { blank(pixels[it * width + x]) }
    var l = 0; var r = width - 1; var t = 0; var b = height - 1
    while (l < width / 5 && column(l)) l++
    while (r > width * 4 / 5 && column(r)) r--
    while (t < height / 5 && row(t)) t++
    while (b > height * 4 / 5 && row(b)) b--
    // Do not crop blank pages or borders wider than the conservative search range.
    if (column(l) || column(r) || row(t) || row(b)) return CropRect()
    return CropRect(((l - 2).coerceAtLeast(0)).toFloat() / width, ((t - 2).coerceAtLeast(0)).toFloat() / height,
        ((r + 3).coerceAtMost(width)).toFloat() / width, ((b + 3).coerceAtMost(height)).toFloat() / height)
}
