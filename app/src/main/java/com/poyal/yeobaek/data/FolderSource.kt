package com.poyal.yeobaek.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.text.Collator
import java.util.Locale

@kotlinx.serialization.Serializable
data class DocumentEntry(val id: String, val uri: String, val name: String, val directory: Boolean, val size: Long?, val modified: Long?, val mime: String) {
    val epub get() = name.endsWith(".epub", true) || mime == "application/epub+zip"
    val txt get() = name.endsWith(".txt", true) || mime == "text/plain"
    val pdf get() = name.endsWith(".pdf", true) || mime == "application/pdf"
    val image get() = com.poyal.yeobaek.image.ImageFormats.image(name) || mime in setOf("image/jpeg", "image/png", "image/webp", "image/bmp", "image/gif")
    val archive get() = com.poyal.yeobaek.image.ImageFormats.archive(name) || mime in setOf("application/zip", "application/x-rar-compressed", "application/vnd.rar", "application/x-7z-compressed")
    val supported get() = epub || txt || pdf || image || archive
    val formatLabel get() = when { epub -> "EPUB"; pdf -> "PDF"; image -> "이미지"; archive -> "압축 이미지"; else -> "TXT" }
}
class FolderSource(private val resolver: ContentResolver) {
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    fun root(tree: String): DocumentEntry {
        val uri = Uri.parse(tree)
        return info(DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)).toString())
    }
    fun info(uri: String): DocumentEntry {
        val parsed = Uri.parse(uri)
        require(parsed.scheme == "content") { "콘텐츠 파일만 열 수 있습니다." }
        if (runCatching { DocumentsContract.getDocumentId(parsed) }.isFailure) {
            return resolver.query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) throw FileNotFoundException()
                val name = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val size = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                DocumentEntry(uri, uri, if (name >= 0) c.getString(name) ?: "이름 없는 파일" else "이름 없는 파일", false,
                    if (size >= 0 && !c.isNull(size)) c.getLong(size) else null, null, resolver.getType(parsed).orEmpty())
            } ?: throw FileNotFoundException()
        }
        return resolver.query(parsed, projection, null, null, null)?.use { c ->
        if (!c.moveToFirst()) throw FileNotFoundException("파일을 찾을 수 없습니다.")
        DocumentEntry(c.getString(0), uri, c.getString(1) ?: "이름 없는 파일", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
            if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(2) ?: "")
        } ?: throw FileNotFoundException("파일 정보를 읽지 못했습니다.")
    }
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
    /** Resolve from the document itself so recent/resumed books do not use an unrelated UI folder. */
    fun parentPath(uri: String, knownPath: List<DocumentEntry>): List<DocumentEntry> {
        val document = Uri.parse(uri)
        val id = DocumentsContract.getDocumentId(document)
        if (DocumentsContract.isTreeUri(document)) {
            val rootId = DocumentsContract.getTreeDocumentId(document)
            if (id == rootId) return emptyList()
            val ids = try { DocumentsContract.findDocumentPath(resolver, document)?.path }
                catch (_: UnsupportedOperationException) { null }
                catch (_: IllegalArgumentException) { null }
            val rootIndex = ids?.indexOf(rootId) ?: -1
            if (ids != null && rootIndex >= 0 && ids.lastOrNull() == id) {
                return ids.subList(rootIndex, ids.lastIndex).map {
                    info(DocumentsContract.buildDocumentUriUsingTree(document, it).toString())
                }
            }
        }
        // Some providers do not implement findDocumentPath. Verify membership before falling back.
        for (index in knownPath.indices.reversed()) {
            val parent = knownPath[index]
            if (key(parent.uri) != key(uri) && list(parent.uri, parent.id).any { key(it.uri) == key(uri) }) {
                return knownPath.take(index + 1)
            }
        }
        throw java.io.IOException("문서가 있는 폴더를 확인하지 못했습니다.")
    }
    fun release(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun open(uri: String) = resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("원본을 열 수 없습니다.")
    fun key(uri: String): String = Uri.parse(uri).let { parsed ->
        runCatching { "${parsed.authority}:${DocumentsContract.getDocumentId(parsed)}" }.getOrElse { "content:$uri" }
    }
}

fun sortEntries(entries: List<DocumentEntry>, sort: FolderSort): List<DocumentEntry> {
    val collator = Collator.getInstance(Locale.KOREAN)
    return entries.sortedWith { a, b ->
        if (a.directory != b.directory) if (a.directory) -1 else 1
        else {
            val order = when (sort) {
                FolderSort.NAME -> 0
                FolderSort.MODIFIED -> (b.modified ?: Long.MIN_VALUE).compareTo(a.modified ?: Long.MIN_VALUE)
                FolderSort.SIZE -> (b.size ?: Long.MIN_VALUE).compareTo(a.size ?: Long.MIN_VALUE)
            }
            order.takeIf { it != 0 } ?: naturalCompare(a.name, b.name, collator)
        }
    }
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
