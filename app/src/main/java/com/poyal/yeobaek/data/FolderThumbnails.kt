package com.poyal.yeobaek.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

/** A listing never opens archives or starts a renderer. Only cached/provider thumbnails are used. */
class FolderThumbnails(private val context: Context) {
    private val slots = Semaphore(2)
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val directory get() = File(context.cacheDir, "folder-thumbnails").apply { mkdirs() }
    private fun key(entry: DocumentEntry) = MessageDigest.getInstance("SHA-256").digest("${entry.uri}:${entry.size}:${entry.modified}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    suspend fun load(entry: DocumentEntry): Bitmap? = slots.withPermit { withContext(Dispatchers.IO) {
        val key = key(entry)
        memory.get(key)?.let { return@withContext it }
        val cache = File(directory, "$key.png")
        val cached = if (cache.isFile) BitmapFactory.decodeFile(cache.path) else null
        if (cached != null) { memory.put(key, cached); return@withContext cached }
        val uri = Uri.parse(entry.uri)
        val flags = runCatching { context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0 }.getOrDefault(0)
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL == 0) return@withContext null
        val signal = CancellationSignal()
        try {
            runInterruptible { context.contentResolver.loadThumbnail(uri, Size(128, 160), signal) }.also { memory.put(key, it) }
        } catch (e: CancellationException) { signal.cancel(); throw e }
        catch (_: Exception) { null }
    } }
    suspend fun save(entry: DocumentEntry, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val file = File(directory, "${key(entry)}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var size = 0L
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { size += it.length(); if (size > 24L * 1024 * 1024) it.delete() }
    }
}
