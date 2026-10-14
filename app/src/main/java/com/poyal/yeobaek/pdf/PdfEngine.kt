package com.poyal.yeobaek.pdf

import android.content.*
import android.graphics.Bitmap
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PdfFailure(message: String) : IOException(message)

/** Serialized, cancellable IPC. A renderer failure cannot take down the reading library. */
class PdfEngine(private val context: Context) {
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<Bundle>>()
    private val sequence = AtomicInteger()
    @Volatile private var service: Messenger? = null
    @Volatile private var binding: CompletableDeferred<Messenger>? = null
    @Volatile private var bound = false
    @Suppress("DEPRECATION")
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val result = message.data
        val waiter = pending.remove(message.arg1)
        if (waiter == null) result.getParcelable<SharedMemory>("pixels")?.close()
        else waiter.resume(result) { _, response, _ -> response.getParcelable<SharedMemory>("pixels")?.close() }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder); binding?.complete(service!!)
        }
        override fun onServiceDisconnected(name: ComponentName) = failed()
        override fun onBindingDied(name: ComponentName) = failed()
        override fun onNullBinding(name: ComponentName) = failed()
    }
    private fun failed() {
        service = null
        val failure = PdfFailure("PDF 처리 연결이 끊겼습니다. 문서를 닫고 다시 열어 주세요.")
        binding?.completeExceptionally(failure)
        pending.entries.forEach { (key, reply) -> if (pending.remove(key, reply)) reply.resumeWith(Result.failure(failure)) }
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
    }
    internal suspend fun request(command: Int, id: String, input: Bundle = Bundle()): Bundle = mutex.withLock {
        try {
            withTimeout(30_000) {
                val remote = service ?: run {
                    binding = CompletableDeferred()
                    bound = context.bindService(Intent(context, PdfRenderService::class.java), connection, Context.BIND_AUTO_CREATE)
                    if (!bound) throw PdfFailure("PDF 뷰어를 시작하지 못했습니다.")
                    binding!!.await()
                }
                val key = sequence.incrementAndGet()
                val output = suspendCancellableCoroutine<Bundle> { reply ->
                    pending[key] = reply
                    reply.invokeOnCancellation { pending.remove(key, reply) }
                    try {
                        input.putString("document", id)
                        remote.send(Message.obtain(null, command, key, 0).apply { data = input; replyTo = replies })
                    } catch (e: Exception) { pending.remove(key, reply); reply.resumeWith(Result.failure(e)) }
                }
                output.getString("error")?.let { throw PdfFailure(it) }
                output
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failed(); throw PdfFailure("PDF 응답 시간이 초과되었습니다. 문서를 다시 열어 주세요.")
        } catch (e: RemoteException) { failed(); throw PdfFailure("PDF 처리 연결이 끊겼습니다. 문서를 다시 열어 주세요.") }
    }
    suspend fun open(file: File, progress: (String) -> Unit = {}): PdfDocumentHandle {
        val id = UUID.randomUUID().toString()
        try {
            val count = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                request(PdfProtocol.OPEN, id, Bundle().apply { putParcelable("file", fd) }).getInt("count")
            }
            val sizes = ArrayList<PdfPageSize>(count)
            for (start in 0 until count step 32) {
                currentCoroutineContext().ensureActive()
                progress("PDF 페이지 확인 중 · ${start + 1}/$count")
                val result = request(PdfProtocol.SIZES, id, Bundle().apply { putInt("start", start); putInt("count", 32) }).getIntArray("sizes")!!
                for (i in result.indices step 2) sizes += PdfPageSize(result[i], result[i + 1])
            }
            return PdfDocumentHandle(this, id, file, sizes)
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { request(PdfProtocol.CLOSE, id) } }; throw e
        }
    }
    fun dispose() { failed() }
}

