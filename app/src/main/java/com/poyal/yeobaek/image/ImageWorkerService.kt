package com.poyal.yeobaek.image

import android.app.Service
import android.content.Intent
import android.graphics.*
import android.os.*
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.*

internal object ImageProtocol {
    const val OPEN = 1; const val LIST = 2; const val EXTRACT = 3
    const val META = 4; const val TILE = 5; const val STOP = 6
    const val MAX_ENTRY = 256L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
}

/** One isolated instance per reading source; it can access only descriptors supplied by the app. */
class ImageWorkerService : Service() {
    private val worker = HandlerThread("image-worker")
    private lateinit var handler: Handler
    private var archive = 0L
    private var archiveFd: ParcelFileDescriptor? = null
    private var ordinal = -1
    private var listed = 0
    private var declaredBytes = 0L
    private val decoders = LinkedHashMap<String, Decoder>(4, .75f, true)
    override fun onCreate() { super.onCreate(); worker.start(); handler = Handler(worker.looper) { handle(it); true } }
    override fun onBind(intent: Intent): IBinder = Messenger(Handler(Looper.getMainLooper()) { message ->
        // This handler remains responsive even if a damaged native stream blocks the worker.
        if (message.what == ImageProtocol.STOP) android.os.Process.killProcess(android.os.Process.myPid())
        else handler.sendMessage(Message.obtain(message))
        true
    }).binder
    override fun onDestroy() { android.os.Process.killProcess(android.os.Process.myPid()); super.onDestroy() }
    private fun rewind() {
        if (archive != 0L) Archive.readFree(archive)
        archive = Archive.readNew()
        Archive.setCharset(archive, "UTF-8".toByteArray())
        Archive.readSupportFormatZip(archive); Archive.readSupportFormatRar(archive)
        Archive.readSupportFormatRar5(archive); Archive.readSupportFormat7zip(archive)
        val fd = requireNotNull(archiveFd)
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        Archive.readOpenFd(archive, fd.fd, 64 * 1024)
        ordinal = -1
    }
    private fun header(): Long {
        val entry = Archive.readNextHeader(archive)
        if (entry != 0L) {
            ordinal++
            require(ordinal < ImageProtocol.MAX_ENTRIES) { "압축 항목은 20,000개까지 읽을 수 있습니다." }
            require(!ArchiveEntry.isEncrypted(entry)) { "암호가 걸린 압축파일은 아직 지원하지 않습니다." }
        }
        return entry
    }
    private fun entryPath(entry: Long): String? {
        if (ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG || ArchiveEntry.symlink(entry) != null || ArchiveEntry.hardlink(entry) != null) return null
        val raw = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { bytes ->
            val utf = bytes.toString(Charsets.UTF_8)
            if ('\uFFFD' in utf) bytes.toString(charset("MS949")) else utf
        } ?: return null
        require(raw.length <= 4096) { "압축 내부 파일명이 너무 깁니다." }
        return ImageFormats.safePath(raw)
    }
    @Suppress("DEPRECATION")
    private fun handle(message: Message) {
        val input = message.data
        val result = Bundle()
        var shared: SharedMemory? = null
        try {
            when (message.what) {
                ImageProtocol.OPEN -> {
                    archiveFd?.close(); archiveFd = input.getParcelable("file")
                    val probe = ByteArray(1024)
                    val count = Os.pread(requireNotNull(archiveFd).fileDescriptor, probe, 0, probe.size, 0)
                    checkArchiveHeader(probe.copyOf(count.coerceAtLeast(0)))
                    listed = 0; declaredBytes = 0; rewind()
                    result.putString("engine", String(Archive.versionString()))
                }
                ImageProtocol.LIST -> {
                    val entries = arrayListOf<Bundle>()
                    var done = false
                    repeat(64) {
                        if (!done) {
                            val entry = header()
                            if (entry == 0L) done = true
                            else {
                                listed++
                                val size = ArchiveEntry.size(entry).coerceAtLeast(0)
                                declaredBytes += size
                                require(declaredBytes <= 32L * 1024 * 1024 * 1024) { "압축 해제 용량이 너무 큽니다. 폴더로 나누어 열어 주세요." }
                                val path = entryPath(entry)
                                if (path != null && ImageFormats.image(path)) {
                                    require(size <= ImageProtocol.MAX_ENTRY) { "개별 이미지는 256MiB까지 읽을 수 있습니다." }
                                    entries += Bundle().apply { putInt("index", ordinal); putString("path", path); putLong("size", size) }
                                }
                                Archive.readDataSkip(archive)
                            }
                        }
                    }
                    result.putParcelableArrayList("entries", entries); result.putBoolean("done", done); result.putInt("listed", listed)
                }
                ImageProtocol.EXTRACT -> {
                    val target = input.getInt("index")
                    require(target in 0 until ImageProtocol.MAX_ENTRIES)
                    val output = requireNotNull(input.getParcelable<ParcelFileDescriptor>("output"))
                    output.use {
                        if (target <= ordinal) rewind()
                        var entry = 0L
                        while (ordinal < target) {
                            entry = header(); require(entry != 0L) { "압축 내부 이미지를 찾을 수 없습니다." }
                            if (ordinal < target) Archive.readDataSkip(archive)
                        }
                        val path = entryPath(entry)
                        require(path != null && ImageFormats.image(path)) { "이미지 항목이 아닙니다." }
                        val buffer = ByteBuffer.allocateDirect(64 * 1024)
                        var written = 0L
                        ParcelFileDescriptor.AutoCloseOutputStream(output).channel.use { channel ->
                            while (true) {
                                buffer.clear(); Archive.readData(archive, buffer); buffer.flip()
                                if (!buffer.hasRemaining()) break
                                written += buffer.remaining()
                                require(written <= ImageProtocol.MAX_ENTRY) { "이미지의 압축 해제 크기가 너무 큽니다." }
                                val space = Os.fstatvfs(output.fileDescriptor)
                                require(space.f_bavail * space.f_frsize > 32L * 1024 * 1024) { "이미지를 준비할 저장 공간이 부족합니다." }
                                while (buffer.hasRemaining()) channel.write(buffer)
                            }
                        }
                        result.putLong("bytes", written)
                    }
                }
                ImageProtocol.META, ImageProtocol.TILE -> {
                    val key = requireNotNull(input.getString("key"))
                    val fd = requireNotNull(input.getParcelable<ParcelFileDescriptor>("file"))
                    val decoder = decoders[key]?.also { fd.close() } ?: try {
                        Decoder(fd).also {
                            while (decoders.size >= 3) decoders.remove(decoders.keys.first())?.close()
                            decoders[key] = it
                        }
                    } catch (e: Exception) { fd.close(); throw e }
                    result.putInt("width", decoder.width); result.putInt("height", decoder.height)
                    if (message.what == ImageProtocol.TILE) {
                        val bitmap = decoder.tile(input)
                        try {
                            shared = SharedMemory.create("image-tile", bitmap.byteCount)
                            val buffer = shared!!.mapReadWrite()
                            try { bitmap.copyPixelsToBuffer(buffer) } finally { SharedMemory.unmap(buffer) }
                            shared!!.setProtect(OsConstants.PROT_READ); result.putParcelable("pixels", shared)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } catch (e: Exception) {
            if (com.poyal.yeobaek.BuildConfig.DEBUG) android.util.Log.w("ImageWorker", "Command ${message.what} failed", e)
            val text = e.message.orEmpty()
            result.putString("error", when {
                text.contains("encrypt", true) || text.contains("passphrase", true) -> "암호가 걸린 압축파일은 아직 지원하지 않습니다."
                text.contains("volume", true) -> "분할 압축파일은 아직 지원하지 않습니다. 하나의 압축파일로 합쳐 주세요."
                text.any { it in '\uAC00'..'\uD7A3' } -> text
                else -> "이미지를 읽지 못했습니다. 파일이 손상되었거나 지원하지 않는 압축 방식일 수 있습니다."
            })
        } finally {
            try { message.replyTo?.send(Message.obtain(null, message.what, message.arg1, 0).apply { data = result }) }
            catch (_: RemoteException) { }
            shared?.close()
        }
    }

    private class Decoder(private val fd: ParcelFileDescriptor) {
        private val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        private val transform = Matrix()
        private var region: BitmapRegionDecoder? = null
        private var fallback: Bitmap? = null
        val width: Int
        val height: Int
        init {
            Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
            BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, bounds)
            require(bounds.outWidth in 1..200_000 && bounds.outHeight in 1..200_000) { "이미지 크기를 읽을 수 없습니다." }
            val w = bounds.outWidth.toFloat(); val h = bounds.outHeight.toFloat()
            val orientation = runCatching { ExifInterface(fd.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            transform.setValues(when (orientation) {
                2 -> floatArrayOf(-1f,0f,w, 0f,1f,0f, 0f,0f,1f)
                3 -> floatArrayOf(-1f,0f,w, 0f,-1f,h, 0f,0f,1f)
                4 -> floatArrayOf(1f,0f,0f, 0f,-1f,h, 0f,0f,1f)
                5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
                6 -> floatArrayOf(0f,-1f,h, 1f,0f,0f, 0f,0f,1f)
                7 -> floatArrayOf(0f,-1f,h, -1f,0f,w, 0f,0f,1f)
                8 -> floatArrayOf(0f,1f,0f, -1f,0f,w, 0f,0f,1f)
                else -> floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
            })
            width = if (orientation in 5..8) bounds.outHeight else bounds.outWidth
            height = if (orientation in 5..8) bounds.outWidth else bounds.outHeight
            Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
            @Suppress("DEPRECATION")
            val decodedRegion = runCatching { BitmapRegionDecoder.newInstance(fd.fileDescriptor, false) }.getOrNull()
            region = decodedRegion
            if (region == null) {
                var sample = 1
                while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_194_304 || max(width, height) / sample > 4096) sample *= 2
                Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
                fallback = BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                require(fallback != null) { "이 이미지 형식을 표시할 수 없습니다." }
            }
        }
        fun tile(b: Bundle): Bitmap {
            val pw = b.getInt("pw").coerceIn(1, 200_000); val ph = b.getInt("ph").coerceIn(1, 200_000)
            val x = b.getInt("x"); val y = b.getInt("y")
            val w = b.getInt("w").coerceIn(1, 512); val h = b.getInt("h").coerceIn(1, 512)
            require(x >= 0 && y >= 0 && x + w <= pw && y + h <= ph)
            val mapped = Matrix(transform).apply { postScale(pw.toFloat() / width, ph.toFloat() / height); postTranslate(-x.toFloat(), -y.toFloat()) }
            val inverse = Matrix(); mapped.invert(inverse)
            val raw = RectF(0f, 0f, w.toFloat(), h.toFloat()); inverse.mapRect(raw)
            val rect = Rect(floor(raw.left).toInt().coerceIn(0, bounds.outWidth - 1), floor(raw.top).toInt().coerceIn(0, bounds.outHeight - 1),
                ceil(raw.right).toInt().coerceIn(1, bounds.outWidth), ceil(raw.bottom).toInt().coerceIn(1, bounds.outHeight))
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output); canvas.concat(mapped)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val decoder = region
                if (decoder != null) {
                    var sample = 1
                    val ratio = min(width.toFloat() / pw, height.toFloat() / ph)
                    while (sample * 2 <= ratio) sample *= 2
                    val bitmap = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                        ?: throw ImageFailure("이미지 영역을 읽지 못했습니다.")
                    try { canvas.drawBitmap(bitmap, null, RectF(rect), paint) } finally { bitmap.recycle() }
                } else canvas.drawBitmap(requireNotNull(fallback), null, RectF(0f, 0f, bounds.outWidth.toFloat(), bounds.outHeight.toFloat()), paint)
                return output
            } catch (e: Exception) { output.recycle(); throw e }
        }
        fun close() { region?.recycle(); fallback?.recycle(); fd.close() }
    }
}
