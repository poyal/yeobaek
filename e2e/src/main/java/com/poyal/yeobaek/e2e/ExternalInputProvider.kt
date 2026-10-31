package com.poyal.yeobaek.e2e

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Test-only non-DocumentsProvider: exercises a grant from another app. */
class ExternalInputProvider : ContentProvider() {
    companion object { val activeSlowWrites = java.util.concurrent.atomic.AtomicInteger() }
    override fun onCreate() = true
    override fun getType(uri: Uri) = if (uri.getQueryParameter("unknownMime") == "1") "application/octet-stream" else when (uri.lastPathSegment?.substringAfterLast('.')) {
        "png" -> "image/png"; "epub" -> "application/epub+zip"; "pdf" -> "application/pdf"; "cbz" -> "application/zip"; else -> "text/plain"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> if (uri.getQueryParameter("unknownSize") == "1") -1L else file.length(); else -> null } }) }
    }
    private fun file(uri: Uri): File {
        val name = requireNotNull(uri.lastPathSegment)
        require(name in setOf("external-reader.txt", "external-page.png", "external-reflow.epub", "external-fixed.epub", "external-drm.epub", "external-book.pdf", "external-comic.cbz"))
        return File(requireNotNull(context).cacheDir, name)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val file = file(uri)
        if (uri.getQueryParameter("slow") != "1") return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        activeSlowWrites.incrementAndGet()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); Thread.sleep(10) }
                    }
                }
            } catch (_: java.io.IOException) { /* Closing a cancelled consumer terminates the writer. */ }
            finally { activeSlowWrites.decrementAndGet() }
        }, "slow-synthetic-document").apply { isDaemon = true; start() }
        return pipe[0]
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
