package com.poyal.yeobaek.image

import android.content.*
import android.graphics.Bitmap
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A distinct isolated binding makes cancel/timeout safe even while another book is still open. */
class ImageEngine(private val context: Context) {
    private val instance = "image" + UUID.randomUUID().toString().replace("-", "")
    private val lock = Mutex()
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<Bundle>>()
    private val serial = AtomicInteger()
    private var remote: Messenger? = null
    private var connecting: CompletableDeferred<Messenger>? = null
    private var bound = false
    @Volatile private var closed = false
    @Suppress("DEPRECATION")
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = message.data
        val waiter = pending.remove(message.arg1)
        if (waiter == null) data.getParcelable<SharedMemory>("pixels")?.close()
        else waiter.resume(data) { _, result, _ -> result.getParcelable<SharedMemory>("pixels")?.close() }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = Messenger(binder)
            if (closed) { runCatching { service.send(Message.obtain(null, ImageProtocol.STOP)) }; return }
            remote = service; connecting?.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName) = close()
        override fun onBindingDied(name: ComponentName) = close()
        override fun onNullBinding(name: ComponentName) = close()
    }
    internal suspend fun request(command: Int, input: Bundle = Bundle(), timeout: Long = 60_000): Bundle = lock.withLock {
        if (closed) throw ImageFailure("이미지 처리 연결이 끊겼습니다. 문서를 닫고 다시 열어 주세요.")
        try {
            withTimeout(timeout) {
                val service = remote ?: withContext(Dispatchers.Main.immediate) {
                    connecting = CompletableDeferred()
                    bound = context.bindIsolatedService(Intent(context, ImageWorkerService::class.java), Context.BIND_AUTO_CREATE,
                        instance, context.mainExecutor, connection)
                    if (!bound) throw ImageFailure("이미지 뷰어를 시작하지 못했습니다.")
                    connecting!!.await()
                }
                val id = serial.incrementAndGet()
                val output = suspendCancellableCoroutine<Bundle> { waiter ->
                    pending[id] = waiter; waiter.invokeOnCancellation { pending.remove(id, waiter) }
                    try { service.send(Message.obtain(null, command, id, 0).apply { data = input; replyTo = replies }) }
                    catch (e: RemoteException) { pending.remove(id, waiter); waiter.resumeWith(Result.failure(ImageFailure("이미지 처리 연결이 끊겼습니다."))) }
                }
                output.getString("error")?.let { throw ImageFailure(it) }
                output
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive(); close()
            throw ImageFailure("이미지 준비 시간이 초과되었습니다. 파일 상태를 확인하고 다시 열어 주세요.")
        }
    }
    @Suppress("DEPRECATION")
    suspend fun archive(file: File, progress: (String) -> Unit): List<ImagePage> {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { request(ImageProtocol.OPEN, Bundle().apply { putParcelable("file", it) }) }
        val pages = ArrayList<ImagePage>()
        do {
            currentCoroutineContext().ensureActive()
            val batch = request(ImageProtocol.LIST, timeout = 180_000)
            batch.getParcelableArrayList<Bundle>("entries").orEmpty().forEach { b ->
                val path = requireNotNull(b.getString("path"))
                pages += ImagePage("${b.getInt("index")}:$path", path.substringAfterLast('/'), path, b.getLong("size"))
            }
            progress("압축 목록 확인 중 · ${batch.getInt("listed")}개")
        } while (!batch.getBoolean("done"))
        return pages
    }
    suspend fun extract(index: Int, file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use { fd ->
            request(ImageProtocol.EXTRACT, Bundle().apply { putInt("index", index); putParcelable("output", fd) }, 180_000)
        }
    }
    suspend fun size(file: File): Pair<Int, Int> = withFile(file, ImageProtocol.META).let { it.getInt("width") to it.getInt("height") }
    private suspend fun withFile(file: File, command: Int, input: Bundle = Bundle()): Bundle =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            input.putParcelable("file", fd); input.putString("key", file.name)
            request(command, input)
        }
    @Suppress("DEPRECATION")
    suspend fun render(file: File, pw: Int, ph: Int, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val result = withFile(file, ImageProtocol.TILE, Bundle().apply {
            putInt("pw", pw); putInt("ph", ph); putInt("x", x); putInt("y", y); putInt("w", w); putInt("h", h)
        })
        val memory = result.getParcelable<SharedMemory>("pixels") ?: throw ImageFailure("이미지를 표시하지 못했습니다.")
        return memory.use {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val buffer = memory.mapReadOnly()
            try { bitmap.copyPixelsFromBuffer(buffer); bitmap } finally { SharedMemory.unmap(buffer) }
        }
    }
    fun close() {
        if (closed) return
        closed = true
        runCatching { remote?.send(Message.obtain(null, ImageProtocol.STOP)) }
        val failure = ImageFailure("이미지 문서가 닫혔습니다. 다시 열어 주세요.")
        connecting?.completeExceptionally(failure)
        pending.entries.forEach { (key, waiter) -> if (pending.remove(key, waiter)) waiter.resumeWith(Result.failure(failure)) }
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
        remote = null
    }
}
