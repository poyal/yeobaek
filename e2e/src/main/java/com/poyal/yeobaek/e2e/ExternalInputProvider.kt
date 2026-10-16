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
    override fun onCreate() = true
    override fun getType(uri: Uri) = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "png" -> "image/png"; "epub" -> "application/epub+zip"; else -> "text/plain"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } }) }
    }
    private fun file(uri: Uri): File {
        val name = requireNotNull(uri.lastPathSegment)
        require(name in setOf("external-reader.txt", "external-page.png", "external-reflow.epub", "external-fixed.epub", "external-drm.epub"))
        return File(requireNotNull(context).cacheDir, name)
    }
    override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
