package com.poyal.yeobaek.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache

internal sealed interface ImageRaster {
    val page: Int

    data class Preview(override val page: Int) : ImageRaster
    data class Tile(override val page: Int, val pw: Int, val ph: Int, val x: Int, val y: Int,
                    val w: Int, val h: Int) : ImageRaster {
        fun bounds(pageRect: RectF, out: RectF) {
            val sx = pageRect.width() / pw
            val sy = pageRect.height() / ph
            out.set(pageRect.left + x * sx, pageRect.top + y * sy,
                pageRect.left + (x + w) * sx, pageRect.top + (y + h) * sy)
        }
    }
}

/** Previews and all zoom levels share one byte budget. The page index only holds keys. */
internal class ImageTileCache : LruCache<ImageRaster, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
) {
    private val tiles = mutableMapOf<Int, MutableSet<ImageRaster.Tile>>()
    private val destination = RectF()

    override fun sizeOf(key: ImageRaster, value: Bitmap) = value.byteCount
    override fun entryRemoved(evicted: Boolean, key: ImageRaster, oldValue: Bitmap, newValue: Bitmap?) {
        if (key is ImageRaster.Tile && newValue == null) {
            val page = tiles[key.page] ?: return
            page.remove(key)
            if (page.isEmpty()) tiles.remove(key.page)
        }
    }

    fun store(key: ImageRaster, bitmap: Bitmap) {
        if (key is ImageRaster.Tile) tiles.getOrPut(key.page) { mutableSetOf() }.add(key)
        put(key, bitmap)
    }

    fun draw(canvas: Canvas, page: Int, rect: RectF, viewport: RectF, exact: List<ImageRaster.Tile>, paint: Paint): Boolean {
        var painted = false
        fun drawTile(tile: ImageRaster.Tile, replace: Boolean) {
            tile.bounds(rect, destination)
            if (!RectF.intersects(destination, viewport)) return
            val bitmap = get(tile) ?: return
            // A more detailed tile replaces the underlying pixels, including transparent areas.
            if (replace) canvas.drawRect(destination, paint)
            canvas.drawBitmap(bitmap, null, destination, paint)
            painted = true
        }
        if (exact.isEmpty() || exact.any { get(it) == null }) {
            get(ImageRaster.Preview(page))?.let {
                canvas.drawBitmap(it, null, rect, paint)
                painted = true
            }
            val target = exact.firstOrNull()
            tiles[page]?.forEach { tile ->
                if (target == null || tile.pw != target.pw || tile.ph != target.ph) drawTile(tile, painted)
            }
        }
        val hasUnderlay = painted
        exact.forEach { drawTile(it, hasUnderlay) }
        return painted
    }
}
