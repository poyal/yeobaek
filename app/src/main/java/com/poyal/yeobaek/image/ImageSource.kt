package com.poyal.yeobaek.image

import android.content.Context
import android.graphics.Bitmap
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class ImageStore(cache: File) {
    val directory = File(cache, "images").apply { mkdirs() }
    private val pins = ConcurrentHashMap<File, Int>()
    fun pin(file: File) { pins.compute(file) { _, count -> (count ?: 0) + 1 } }
    fun unpin(file: File) { pins.computeIfPresent(file) { _, count -> (count - 1).takeIf { it > 0 } } }
    fun target(key: String) = File(directory, digest(key.toByteArray()))
    fun part() = File(directory, UUID.randomUUID().toString() + ".part")
    fun clean() { directory.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }; trim() }
    fun clear() { directory.listFiles()?.filter { !pins.containsKey(it) }?.forEach { it.delete() } }
    fun trim(extra: Set<File> = emptySet()) {
        var used = 0L
        directory.listFiles()?.filter { it.extension != "part" && !pins.containsKey(it) && it !in extra }
            ?.sortedByDescending(File::lastModified)?.forEach { file ->
                if (used + file.length() > 512L * 1024 * 1024) file.delete() else used += file.length()
            }
    }
    suspend fun copy(open: () -> InputStream, limit: Long = Long.MAX_VALUE, progress: (String) -> Unit = {}, cachedVersion: String? = null): File = withContext(Dispatchers.IO) {
        val known = cachedVersion?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { File(directory, it) }?.takeIf { it.isFile }
        if (known != null) {
            suspend fun hash(input: InputStream): String = input.use {
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
                while (true) { ensureActive(); val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().hex()
            }
            progress("원본 변경 여부 확인 중")
            val sourceMatches = hash(open()) == cachedVersion
            val cacheMatches = if (sourceMatches) try { hash(known.inputStream()) == cachedVersion }
                catch (e: CancellationException) { throw e }
                catch (_: java.io.IOException) { false }
            else false
            if (sourceMatches && cacheMatches) {
                known.setLastModified(System.currentTimeMillis()); return@withContext known
            }
        }
        val part = part()
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L; var last = 0L
            open().use { input -> part.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive(); val n = input.read(buffer); if (n < 0) break
                    count += n
                    if (count > limit) throw ImageFailure("개별 이미지는 256MiB까지 읽을 수 있습니다.")
                    if (directory.usableSpace < 32L * 1024 * 1024) throw ImageFailure("이미지를 준비할 저장 공간이 부족합니다.")
                    hash.update(buffer, 0, n); output.write(buffer, 0, n)
                    if (count - last >= 8 * 1024 * 1024) { progress("파일 준비 중 · ${count / 1024 / 1024}MiB"); last = count }
                }
            } }
            val file = File(directory, hash.digest().hex())
            // Replace from a complete new copy, also repairing a damaged previous cache file.
            if (!part.renameTo(file)) throw ImageFailure("이미지 캐시를 저장하지 못했습니다.")
            file.setLastModified(System.currentTimeMillis()); file
        } finally { part.delete() }
    }
    companion object {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

/** Metadata is lazy; listing a folder never decodes all its images. */
class ImageSource private constructor(val version: String, initial: List<ImagePage>,
    private val engine: ImageEngine, private val store: ImageStore,
    private val archiveFile: File?, private val openImage: ((ImagePage) -> InputStream)?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = ConcurrentHashMap<String, Deferred<File>>()
    private val measured = ConcurrentHashMap<String, Deferred<Pair<Int, Int>>>()
    private val extraction = Mutex()
    private val _pages = MutableStateFlow(initial)
    val pages = _pages.asStateFlow()
    val activity = MutableStateFlow<String?>(null)
    @Volatile private var closed = false
    private suspend fun file(page: ImagePage): File {
        if (closed) throw ImageFailure("이미지 문서가 닫혔습니다.")
        while (true) {
            val deferred = files.computeIfAbsent(page.key) {
                scope.async {
                    extraction.withLock {
                        activity.value = "${page.name} 준비 중"
                        try {
                            if (archiveFile == null) store.copy({ requireNotNull(openImage)(page) }, ImageProtocol.MAX_ENTRY)
                            else {
                                val target = store.target("$version:${page.key}")
                                if (target.isFile && (page.size <= 0 || target.length() == page.size)) target
                                else {
                                    val part = store.part()
                                    try {
                                        engine.extract(page.key.substringBefore(':').toInt(), part)
                                        if (!part.renameTo(target)) throw ImageFailure("이미지 캐시를 저장하지 못했습니다.")
                                        target
                                    } finally { part.delete() }
                                }
                            }.also { it.setLastModified(System.currentTimeMillis()); store.trim(setOf(it)) }
                        } finally { activity.value = null }
                    }
                }
            }
            try {
                val value = deferred.await()
                if (value.isFile) return value
                files.remove(page.key, deferred)
            } catch (e: Exception) {
                if (deferred.isCompleted) files.remove(page.key, deferred)
                throw when (e) {
                    is CancellationException -> e
                    is ImageFailure -> e
                    is SecurityException -> ImageFailure("이미지 접근 권한이 없습니다. 시작 폴더를 다시 연결해 주세요.")
                    is java.io.FileNotFoundException -> ImageFailure("이미지 파일을 찾을 수 없습니다. 폴더를 새로고침해 주세요.")
                    else -> ImageFailure("이미지를 준비하지 못했습니다. 저장 공간과 원본 파일을 확인해 주세요.")
                }
            }
        }
    }
    suspend fun measure(index: Int): ImagePage {
        val page = pages.value[index]
        if (page.measured) return page
        val task = measured.computeIfAbsent(page.key) {
            scope.async { engine.size(file(page)) }
        }
        try {
            val size = task.await()
            _pages.update { values -> values.toMutableList().also { it[index] = page.copy(width = size.first, height = size.second, measured = true) } }
            return pages.value[index]
        } catch (e: Exception) { if (task.isCompleted) measured.remove(page.key, task); throw e }
    }
    suspend fun render(index: Int, pw: Int, ph: Int, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val page = measure(index)
        return engine.render(file(page), pw, ph, x, y, w, h)
    }
    suspend fun thumbnail(index: Int): Bitmap {
        val page = measure(index)
        val scale = 192f / max(page.width, page.height)
        val w = (page.width * scale).roundToInt().coerceIn(1, 192)
        val h = (page.height * scale).roundToInt().coerceIn(1, 192)
        return render(index, w, h, 0, 0, w, h)
    }
    fun close() {
        if (closed) return
        closed = true; scope.cancel(); engine.close()
        archiveFile?.let { store.unpin(it); store.trim() }
    }
    companion object {
        suspend fun folder(context: Context, store: ImageStore, entries: List<DocumentEntry>, folders: FolderSource): ImageSource {
            val images = entries.filter { !it.directory && it.image }
            if (images.isEmpty()) throw ImageFailure("이 폴더에는 읽을 수 있는 이미지가 없습니다.")
            if (images.size > ImageProtocol.MAX_ENTRIES) throw ImageFailure("이미지 폴더는 20,000장까지 읽을 수 있습니다.")
            val version = ImageStore.digest(images.joinToString("\n") { "${it.uri}\t${it.name}\t${it.size}\t${it.modified}" }.toByteArray())
            return ImageSource(version, images.map { ImagePage(it.uri, it.name, it.name, it.size ?: -1) }, ImageEngine(context), store, null) { page -> folders.open(page.key) }
        }
        suspend fun archive(context: Context, store: ImageStore, open: () -> InputStream, cachedVersion: String? = null, progress: (String) -> Unit = {}): ImageSource {
            val file = store.copy(open, progress = progress, cachedVersion = cachedVersion)
            store.pin(file); val engine = ImageEngine(context)
            try {
                val pages = engine.archive(file, progress)
                if (pages.isEmpty()) throw ImageFailure("압축파일 안에 읽을 수 있는 이미지가 없습니다. 압축 속 압축은 지원하지 않습니다.")
                val collator = Collator.getInstance(Locale.KOREAN)
                val sorted = pages.sortedWith { a, b ->
                    val ap = a.path.split('/'); val bp = b.path.split('/')
                    var result = 0
                    for (i in 0 until min(ap.size, bp.size)) { result = naturalCompare(ap[i], bp[i], collator); if (result != 0) break }
                    if (result == 0) ap.size.compareTo(bp.size).takeIf { it != 0 } ?: a.key.compareTo(b.key) else result
                }
                return ImageSource(file.name, sorted, engine, store, file, null)
            } catch (e: Exception) { engine.close(); store.unpin(file); throw e }
        }
    }
}