class PdfDocumentHandle internal constructor(private val engine: PdfEngine, private val id: String, val file: File, val pages: List<PdfPageSize>) {
    val version get() = file.nameWithoutExtension
    suspend fun close() { runCatching { engine.request(PdfProtocol.CLOSE, id) } }
    @Suppress("DEPRECATION")
    suspend fun render(page: Int, pageWidth: Int, pageHeight: Int, left: Int, top: Int, width: Int, height: Int): Bitmap {
        val output = engine.request(PdfProtocol.RENDER, id, Bundle().apply {
            putInt("page", page); putInt("pageWidth", pageWidth); putInt("pageHeight", pageHeight)
            putInt("left", left); putInt("top", top); putInt("width", width); putInt("height", height)
        })
        val memory = output.getParcelable<SharedMemory>("pixels") ?: throw PdfFailure("페이지를 표시하지 못했습니다.")
        return memory.use {
            withContext(Dispatchers.Default) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val buffer = memory.mapReadOnly()
                try { bitmap.copyPixelsFromBuffer(buffer); bitmap } finally { SharedMemory.unmap(buffer) }
            }
        }
    }
    @Suppress("DEPRECATION")
    suspend fun search(page: Int, query: String, start: Int): PdfSearchBatch {
        val b = engine.request(PdfProtocol.SEARCH, id, Bundle().apply { putInt("page", page); putString("query", query); putInt("start", start) })
        return PdfSearchBatch(b.getParcelableArrayList<Bundle>("matches").orEmpty().map { PdfMatch(page, it.getInt("start"), it.getInt("count"), it.getString("excerpt").orEmpty()) }, b.getInt("next"), b.getInt("textCount"))
    }
    suspend fun range(hit: PdfMatch) = readRange(PdfProtocol.RANGE, hit.page, Bundle().apply { putInt("start", hit.start); putInt("count", hit.count) })
    suspend fun select(page: Int, x1: Float, y1: Float, x2: Float, y2: Float) = readRange(PdfProtocol.SELECT, page, Bundle().apply {
        putFloat("x1", x1); putFloat("y1", y1); putFloat("x2", x2); putFloat("y2", y2)
    })
    private suspend fun readRange(command: Int, page: Int, input: Bundle): PdfTextRange {
        input.putInt("page", page)
        val b = engine.request(command, id, input)
        val values = b.getFloatArray("rects") ?: floatArrayOf()
        return PdfTextRange(page, b.getInt("start"), b.getInt("count"), b.getString("text").orEmpty(), values.toList().chunked(4).map { PdfRect(it[0], it[1], it[2], it[3]) })
    }
}

class PdfStore(cache: File) {
    private val directory = File(cache, "pdf").apply { mkdirs() }
    suspend fun prepare(open: () -> InputStream): File = withContext(Dispatchers.IO) {
        val staging = File(directory, "${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { input -> staging.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer); if (read < 0) break
                    digest.update(buffer, 0, read); output.write(buffer, 0, read)
                }
            } }
            val target = File(directory, digest.digest().joinToString("") { "%02x".format(it) } + ".pdf")
            if (target.exists()) staging.delete() else if (!staging.renameTo(target)) throw IOException("PDF cache")
            target.setLastModified(System.currentTimeMillis())
            target
        } finally { staging.delete() }
    }
    fun clear(pinned: Set<File> = emptySet()) { directory.listFiles()?.filter { it !in pinned }?.forEach { it.delete() } }
    fun trim(pinned: Set<File>) {
        val files = directory.listFiles()?.filter { it.extension == "pdf" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var used = pinned.sumOf { it.length() }
        files.filter { it !in pinned }.forEach { file -> if (used + file.length() <= 512L * 1024 * 1024) used += file.length() else file.delete() }
    }
    fun clean() { directory.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() } }
    companion object {
        fun signature(input: InputStream): Boolean = input.use {
            val bytes = ByteArray(1024); var count = 0
            while (count < bytes.size) { val read = it.read(bytes, count, bytes.size - count); if (read <= 0) break; count += read }
            String(bytes, 0, count, Charsets.ISO_8859_1).contains("%PDF-")
        }
    }
}
