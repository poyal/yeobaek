package com.poyal.yeobaek.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.text.Collator
import java.util.Locale

data class DocumentEntry(val id: String, val uri: String, val name: String, val directory: Boolean, val size: Long?, val modified: Long?, val mime: String) {
    val txt get() = name.endsWith(".txt", true) || mime == "text/plain"
}
class FolderSource(private val resolver: ContentResolver) {
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    fun root(tree: String): DocumentEntry {
        val uri = Uri.parse(tree)
        return info(DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)).toString())
    }
    fun info(uri: String): DocumentEntry = resolver.query(Uri.parse(uri), projection, null, null, null)?.use { c ->
        if (!c.moveToFirst()) throw FileNotFoundException("파일을 찾을 수 없습니다.")
        DocumentEntry(c.getString(0), uri, c.getString(1) ?: "이름 없는 파일", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
            if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(2) ?: "")
    } ?: throw FileNotFoundException("파일 정보를 읽지 못했습니다.")
    fun list(tree: String, parentId: String): List<DocumentEntry> {
        val treeUri = Uri.parse(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val entries: List<DocumentEntry> = resolver.query(children, projection, null, null, null)?.use { c ->
            buildList<DocumentEntry> {
                while (c.moveToNext()) add(DocumentEntry(c.getString(0), DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)).toString(),
                    c.getString(1) ?: "이름 없는 파일", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(2) ?: ""))
            }
        } ?: throw FileNotFoundException("폴더 목록을 읽지 못했습니다.")
        val collator = Collator.getInstance(Locale.KOREAN)
        return entries.sortedWith { a, b ->
            if (a.directory != b.directory) if (a.directory) -1 else 1
            else naturalCompare(a.name, b.name, collator)
        }
    }
    fun connect(uri: Uri): DocumentEntry {
        val entry = root(uri.toString())
        list(uri.toString(), entry.id)
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return entry
    }
    fun release(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun open(uri: String) = resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("원본을 열 수 없습니다.")
    fun key(uri: String): String = Uri.parse(uri).let { "${it.authority}:${DocumentsContract.getDocumentId(it)}" }
}

internal fun naturalCompare(a: String, b: String, collator: Collator): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            val ai = i; val bj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val av = a.substring(ai, i).trimStart('0').ifEmpty { "0" }
            val bv = b.substring(bj, j).trimStart('0').ifEmpty { "0" }
            val c = av.length.compareTo(bv.length).takeIf { it != 0 } ?: av.compareTo(bv)
            if (c != 0) return c
        } else {
            val c = collator.compare(a[i].toString(), b[j].toString())
            if (c != 0) return c
            i++; j++
        }
    }
    return a.length.compareTo(b.length)
}
